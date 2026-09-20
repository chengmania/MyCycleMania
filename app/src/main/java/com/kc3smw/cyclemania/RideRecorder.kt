package com.kc3smw.cyclemania

import kotlin.math.*

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timestamp: Long,
    val speed: Float
)

data class RideStats(
    val distanceMeters: Double = 0.0,
    val durationMs: Long = 0L,
    val currentSpeedKph: Float = 0f,
    val maxSpeedKph: Float = 0f,
    val elevationGainMeters: Double = 0.0,
    val currentAltitudeMeters: Double = 0.0,
    val currentGradePercent: Double = 0.0
)

class RideRecorder {
    val trackPoints = mutableListOf<TrackPoint>()
    private var startTimeMs: Long = 0L
    private var pausedDurationMs: Long = 0L
    private var pauseStartMs: Long = 0L
    private var isPaused = false
    private var totalDistanceMeters = 0.0
    private var maxSpeedKph = 0f
    private var elevationGainMeters = 0.0
    private var lastAltitude = Double.NaN
    private var currentGradePercent = 0.0

    // Trailing window of (cumulative distance, altitude) used to compute a
    // smoothed live grade over the last GRADE_WINDOW_METERS of travel, rather
    // than a single noisy point-to-point delta.
    private val gradeWindow = ArrayDeque<Pair<Double, Double>>()

    fun start() {
        trackPoints.clear()
        startTimeMs = System.currentTimeMillis()
        pausedDurationMs = 0L
        totalDistanceMeters = 0.0
        maxSpeedKph = 0f
        elevationGainMeters = 0.0
        lastAltitude = Double.NaN
        currentGradePercent = 0.0
        gradeWindow.clear()
        isPaused = false
    }

    fun pause() {
        if (!isPaused) {
            pauseStartMs = System.currentTimeMillis()
            isPaused = true
        }
    }

    fun resume() {
        if (isPaused) {
            pausedDurationMs += System.currentTimeMillis() - pauseStartMs
            isPaused = false
        }
    }

    fun addPoint(lat: Double, lon: Double, alt: Double, speed: Float) {
        if (isPaused) return
        val point = TrackPoint(lat, lon, alt, System.currentTimeMillis(), speed)
        if (trackPoints.isNotEmpty()) {
            val last = trackPoints.last()
            totalDistanceMeters += haversineMeters(last.latitude, last.longitude, lat, lon)
        }
        if (!lastAltitude.isNaN()) {
            val delta = alt - lastAltitude
            // Ignore sub-noise-floor deltas so small sensor jitter doesn't
            // silently accumulate into a wildly overstated total gain.
            if (delta > ELEVATION_NOISE_THRESHOLD_METERS) elevationGainMeters += delta
        }
        lastAltitude = alt
        updateLiveGrade(alt)
        val kph = speed * 3.6f
        if (kph > maxSpeedKph) maxSpeedKph = kph
        trackPoints.add(point)
    }

    private fun updateLiveGrade(alt: Double) {
        gradeWindow.addLast(totalDistanceMeters to alt)
        while (gradeWindow.isNotEmpty() && totalDistanceMeters - gradeWindow.first().first > GRADE_WINDOW_METERS) {
            gradeWindow.removeFirst()
        }
        val oldest = gradeWindow.firstOrNull() ?: return
        val runMeters = totalDistanceMeters - oldest.first
        currentGradePercent = if (runMeters >= MIN_GRADE_RUN_METERS) {
            (alt - oldest.second) / runMeters * 100.0
        } else {
            0.0
        }
    }

    fun currentStats(): RideStats {
        val elapsed = if (isPaused) pauseStartMs - startTimeMs - pausedDurationMs
        else System.currentTimeMillis() - startTimeMs - pausedDurationMs
        val currentKph = (trackPoints.lastOrNull()?.speed ?: 0f) * 3.6f
        return RideStats(
            distanceMeters = totalDistanceMeters,
            durationMs = elapsed.coerceAtLeast(0L),
            currentSpeedKph = currentKph,
            maxSpeedKph = maxSpeedKph,
            elevationGainMeters = elevationGainMeters,
            currentAltitudeMeters = lastAltitude.takeUnless { it.isNaN() } ?: 0.0,
            currentGradePercent = currentGradePercent
        )
    }

    fun avgSpeedKph(): Float {
        val durationHours = (currentStats().durationMs / 3_600_000.0).toFloat()
        return if (durationHours > 0f) (totalDistanceMeters.toFloat() / 1000f / durationHours) else 0f
    }

    companion object {
        private const val ELEVATION_NOISE_THRESHOLD_METERS = 0.3
        private const val GRADE_WINDOW_METERS = 25.0
        private const val MIN_GRADE_RUN_METERS = 8.0

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }
    }
}
