package com.kc3smw.cyclemania

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File

data class SavedRoute(
    val id: Long,
    val name: String,
    val createdTimestamp: Long,
    val waypoints: List<GeoPoint>,
    val routePoints: List<GeoPoint>,
    val distanceMeters: Double,
    val timeSeconds: Double
)

data class RouteSummary(
    val id: Long,
    val name: String,
    val createdTimestamp: Long,
    val distanceMeters: Double
)

object SavedRouteStore {

    private const val TAG = "SavedRouteStore"
    private const val DIR_NAME = "routes"

    private fun geoPointsToJson(points: List<GeoPoint>): JSONArray {
        val array = JSONArray()
        for (p in points) {
            array.put(JSONObject().apply {
                put("lat", p.latitude)
                put("lon", p.longitude)
            })
        }
        return array
    }

    private fun jsonToGeoPoints(array: JSONArray): List<GeoPoint> =
        (0 until array.length()).map { i ->
            val p = array.getJSONObject(i)
            GeoPoint(p.getDouble("lat"), p.getDouble("lon"))
        }

    fun saveRoute(
        context: Context,
        name: String,
        waypoints: List<GeoPoint>,
        routePoints: List<GeoPoint>,
        distanceMeters: Double,
        timeSeconds: Double
    ): Long? {
        if (waypoints.isEmpty()) return null
        val dir = context.getExternalFilesDir(DIR_NAME) ?: return null
        dir.mkdirs()
        val id = System.currentTimeMillis()

        val json = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("createdTimestamp", id)
            put("waypoints", geoPointsToJson(waypoints))
            put("routePoints", geoPointsToJson(routePoints))
            put("distanceMeters", distanceMeters)
            put("timeSeconds", timeSeconds)
        }

        return try {
            File(dir, "$id.json").writeText(json.toString())
            id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save route", e)
            null
        }
    }

    fun listRoutes(context: Context): List<RouteSummary> {
        val dir = context.getExternalFilesDir(DIR_NAME) ?: return emptyList()
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                val obj = JSONObject(file.readText())
                RouteSummary(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    createdTimestamp = obj.getLong("createdTimestamp"),
                    distanceMeters = obj.getDouble("distanceMeters")
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse route summary: ${file.name}", e)
                null
            }
        }.sortedByDescending { it.createdTimestamp }
    }

    fun loadRoute(context: Context, id: Long): SavedRoute? {
        val dir = context.getExternalFilesDir(DIR_NAME) ?: return null
        val file = File(dir, "$id.json")
        if (!file.exists()) return null
        return try {
            val obj = JSONObject(file.readText())
            SavedRoute(
                id = obj.getLong("id"),
                name = obj.getString("name"),
                createdTimestamp = obj.getLong("createdTimestamp"),
                waypoints = jsonToGeoPoints(obj.getJSONArray("waypoints")),
                routePoints = jsonToGeoPoints(obj.getJSONArray("routePoints")),
                distanceMeters = obj.getDouble("distanceMeters"),
                timeSeconds = obj.getDouble("timeSeconds")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load route $id", e)
            null
        }
    }

    fun renameRoute(context: Context, id: Long, newName: String): Boolean {
        val dir = context.getExternalFilesDir(DIR_NAME) ?: return false
        val file = File(dir, "$id.json")
        if (!file.exists()) return false
        return try {
            val obj = JSONObject(file.readText())
            obj.put("name", newName)
            file.writeText(obj.toString())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename route $id", e)
            false
        }
    }

    fun deleteRoute(context: Context, id: Long): Boolean {
        val dir = context.getExternalFilesDir(DIR_NAME) ?: return false
        return File(dir, "$id.json").delete()
    }
}
