package com.example.pricescanner.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceStabilizerTest {
    private fun reading(value: Double, score: Double = 175.0) = PriceReading(
        value = value,
        score = score,
        sourceText = value.toString(),
        kind = PriceCandidateKind.EXPLICIT_DECIMAL
    )

    @Test
    fun locksAfterThreeStrongMatchingObservations() {
        val stabilizer = PriceStabilizer()
        assertNull(stabilizer.add(reading(4.99)))
        assertNull(stabilizer.add(reading(4.99)))
        val stable = stabilizer.add(reading(4.99))
        assertEquals(4.99, stable?.value ?: -1.0, 0.001)
        assertTrue((stable?.confidence ?: 0.0) > 0.60)
    }

    @Test
    fun oneBadFrameDoesNotChangeWinner() {
        val stabilizer = PriceStabilizer()
        stabilizer.add(reading(4.99))
        stabilizer.add(reading(4.99))
        stabilizer.add(reading(4.89, 95.0))
        val stable = stabilizer.add(reading(4.99))
        assertEquals(4.99, stable?.value ?: -1.0, 0.001)
    }

    @Test
    fun missingFramesReduceConfidenceAndDelayLock() {
        val stabilizer = PriceStabilizer()
        stabilizer.add(reading(2.49))
        stabilizer.add(null)
        stabilizer.add(reading(2.49))
        assertNull(stabilizer.add(null))
        val stable = stabilizer.add(reading(2.49))
        assertEquals(2.49, stable?.value ?: -1.0, 0.001)
    }

    @Test
    fun doesNotLockOnLowScoreNoise() {
        val stabilizer = PriceStabilizer()
        stabilizer.add(reading(299.0, 75.0))
        stabilizer.add(reading(299.0, 80.0))
        assertNull(stabilizer.add(reading(299.0, 85.0)))
    }

    @Test
    fun resetClearsHistory() {
        val stabilizer = PriceStabilizer()
        stabilizer.add(reading(6.99))
        stabilizer.add(reading(6.99))
        stabilizer.reset()
        assertNull(stabilizer.add(reading(6.99)))
        assertNull(stabilizer.add(reading(6.99)))
        assertEquals(6.99, stabilizer.add(reading(6.99))?.value ?: -1.0, 0.001)
    }
}
