package com.kc3smw.cyclemania

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedRide(
    val id: Long,
    val timestamp: Long,
    val distanceMeters: Double,
    val durationMs: Long,
    val avgSpeedKph: Float,
    val maxSpeedKph: Float,
    val elevationGainMeters: Double,
    val trackPoints: List<TrackPoint>
)

data class RideSummary(
    val id: Long,
    val timestamp: Long,
    val distanceMeters: Double,
    val durationMs: Long
)

object RideHistoryStore {

    private const val TAG = "RideHistoryStore"
    private const val DIR_NAME = "ride_history"

    fun saveRide(
        context: Context,
        stats: RideStats,
        avgSpeedKph: Float,
        trackPoints: List<TrackPoint>
    ): Long? {
        if (trackPoints.isEmpty()) return null
        val dir = context.getExternalFilesDir(DIR_NAME) ?: return null
        dir.mkdirs()
        val id = System.currentTimeMillis()

        val pointsArray = JSONArray()
        for (p in trackPoints) {
            pointsArray.put(JSONObject().apply {
                put("lat", p.latitude)
                put("lon", p.longitude)
                put("alt", p.altitude)
                put("t", p.timestamp)
                put("spd", p.speed)
            })
        }
        val json = JSONObject().apply {
            put("id", id)
            put("timestamp", id)
            put("distanceMeters", stats.distanceMeters)
            put("durationMs", stats.durationMs)
            put("avgSpeedKph", avgSpeedKph)
            put("maxSpeedKph", stats.maxSpeedKph)
            put("elevationGainMeters", stats.elevationGainMeters)
            put("trackPoints", pointsArray)
        }

        return try {
            File(dir, "$id.json").writeText(json.toString())
            id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save ride", e)
            null
        }
    }

    fun listRides(context: Context): List<RideSummary> {
        val dir = context.getExternalFilesDir(DIR_NAME) ?: return emptyList()
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                val obj = JSONObject(file.readText())
                RideSummary(
                    id = obj.getLong("id"),
                    timestamp = obj.getLong("timestamp"),
                    distanceMeters = obj.getDouble("distanceMeters"),
                    durationMs = obj.getLong("durationMs")
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse ride summary: ${file.name}", e)
                null
            }
        }.sortedByDescending { it.timestamp }
    }

    fun loadRide(context: Context, id: Long): SavedRide? {
        val dir = context.getExternalFilesDir(DIR_NAME) ?: return null
        val file = File(dir, "$id.json")
        if (!file.exists()) return null
        return try {
            val obj = JSONObject(file.readText())
            val pointsArray = obj.getJSONArray("trackPoints")
            val trackPoints = (0 until pointsArray.length()).map { i ->
                val p = pointsArray.getJSONObject(i)
                TrackPoint(
                    latitude = p.getDouble("lat"),
                    longitude = p.getDouble("lon"),
                    altitude = p.getDouble("alt"),
                    timestamp = p.getLong("t"),
                    speed = p.getDouble("spd").toFloat()
                )
            }
            SavedRide(
                id = obj.getLong("id"),
                timestamp = obj.getLong("timestamp"),
                distanceMeters = obj.getDouble("distanceMeters"),
                durationMs = obj.getLong("durationMs"),
                avgSpeedKph = obj.getDouble("avgSpeedKph").toFloat(),
                maxSpeedKph = obj.getDouble("maxSpeedKph").toFloat(),
                elevationGainMeters = obj.getDouble("elevationGainMeters"),
                trackPoints = trackPoints
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ride $id", e)
            null
        }
    }

    fun deleteRide(context: Context, id: Long): Boolean {
        val dir = context.getExternalFilesDir(DIR_NAME) ?: return false
        return File(dir, "$id.json").delete()
    }
}
