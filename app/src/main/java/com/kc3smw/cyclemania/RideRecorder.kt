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
    val elevationGainMeters: Double = 0.0
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

    fun start() {
        trackPoints.clear()
        startTimeMs = System.currentTimeMillis()
        pausedDurationMs = 0L
        totalDistanceMeters = 0.0
        maxSpeedKph = 0f
        elevationGainMeters = 0.0
        lastAltitude = Double.NaN
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
        if (!lastAltitude.isNaN() && alt > lastAltitude) {
            elevationGainMeters += alt - lastAltitude
        }
        lastAltitude = alt
        val kph = speed * 3.6f
        if (kph > maxSpeedKph) maxSpeedKph = kph
        trackPoints.add(point)
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
            elevationGainMeters = elevationGainMeters
        )
    }

    fun avgSpeedKph(): Float {
        val durationHours = (currentStats().durationMs / 3_600_000.0).toFloat()
        return if (durationHours > 0f) (totalDistanceMeters.toFloat() / 1000f / durationHours) else 0f
    }

    companion object {
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
