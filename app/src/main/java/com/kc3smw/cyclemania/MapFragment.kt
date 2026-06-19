package com.kc3smw.cyclemania

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

class MapFragment : Fragment() {

    lateinit var mapView: MapView
        private set

    // Location marker
    private var locationMarker: Marker? = null
    private var currentLat = 0.0
    private var currentLon = 0.0
    private var currentBearing = 0f

    // Follow + orientation mode
    private var followMode = true
    private var northUpMode = true     // default north-up; heading-up via orientation FAB
    private var userIsTouching = false
    var onFollowModeChanged: ((Boolean) -> Unit)? = null
    var onNorthUpChanged: ((Boolean) -> Unit)? = null

    // Terrain overlay
    private var terrainOverlay: TilesOverlay? = null
    private var terrainEnabled = false

    // Cycle map tile source
    private val cyclosmTileSource = XYTileSource(
        "CyclOSM", 0, 19, 256, ".png",
        arrayOf(
            "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/",
            "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/",
            "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/"
        )
    )
    private var cyclosmActive = false

    // Waypoint markers
    private val waypointMarkers = mutableListOf<Marker>()
    var onWaypointDeleteRequest: ((Int) -> Unit)? = null
    var onWaypointDragged: ((Int, GeoPoint) -> Unit)? = null

    // Track and route polylines
    private var trackPolyline: Polyline? = null
    private var routePolyline: Polyline? = null

    // Long press callback
    var onLongPressListener: ((GeoPoint) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        val offlineTileDir = requireContext().getExternalFilesDir("tiles")
        if (offlineTileDir != null) {
            Configuration.getInstance().osmdroidTileCache = offlineTileDir
        }
        mapView = MapView(requireContext())
        return mapView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMap()
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.isHorizontalMapRepetitionEnabled = false
        mapView.isVerticalMapRepetitionEnabled = false
        mapView.controller.setZoom(15.0)

        // Terrain overlay (built but not yet added to overlays)
        setupTerrainOverlay()

        // Compass
        val compass = CompassOverlay(
            requireContext(),
            InternalCompassOrientationProvider(requireContext()),
            mapView
        )
        compass.enableCompass()
        mapView.overlays.add(compass)

        // Touch detector overlay — must be before scroll listener so flag is set first
        mapView.overlays.add(object : Overlay() {
            override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN -> userIsTouching = true
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_POINTER_UP,
                    MotionEvent.ACTION_CANCEL -> userIsTouching = false
                }
                return false
            }
        })

        // Long press overlay — defer callback via post() so we are NOT modifying
        // mapView.overlays while OSMDroid is still iterating it for this touch event.
        mapView.overlays.add(object : Overlay() {
            override fun onLongPress(e: MotionEvent, mapView: MapView): Boolean {
                val gp = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) as? GeoPoint
                    ?: return true
                mapView.post { onLongPressListener?.invoke(gp) }
                return true
            }
        })

        // Disengage follow mode on user-initiated scroll or zoom
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent): Boolean {
                if (userIsTouching && followMode) disengageFollowMode()
                return false
            }
            override fun onZoom(event: ZoomEvent): Boolean {
                if (userIsTouching && followMode) disengageFollowMode()
                return false
            }
        })
    }

    private fun setupTerrainOverlay() {
        val topoSource = XYTileSource(
            "OpenTopoMap", 0, 17, 256, ".png",
            arrayOf(
                "https://a.tile.opentopomap.org/",
                "https://b.tile.opentopomap.org/",
                "https://c.tile.opentopomap.org/"
            )
        )
        val topoProvider = MapTileProviderBasic(requireContext(), topoSource)
        terrainOverlay = object : TilesOverlay(topoProvider, requireContext()) {
            private val alphaPaint = Paint().apply { alpha = (255 * 0.6f).toInt() }
            override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
                val sc = canvas.save()
                canvas.saveLayerAlpha(null, alphaPaint.alpha)
                super.draw(canvas, mapView, shadow)
                canvas.restoreToCount(sc)
            }
        }.also { overlay ->
            overlay.loadingBackgroundColor = Color.TRANSPARENT
            overlay.loadingLineColor = Color.TRANSPARENT
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    fun updateLocation(lat: Double, lon: Double, bearing: Float) {
        currentLat = lat
        currentLon = lon
        currentBearing = bearing
        updateBikeMarker(lat, lon, bearing)
        if (followMode) applyFollowMode(lat, lon, bearing)
    }

    fun setFollowMode(enabled: Boolean) {
        if (followMode == enabled) return
        followMode = enabled
        if (enabled && currentLat != 0.0) {
            applyFollowMode(currentLat, currentLon, currentBearing)
        }
        onFollowModeChanged?.invoke(enabled)
    }

    fun toggleNorthUp(): Boolean {
        northUpMode = !northUpMode
        if (followMode && currentLat != 0.0) {
            applyFollowMode(currentLat, currentLon, currentBearing)
        } else if (northUpMode) {
            // Immediately snap north when locking orientation while browsing
            mapView.mapOrientation = 0f
            mapView.invalidate()
        }
        onNorthUpChanged?.invoke(northUpMode)
        return northUpMode
    }

    private fun applyFollowMode(lat: Double, lon: Double, bearing: Float) {
        if (userIsTouching) return  // don't fight the user's finger
        if (northUpMode) {
            mapView.mapOrientation = 0f
            mapView.controller.animateTo(GeoPoint(lat, lon))
        } else {
            applyHeadingUp(lat, lon, bearing)
        }
    }

    fun toggleMapStyle(): Boolean {
        cyclosmActive = !cyclosmActive
        mapView.setTileSource(if (cyclosmActive) cyclosmTileSource else TileSourceFactory.MAPNIK)
        mapView.invalidate()
        return cyclosmActive
    }

    fun toggleTerrain(): Boolean {
        terrainEnabled = !terrainEnabled
        val overlay = terrainOverlay ?: return false
        if (terrainEnabled) {
            if (!mapView.overlays.contains(overlay)) {
                mapView.overlays.add(0, overlay)
            }
        } else {
            mapView.overlays.remove(overlay)
        }
        mapView.invalidate()
        return terrainEnabled
    }

    fun updateWaypointMarkers(
        waypoints: List<GeoPoint>,
        onDelete: ((Int) -> Unit)? = null,
        onDrag: ((Int, GeoPoint) -> Unit)? = null
    ) {
        waypointMarkers.forEach { mapView.overlays.remove(it) }
        waypointMarkers.clear()

        waypoints.forEachIndexed { index, point ->
            val marker = Marker(mapView).apply {
                position = point
                icon = createNumberedPin(index + 1)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                isDraggable = true
                infoWindow = null
                setOnMarkerClickListener { _, _ ->
                    (onDelete ?: onWaypointDeleteRequest)?.invoke(index)
                    true
                }
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(marker: Marker) {}
                    override fun onMarkerDrag(marker: Marker) {}
                    override fun onMarkerDragEnd(marker: Marker) {
                        (onDrag ?: onWaypointDragged)?.invoke(index, marker.position)
                    }
                })
            }
            waypointMarkers.add(marker)
            // Insert below location marker (keep location marker on top)
            val insertIdx = maxOf(0, mapView.overlays.size - 1)
            mapView.overlays.add(insertIdx, marker)
        }
        mapView.invalidate()
    }

    fun showRoute(points: List<GeoPoint>) {
        routePolyline?.let { mapView.overlays.remove(it) }
        if (points.size < 2) return
        val poly = Polyline().apply {
            outlinePaint.color = Color.parseColor("#FF42A5F5")
            outlinePaint.strokeWidth = 8f
            setPoints(points)
        }
        routePolyline = poly
        mapView.overlays.add(0, poly)
        mapView.invalidate()
    }

    fun updateTrackPolyline(points: List<GeoPoint>) {
        if (points.size < 2) return
        val poly = trackPolyline ?: Polyline().apply {
            outlinePaint.color = Color.parseColor("#FFEF5350")
            outlinePaint.strokeWidth = 6f
            val insertIdx = minOf(1, mapView.overlays.size)
            mapView.overlays.add(insertIdx, this)
            trackPolyline = this
        }
        poly.setPoints(points)
        mapView.invalidate()
    }

    fun clearRoute() {
        routePolyline?.let { mapView.overlays.remove(it) }
        routePolyline = null
        waypointMarkers.forEach { mapView.overlays.remove(it) }
        waypointMarkers.clear()
        mapView.invalidate()
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun disengageFollowMode() {
        followMode = false
        // Reset to north so the map is readable while the user browses.
        // Re-engaging follow mode (recenter button) will re-apply the correct orientation.
        mapView.mapOrientation = 0f
        onFollowModeChanged?.invoke(false)
    }

    private fun applyHeadingUp(lat: Double, lon: Double, bearing: Float) {
        mapView.mapOrientation = -bearing
        val center = calcOffsetCenter(lat, lon, bearing)
        mapView.controller.animateTo(center)
    }

    private fun calcOffsetCenter(lat: Double, lon: Double, bearing: Float): GeoPoint {
        // Place user at 70% from top: offset center 20% screen height "behind" user
        val zoom = mapView.zoomLevelDouble
        val metersPerPixel = 156543.03 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)
        val offsetMeters = mapView.height * 0.20 * metersPerPixel
        val backRad = Math.toRadians(((bearing + 180.0) % 360.0))
        val dLat = offsetMeters * cos(backRad) / 111320.0
        val dLon = offsetMeters * sin(backRad) / (111320.0 * cos(Math.toRadians(lat)))
        return GeoPoint(lat + dLat, lon + dLon)
    }

    private fun updateBikeMarker(lat: Double, lon: Double, bearing: Float) {
        val marker = locationMarker ?: Marker(mapView).also { m ->
            m.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_bike_marker)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            m.infoWindow = null
            locationMarker = m
            mapView.overlays.add(m)
        }
        marker.position = GeoPoint(lat, lon)
        marker.rotation = bearing
        mapView.invalidate()
    }

    private fun createNumberedPin(number: Int): BitmapDrawable {
        val density = resources.displayMetrics.density
        val size = (36 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Forest green circle
        paint.color = Color.parseColor("#FF388E3C")
        paint.style = Paint.Style.FILL
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - density, paint)

        // White border
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * 1.5f
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - density, paint)

        // Number text
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        paint.textSize = size * 0.45f
        paint.textAlign = Paint.Align.CENTER
        val metrics = paint.fontMetrics
        val textY = size / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(number.toString(), size / 2f, textY, paint)

        return BitmapDrawable(resources, bitmap)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }
}
