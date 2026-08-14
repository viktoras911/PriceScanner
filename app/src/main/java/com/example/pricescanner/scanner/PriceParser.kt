package com.example.pricescanner.scanner

import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

class PriceParser {

    private data class Candidate(
        val price: Double,
        val box: Box,
        val sourceText: String,
        val sourceLineIds: Set<String>,
        val kind: PriceCandidateKind,
        val directCurrency: Boolean,
        var score: Double
    )

    private val explicitDecimalRegex = Regex(
        """(?<!\d)(\d{1,4})\s*[,\.:·]\s*(\d{2})(?!\d)"""
    )

    private val spacedDecimalRegex = Regex(
        """^\s*(\d{1,4})\s+(\d{2})\s*(?:€|eur)?\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val integerCurrencyRegex = Regex(
        """(?i)(?:€\s*(\d{1,4})(?!\d)|(\d{1,4})\s*(?:€|eur)(?!\p{L}))"""
    )

    private val currencyRegex = Regex("""(?i)(?:€|\beur\b)""")
    private val barcodeRegex = Regex("""\d{8,}""")
    private val unitRegex = Regex(
        """(?i)(?:€|eur)?\s*/\s*(?:\d+(?:[.,]\d+)?\s*)?(?:kg|g|l|ml|vnt|pcs)\b|\b(?:kg|g|l|ml|vnt|pcs)\b"""
    )
    private val oldPriceRegex = Regex(
        """(?i)(?<!\p{L})(?:sena|ankstesnė|ankstesne|anksčiau|anksciau|įprasta|iprasta|buvo|be\s+nuolaidos|reguliari|reguliarioji|prieš|pries)(?!\p{L})"""
    )

    fun parse(tokens: List<OcrToken>, frameSize: FrameSize): PriceReading? {
        if (tokens.isEmpty() || frameSize.width <= 0 || frameSize.height <= 0) return null

        val candidates = mutableListOf<Candidate>()
        val normalizedTokens = tokens.map { token -> token to normalizeNumericText(token.text) }

        for ((token, normalized) in normalizedTokens) {
            val lower = token.text.lowercase(Locale.ROOT)
            val digitsOnly = normalized.filter(Char::isDigit)

            if ('%' in token.text) continue
            if (barcodeRegex.containsMatchIn(digitsOnly)) continue

            val directCurrency = currencyRegex.containsMatchIn(lower)

            explicitDecimalRegex.findAll(normalized).forEach { match ->
                val value = parsePrice(match.groupValues[1], match.groupValues[2]) ?: return@forEach
                candidates += Candidate(
                    price = value,
                    box = token.box,
                    sourceText = token.text,
                    sourceLineIds = setOf(token.lineId),
                    kind = PriceCandidateKind.EXPLICIT_DECIMAL,
                    directCurrency = directCurrency,
                    score = 138.0
                )
            }

            spacedDecimalRegex.find(normalized)?.let { match ->
                val value = parsePrice(match.groupValues[1], match.groupValues[2]) ?: return@let
                candidates += Candidate(
                    price = value,
                    box = token.box,
                    sourceText = token.text,
                    sourceLineIds = setOf(token.lineId),
                    kind = PriceCandidateKind.SPACED_DECIMAL,
                    directCurrency = directCurrency,
                    score = 126.0
                )
            }

            integerCurrencyRegex.find(normalized)?.let { match ->
                val number = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
                val value = number?.toDoubleOrNull()?.takeIf { it in MIN_PRICE..MAX_PRICE } ?: return@let
                candidates += Candidate(
                    price = value,
                    box = token.box,
                    sourceText = token.text,
                    sourceLineIds = setOf(token.lineId),
                    kind = PriceCandidateKind.INTEGER_WITH_CURRENCY,
                    directCurrency = true,
                    score = 102.0
                )
            }
        }

        candidates += buildCompositeCandidates(tokens)

        // Whole-euro prices without a decimal part are allowed only as a last resort.
        // Stability + nearby € + size/position must still make them win.
        if (candidates.none { it.kind != PriceCandidateKind.INTEGER_FALLBACK }) {
            for ((token, normalized) in normalizedTokens) {
                if ('%' in token.text) continue
                val digits = normalized.trim()
                if (!digits.matches(Regex("""\d{1,4}"""))) continue
                if (barcodeRegex.containsMatchIn(digits)) continue

                val value = digits.toDoubleOrNull()?.takeIf { it in MIN_PRICE..MAX_PRICE } ?: continue
                candidates += Candidate(
                    price = value,
                    box = token.box,
                    sourceText = token.text,
                    sourceLineIds = setOf(token.lineId),
                    kind = PriceCandidateKind.INTEGER_FALLBACK,
                    directCurrency = false,
                    score = 24.0
                )
            }
        }

        if (candidates.isEmpty()) return null

        val numericTokenHeights = normalizedTokens
            .filter { (_, text) -> text.any(Char::isDigit) }
            .map { (token, _) -> token.box.height }
        val maxNumericHeight = max(1, numericTokenHeights.maxOrNull() ?: 1).toDouble()

        for (candidate in candidates) {
            val heightRatio = candidate.box.height / maxNumericHeight
            candidate.score += heightRatio.coerceIn(0.0, 1.25) * 62.0
            candidate.score += centerScore(candidate.box, frameSize) * 36.0

            if (candidate.directCurrency) candidate.score += 18.0
            if (hasNearbyCurrency(candidate.box, tokens)) candidate.score += 28.0

            applyContextPenalties(candidate, tokens)

            if (candidate.price >= 1000.0) candidate.score -= 55.0
            if (candidate.kind == PriceCandidateKind.INTEGER_FALLBACK && !hasNearbyCurrency(candidate.box, tokens)) {
                candidate.score -= 22.0
            }
        }

        val best = candidates
            .filter { it.price in MIN_PRICE..MAX_PRICE }
            .maxByOrNull { it.score }
            ?: return null

        return if (best.score >= MIN_ACCEPTED_SCORE) {
            PriceReading(
                value = best.price,
                score = best.score,
                sourceText = best.sourceText,
                kind = best.kind
            )
        } else {
            null
        }
    }

    private fun buildCompositeCandidates(tokens: List<OcrToken>): List<Candidate> {
        val numeric = tokens.mapNotNull { token ->
            if ('%' in token.text) return@mapNotNull null
            val normalized = normalizeNumericText(token.text).trim()
            val digits = standaloneNumberRegex.matchEntire(normalized)?.groupValues?.get(1)
                ?: return@mapNotNull null
            token to digits
        }

        val result = mutableListOf<Candidate>()

        for ((main, euros) in numeric) {
            if (euros.length !in 1..4) continue

            val centsCandidates = numeric
                .filter { (cents, digits) -> cents !== main && digits.length == 2 }
                .filter { (cents, _) -> isPlausibleCentPair(main.box, cents.box) }
                .sortedBy { (cents, _) -> horizontalGap(main.box, cents.box) }

            // Ambiguous geometry is deliberately rejected instead of guessing.
            if (centsCandidates.isEmpty()) continue

            val (centsToken, centsDigits) = centsCandidates.first()
            val value = parsePrice(euros, centsDigits) ?: continue
            val merged = main.box.union(centsToken.box)

            result += Candidate(
                price = value,
                box = merged,
                sourceText = "${main.text} ${centsToken.text}",
                sourceLineIds = setOf(main.lineId, centsToken.lineId),
                kind = PriceCandidateKind.COMPOSITE,
                directCurrency = currencyRegex.containsMatchIn(main.text) || currencyRegex.containsMatchIn(centsToken.text),
                score = if (main.lineId == centsToken.lineId) 144.0 else 126.0
            )
        }

        return result.distinctBy { Triple(it.price, it.box.left, it.box.top) }
    }

    private fun isPlausibleCentPair(main: Box, cents: Box): Boolean {
        if (cents.centerX <= main.centerX) return false

        val gap = horizontalGap(main, cents)
        val maxGap = max(main.height, main.width) * 1.45f
        if (gap < -main.width * 0.18f || gap > maxGap) return false

        val verticalCenterDistance = kotlin.math.abs(cents.centerY - main.centerY)
        val verticalTolerance = max(main.height, cents.height) * 0.95f
        if (verticalCenterDistance > verticalTolerance) return false

        val sizeRatio = cents.height.toFloat() / main.height.toFloat()
        return sizeRatio in 0.30f..1.25f
    }

    private fun horizontalGap(a: Box, b: Box): Float = when {
        a.right < b.left -> (b.left - a.right).toFloat()
        b.right < a.left -> (a.left - b.right).toFloat()
        else -> 0f
    }

    private fun applyContextPenalties(candidate: Candidate, tokens: List<OcrToken>) {
        val sourceLines = tokens.filter { it.lineId in candidate.sourceLineIds }
        val sourceLineText = sourceLines.joinToString(" ") { it.lineText }.lowercase(Locale.ROOT)

        if (oldPriceRegex.containsMatchIn(sourceLineText)) candidate.score -= 155.0
        if ('%' in sourceLineText) candidate.score -= 72.0
        if (unitRegex.containsMatchIn(sourceLineText)) candidate.score -= 42.0

        for (token in tokens) {
            val lower = token.text.lowercase(Locale.ROOT)
            if (!isNear(candidate.box, token.box, multiplier = 2.0f)) continue

            if (oldPriceRegex.containsMatchIn(lower)) candidate.score -= 125.0
            if ('%' in lower) candidate.score -= 105.0

            val localUnitSignal = unitRegex.containsMatchIn(lower) ||
                (lower in setOf("kg", "g", "l", "ml", "vnt", "pcs"))
            if (localUnitSignal) candidate.score -= 145.0
        }
    }

    private fun hasNearbyCurrency(box: Box, tokens: List<OcrToken>): Boolean = tokens.any { token ->
        currencyRegex.containsMatchIn(token.text.lowercase(Locale.ROOT)) &&
            isNear(box, token.box, multiplier = 1.65f)
    }

    private fun isNear(a: Box, b: Box, multiplier: Float): Boolean {
        val dx = when {
            a.right < b.left -> (b.left - a.right).toFloat()
            b.right < a.left -> (a.left - b.right).toFloat()
            else -> 0f
        }
        val dy = when {
            a.bottom < b.top -> (b.top - a.bottom).toFloat()
            b.bottom < a.top -> (a.top - b.bottom).toFloat()
            else -> 0f
        }
        val distance = sqrt(dx * dx + dy * dy)
        val reference = max(max(a.height, b.height), 1).toFloat()
        return distance <= reference * multiplier
    }

    private fun centerScore(box: Box, frame: FrameSize): Double {
        val dx = (box.centerX - frame.width / 2f) / max(1f, frame.width / 2f)
        val dy = (box.centerY - frame.height / 2f) / max(1f, frame.height / 2f)
        val distance = sqrt(dx * dx + dy * dy).coerceIn(0f, 1.5f)
        return (1.0 - distance / 1.5).coerceIn(0.0, 1.0)
    }

    private fun parsePrice(euros: String, cents: String): Double? {
        if (euros.length !in 1..4 || cents.length != 2) return null
        return "$euros.$cents".toDoubleOrNull()?.takeIf { it in MIN_PRICE..MAX_PRICE }
    }

    internal fun normalizeNumericText(text: String): String {
        if (text.none { it.isDigit() || it in SUPERSCRIPT_DIGITS.keys }) return text

        return buildString(text.length) {
            for (ch in text) {
                append(
                    when {
                        ch in SUPERSCRIPT_DIGITS -> SUPERSCRIPT_DIGITS.getValue(ch)
                        ch == 'O' || ch == 'o' -> '0'
                        ch == 'I' || ch == 'l' || ch == '|' -> '1'
                        ch == '\u00A0' || ch == '\u202F' -> ' '
                        else -> ch
                    }
                )
            }
        }
    }

    companion object {
        private const val MIN_PRICE = 0.01
        private const val MAX_PRICE = 9999.99
        private const val MIN_ACCEPTED_SCORE = 110.0

        private val standaloneNumberRegex = Regex(
            """(?i)^\s*(?:€\s*)?(\d{1,4})\s*[,.:·]?\s*(?:€|eur)?\s*$"""
        )

        private val SUPERSCRIPT_DIGITS = mapOf(
            '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4',
            '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9'
        )
    }
}
