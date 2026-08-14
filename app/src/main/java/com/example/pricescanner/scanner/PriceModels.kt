package com.example.pricescanner.scanner

import kotlin.math.max
import kotlin.math.min

data class Box(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = max(1, right - left)
    val height: Int get() = max(1, bottom - top)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun union(other: Box): Box = Box(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom)
    )
}

data class FrameSize(val width: Int, val height: Int)

data class OcrToken(
    val text: String,
    val box: Box,
    val lineText: String,
    val lineId: String
)

enum class PriceCandidateKind {
    EXPLICIT_DECIMAL,
    SPACED_DECIMAL,
    COMPOSITE,
    INTEGER_WITH_CURRENCY,
    INTEGER_FALLBACK
}

data class PriceReading(
    val value: Double,
    val score: Double,
    val sourceText: String,
    val kind: PriceCandidateKind
) {
    val cents: Long get() = kotlin.math.round(value * 100.0).toLong()
}

data class StablePrice(
    val value: Double,
    val confidence: Double,
    val votes: Int,
    val frames: Int
)
