package com.example.pricescanner.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceParserTest {
    private val parser = PriceParser()
    private val frame = FrameSize(300, 140)

    private fun token(
        text: String,
        x: Int = 90,
        y: Int = 35,
        w: Int = 100,
        h: Int = 60,
        lineText: String = text,
        lineId: String = "0:0"
    ) = OcrToken(text, Box(x, y, x + w, y + h), lineText, lineId)

    @Test
    fun parsesCommonDecimalFormats() {
        val cases = mapOf(
            "4,99" to 4.99,
            "4.99" to 4.99,
            "4:99" to 4.99,
            "4·99" to 4.99,
            "12,49" to 12.49,
            "12.49 €" to 12.49,
            "€ 12,49" to 12.49,
            "0,99" to 0.99,
            "99,99" to 99.99,
            "999,99" to 999.99,
            "1,00" to 1.00,
            "2,05" to 2.05,
            "15,90" to 15.90,
            "25.50" to 25.50,
            "3:45 EUR" to 3.45,
            " 7,29 " to 7.29,
            "7,29€" to 7.29,
            "O,99" to 0.99,
            "I,99" to 1.99,
            "1,⁹⁹" to 1.99
        )

        cases.forEach { (raw, expected) ->
            val result = parser.parse(listOf(token(raw)), frame)
            assertEquals("Failed for $raw", expected, result?.value ?: -1.0, 0.001)
        }
    }

    @Test
    fun parsesSpacedPriceFormats() {
        val cases = mapOf(
            "4 99" to 4.99,
            "12 49" to 12.49,
            "3 05" to 3.05,
            "99 90" to 99.90,
            "1 00 €" to 1.00,
            "15 79 EUR" to 15.79,
            "0 89" to 0.89,
            "8 09" to 8.09
        )

        cases.forEach { (raw, expected) ->
            val result = parser.parse(listOf(token(raw)), frame)
            assertEquals("Failed for $raw", expected, result?.value ?: -1.0, 0.001)
        }
    }

    @Test
    fun rejectsDiscountsBarcodesAndUnitPrices() {
        val rejected = listOf(
            "-30%",
            "50%",
            "5901234123457",
            "4771234567890",
            "7,49 €/kg",
            "3,29 €/l",
            "1,99 EUR/kg",
            "0,89 €/100g",
            "0,89 €/100 g",
            "2,49 €/100ml",
            "2,49 €/100 ml",
            "9,99 kg"
        )

        rejected.forEach { raw ->
            val result = parser.parse(listOf(token(raw)), frame)
            assertNull("Should reject $raw but got $result", result)
        }
    }

    @Test
    fun rejectsOldPriceContext() {
        val cases = listOf(
            "BUVO 6,99",
            "sena kaina 7,49",
            "ankstesnė 4,59",
            "įprasta kaina 5,99",
            "be nuolaidos 12,99",
            "prieš 3,49"
        )

        cases.forEach { raw ->
            val result = parser.parse(listOf(token(raw, lineText = raw)), frame)
            assertNull("Should reject old price: $raw", result)
        }
    }

    @Test
    fun composesSeparatedEurosAndCents() {
        val cases = listOf(
            Triple("4", "99", 4.99),
            Triple("12", "49", 12.49),
            Triple("1", "05", 1.05),
            Triple("25", "90", 25.90),
            Triple("99", "99", 99.99),
            Triple("0", "79", 0.79)
        )

        cases.forEach { (euros, cents, expected) ->
            val tokens = listOf(
                token(euros, x = 70, y = 25, w = 85, h = 80, lineText = "$euros $cents €", lineId = "0:0"),
                token(cents, x = 158, y = 35, w = 50, h = 45, lineText = "$euros $cents €", lineId = "0:0"),
                token("€", x = 215, y = 45, w = 25, h = 35, lineText = "$euros $cents €", lineId = "0:0")
            )
            val result = parser.parse(tokens, frame)
            assertEquals("Failed composite $euros $cents", expected, result?.value ?: -1.0, 0.001)
            assertEquals(PriceCandidateKind.COMPOSITE, result?.kind)
        }
    }

    @Test
    fun choosesLargeMainPriceOverSmallerUnitPrice() {
        val tokens = listOf(
            token("4", x = 55, y = 20, w = 90, h = 90, lineText = "4 99 €", lineId = "0:0"),
            token("99", x = 148, y = 30, w = 48, h = 48, lineText = "4 99 €", lineId = "0:0"),
            token("€", x = 200, y = 40, w = 25, h = 35, lineText = "4 99 €", lineId = "0:0"),
            token("7,49", x = 105, y = 108, w = 55, h = 20, lineText = "7,49 €/kg", lineId = "1:0"),
            token("€/kg", x = 165, y = 108, w = 50, h = 20, lineText = "7,49 €/kg", lineId = "1:0")
        )

        val result = parser.parse(tokens, FrameSize(280, 145))
        assertEquals(4.99, result?.value ?: -1.0, 0.001)
    }

    @Test
    fun choosesCurrentPriceOverOldPrice() {
        val tokens = listOf(
            token("5,49", x = 80, y = 25, w = 120, h = 70, lineText = "5,49 €", lineId = "0:0"),
            token("€", x = 205, y = 45, w = 20, h = 30, lineText = "5,49 €", lineId = "0:0"),
            token("BUVO", x = 70, y = 108, w = 45, h = 18, lineText = "BUVO 6,99", lineId = "1:0"),
            token("6,99", x = 120, y = 108, w = 50, h = 18, lineText = "BUVO 6,99", lineId = "1:0")
        )

        val result = parser.parse(tokens, frame)
        assertEquals(5.49, result?.value ?: -1.0, 0.001)
    }

    @Test
    fun centerAndSizeInfluenceSelection() {
        val tokens = listOf(
            token("8,99", x = 112, y = 42, w = 95, h = 62, lineText = "8,99 €", lineId = "0:0"),
            token("€", x = 210, y = 55, w = 20, h = 25, lineText = "8,99 €", lineId = "0:0"),
            token("3,49", x = 5, y = 8, w = 45, h = 20, lineText = "3,49", lineId = "1:0")
        )

        val result = parser.parse(tokens, frame)
        assertEquals(8.99, result?.value ?: -1.0, 0.001)
        assertTrue((result?.score ?: 0.0) > 150.0)
    }
    @Test
    fun composesWhenCurrencyIsAttachedToCents() {
        val tokens = listOf(
            token("4", x = 70, y = 25, w = 85, h = 80, lineText = "4 99€", lineId = "0:0"),
            token("99€", x = 158, y = 35, w = 60, h = 45, lineText = "4 99€", lineId = "0:0")
        )

        val result = parser.parse(tokens, frame)
        assertEquals(4.99, result?.value ?: -1.0, 0.001)
        assertEquals(PriceCandidateKind.COMPOSITE, result?.kind)
    }

}
