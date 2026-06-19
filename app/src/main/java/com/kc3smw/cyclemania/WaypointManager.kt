package com.kc3smw.cyclemania

import org.osmdroid.util.GeoPoint

class WaypointManager {

    private val _waypoints = mutableListOf<GeoPoint>()
    val waypoints: List<GeoPoint> get() = _waypoints.toList()

    var onChanged: (() -> Unit)? = null

    fun addWaypoint(point: GeoPoint) {
        _waypoints.add(point)
        onChanged?.invoke()
    }

    fun removeWaypoint(index: Int) {
        if (index in _waypoints.indices) {
            _waypoints.removeAt(index)
            onChanged?.invoke()
        }
    }

    fun updateWaypoint(index: Int, point: GeoPoint) {
        if (index in _waypoints.indices) {
            _waypoints[index] = point
            onChanged?.invoke()
        }
    }

    fun clearAll() {
        _waypoints.clear()
        onChanged?.invoke()
    }

    fun loadWaypoints(points: List<GeoPoint>) {
        _waypoints.clear()
        _waypoints.addAll(points)
        // does NOT fire onChanged — caller triggers update when ready
    }

    fun getOrderedPoints(): List<GeoPoint> = _waypoints.toList()

    fun size(): Int = _waypoints.size
}
