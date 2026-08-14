package com.example.pricescanner.scanner

import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

class PriceStabilizer(
    private val historySize: Int = 7,
    private val minimumVotes: Int = 3,
    private val minimumWeightedShare: Double = 0.58,
    private val minimumAverageScore: Double = 105.0
) {
    private data class Observation(val reading: PriceReading?)
    private val history = ArrayDeque<Observation>()

    fun add(reading: PriceReading?): StablePrice? {
        history.addLast(Observation(reading))
        while (history.size > historySize) history.removeFirst()

        if (history.size < minimumVotes) return null
        if (reading == null) return null

        val nonNull = history.mapNotNull { it.reading }
        if (nonNull.size < minimumVotes) return null

        val groups = nonNull.groupBy { it.cents }
        val winner = groups.maxByOrNull { (_, readings) -> readings.sumOf(::weight) } ?: return null
        val winnerCents = winner.key
        val winnerReadings = winner.value

        // Do not lock on a price that is no longer the current observation.
        if (reading.cents != winnerCents) return null

        val totalWeight = nonNull.sumOf(::weight) + (history.size - nonNull.size) * NULL_FRAME_WEIGHT
        val winnerWeight = winnerReadings.sumOf(::weight)
        val weightedShare = if (totalWeight > 0.0) winnerWeight / totalWeight else 0.0
        val averageScore = winnerReadings.map { it.score }.average()

        if (winnerReadings.size < minimumVotes) return null
        if (weightedShare < minimumWeightedShare) return null
        if (averageScore < minimumAverageScore) return null

        val scoreConfidence = ((averageScore - 90.0) / 115.0).coerceIn(0.0, 1.0)
        val voteConfidence = (winnerReadings.size.toDouble() / max(history.size, minimumVotes)).coerceIn(0.0, 1.0)
        val confidence = (weightedShare * 0.55 + scoreConfidence * 0.30 + voteConfidence * 0.15)
            .coerceIn(0.0, 0.99)

        return StablePrice(
            value = winnerCents / 100.0,
            confidence = confidence,
            votes = winnerReadings.size,
            frames = history.size
        )
    }

    fun reset() = history.clear()

    private fun weight(reading: PriceReading): Double = min(220.0, max(45.0, reading.score))

    companion object {
        private const val NULL_FRAME_WEIGHT = 38.0
    }
}
