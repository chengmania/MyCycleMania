package com.kc3smw.cyclemania

import kotlin.math.abs

/**
 * Post-ride elevation analysis shared by the Ride Summary elevation chart and
 * the grade-colored route line: smooths the recorded altitude series, breaks
 * the ride into short grade-labeled chunks, and rolls those chunks up into an
 * overall hill-difficulty rating.
 */
object ElevationProfile {

    enum class GradeCategory { FLAT, UPHILL, DOWNHILL }

    /** A short run of the ride with a roughly-constant grade. */
    data class Chunk(
        val startIndex: Int,
        val endIndex: Int, // inclusive
        val distanceMeters: Double,
        val avgGradePercent: Double,
        val category: GradeCategory
    )

    data class Profile(
        val smoothedAltitudes: DoubleArray,
        val cumulativeDistances: DoubleArray,
        val chunks: List<Chunk>,
        val totalAscentMeters: Double,
        val totalDescentMeters: Double,
        val minAltitudeMeters: Double,
        val maxAltitudeMeters: Double
    )

    const val FLAT_THRESHOLD_PERCENT = 2.0
    private const val CHUNK_DISTANCE_METERS = 40.0
    private const val MIN_CHUNK_DISTANCE_METERS = 10.0
    private const val SMOOTHING_WINDOW = 5

    fun build(points: List<TrackPoint>): Profile? {
        if (points.size < 2) return null

        val smoothed = smoothAltitudes(points)
        val cumulative = cumulativeDistances(points)

        var ascent = 0.0
        var descent = 0.0
        for (i in 1 until smoothed.size) {
            val delta = smoothed[i] - smoothed[i - 1]
            if (delta > 0) ascent += delta else descent += -delta
        }

        val chunks = buildChunks(cumulative, smoothed)

        return Profile(
            smoothedAltitudes = smoothed,
            cumulativeDistances = cumulative,
            chunks = chunks,
            totalAscentMeters = ascent,
            totalDescentMeters = descent,
            minAltitudeMeters = smoothed.min(),
            maxAltitudeMeters = smoothed.max()
        )
    }

    /** Simple centered moving average to knock down point-to-point sensor noise. */
    private fun smoothAltitudes(points: List<TrackPoint>): DoubleArray {
        val n = points.size
        val out = DoubleArray(n)
        val half = SMOOTHING_WINDOW / 2
        for (i in 0 until n) {
            val lo = (i - half).coerceAtLeast(0)
            val hi = (i + half).coerceAtMost(n - 1)
            var sum = 0.0
            for (j in lo..hi) sum += points[j].altitude
            out[i] = sum / (hi - lo + 1)
        }
        return out
    }

    private fun cumulativeDistances(points: List<TrackPoint>): DoubleArray {
        val n = points.size
        val out = DoubleArray(n)
        for (i in 1 until n) {
            out[i] = out[i - 1] + RideRecorder.haversineMeters(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude
            )
        }
        return out
    }

    /**
     * Groups the ride into ~[CHUNK_DISTANCE_METERS]-long runs, each labeled
     * with its average grade and flat/uphill/downhill category.
     */
    private fun buildChunks(cumulative: DoubleArray, altitudes: DoubleArray): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var startIdx = 0
        for (i in 1 until cumulative.size) {
            val runSoFar = cumulative[i] - cumulative[startIdx]
            val isLast = i == cumulative.size - 1
            if (runSoFar >= CHUNK_DISTANCE_METERS || isLast) {
                val distance = cumulative[i] - cumulative[startIdx]
                // Below this, a tiny GPS-jitter distance in the denominator can turn a
                // sub-meter altitude wobble into a wildly exaggerated grade percentage.
                val grade = if (distance >= MIN_CHUNK_DISTANCE_METERS) {
                    (altitudes[i] - altitudes[startIdx]) / distance * 100.0
                } else {
                    0.0
                }
                val category = when {
                    abs(grade) < FLAT_THRESHOLD_PERCENT -> GradeCategory.FLAT
                    grade > 0 -> GradeCategory.UPHILL
                    else -> GradeCategory.DOWNHILL
                }
                chunks.add(Chunk(startIdx, i, distance, grade, category))
                startIdx = i
            }
        }
        return chunks
    }

    enum class HillRating(val labelResId: Int) {
        FLAT(R.string.hills_flat),
        ROLLING(R.string.hills_rolling),
        MODERATE(R.string.hills_moderate),
        STEEP(R.string.hills_steep),
        VERY_STEEP(R.string.hills_very_steep)
    }

    /** Classifies overall ride difficulty by total ascent per mile ridden. */
    fun classifyHillRating(totalAscentMeters: Double, totalDistanceMeters: Double): HillRating {
        if (totalDistanceMeters <= 0) return HillRating.FLAT
        val ascentFtPerMile = (totalAscentMeters * 3.28084) / (totalDistanceMeters / 1609.34)
        return when {
            ascentFtPerMile < 50 -> HillRating.FLAT
            ascentFtPerMile < 100 -> HillRating.ROLLING
            ascentFtPerMile < 200 -> HillRating.MODERATE
            ascentFtPerMile < 400 -> HillRating.STEEP
            else -> HillRating.VERY_STEEP
        }
    }
}
