package com.kc3smw.cyclemania

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class RideSummaryActivity : AppCompatActivity() {

    private lateinit var summaryMap: MapView
    private lateinit var tvTotalDistance: TextView
    private lateinit var tvTotalDuration: TextView
    private lateinit var tvAvgSpeed: TextView
    private lateinit var tvMaxSpeed: TextView
    private lateinit var tvElevation: TextView
    private lateinit var tvHillRating: TextView
    private lateinit var tvHillAscent: TextView
    private lateinit var tvHillDescent: TextView
    private lateinit var elevationChart: ElevationChartView
    private lateinit var overlayCard: LinearLayout
    private lateinit var bottomButtonBar: LinearLayout
    private lateinit var btnSaveScreenshot: Button
    private lateinit var btnShare: Button
    private lateinit var btnConvertRoute: Button

    private var currentLats: DoubleArray = doubleArrayOf()
    private var currentLons: DoubleArray = doubleArrayOf()
    private var currentTrackPoints: List<TrackPoint> = emptyList()
    private var currentDistanceMeters: Double = 0.0
    private var currentDurationMs: Long = 0L
    private var topBarInset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ride_summary)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        Configuration.getInstance().userAgentValue = packageName

        summaryMap = findViewById(R.id.summary_map)
        tvTotalDistance = findViewById(R.id.tv_total_distance)
        tvTotalDuration = findViewById(R.id.tv_total_duration)
        tvAvgSpeed = findViewById(R.id.tv_avg_speed)
        tvMaxSpeed = findViewById(R.id.tv_max_speed)
        tvElevation = findViewById(R.id.tv_elevation)
        tvHillRating = findViewById(R.id.tv_hill_rating)
        tvHillAscent = findViewById(R.id.tv_hill_ascent)
        tvHillDescent = findViewById(R.id.tv_hill_descent)
        elevationChart = findViewById(R.id.elevation_chart)
        overlayCard = findViewById(R.id.overlay_card)
        bottomButtonBar = findViewById(R.id.bottom_button_bar)
        btnSaveScreenshot = findViewById(R.id.btn_save_screenshot)
        btnShare = findViewById(R.id.btn_share)
        btnConvertRoute = findViewById(R.id.btn_convert_route)

        setupInsets()

        val rideId = intent.getLongExtra(EXTRA_RIDE_ID, -1L)
        if (rideId != -1L) {
            // Disable actions that depend on ride data until the async load below finishes,
            // so a tap in that window can't silently no-op or capture an unpopulated map.
            btnSaveScreenshot.isEnabled = false
            btnShare.isEnabled = false
            btnConvertRoute.isEnabled = false
            lifecycleScope.launch {
                val ride = withContext(Dispatchers.IO) { RideHistoryStore.loadRide(this@RideSummaryActivity, rideId) }
                if (ride == null) {
                    Toast.makeText(this@RideSummaryActivity, R.string.ride_not_found, Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                currentLats = ride.trackPoints.map { it.latitude }.toDoubleArray()
                currentLons = ride.trackPoints.map { it.longitude }.toDoubleArray()
                currentTrackPoints = ride.trackPoints
                currentDistanceMeters = ride.distanceMeters
                currentDurationMs = ride.durationMs
                bindStats(ride.distanceMeters, ride.durationMs, ride.avgSpeedKph, ride.maxSpeedKph, ride.elevationGainMeters)
                setupMap(currentTrackPoints)
                setupHillSummary(currentTrackPoints, ride.distanceMeters)
                btnSaveScreenshot.isEnabled = true
                btnShare.isEnabled = true
                btnConvertRoute.isEnabled = true
            }
        } else {
            val distMeters = intent.getDoubleExtra(EXTRA_DISTANCE, 0.0)
            val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
            val avgSpeed = intent.getFloatExtra(EXTRA_AVG_SPEED, 0f)
            val maxSpeed = intent.getFloatExtra(EXTRA_MAX_SPEED, 0f)
            val elevation = intent.getDoubleExtra(EXTRA_ELEVATION, 0.0)
            currentLats = intent.getDoubleArrayExtra(EXTRA_LATS) ?: doubleArrayOf()
            currentLons = intent.getDoubleArrayExtra(EXTRA_LONS) ?: doubleArrayOf()
            val alts = intent.getDoubleArrayExtra(EXTRA_ALTS) ?: DoubleArray(currentLats.size)
            currentTrackPoints = currentLats.indices.map { i ->
                TrackPoint(currentLats[i], currentLons[i], alts.getOrElse(i) { 0.0 }, i.toLong(), 0f)
            }
            currentDistanceMeters = distMeters
            currentDurationMs = durationMs

            bindStats(distMeters, durationMs, avgSpeed, maxSpeed, elevation)
            setupMap(currentTrackPoints)
            setupHillSummary(currentTrackPoints, distMeters)
        }

        btnSaveScreenshot.setOnClickListener { saveScreenshot() }
        btnShare.setOnClickListener { shareScreenshot() }
        btnConvertRoute.setOnClickListener { promptConvertToRoute() }
    }

    private fun promptConvertToRoute() {
        if (currentLats.size < 2) return
        val pad = (16 * resources.displayMetrics.density).toInt()
        val editText = EditText(this).apply {
            hint = getString(R.string.route_name_hint)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.save_route_title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val routePoints = currentLats.indices.map { GeoPoint(currentLats[it], currentLons[it]) }
                val waypoints = decimate(routePoints, MAX_ROUTE_WAYPOINTS)
                val distance = currentDistanceMeters
                val timeSec = currentDurationMs / 1000.0
                lifecycleScope.launch {
                    val id = withContext(Dispatchers.IO) {
                        SavedRouteStore.saveRoute(this@RideSummaryActivity, name, waypoints, routePoints, distance, timeSec)
                    }
                    if (id != null) {
                        Toast.makeText(this@RideSummaryActivity, getString(R.string.route_saved, name), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@RideSummaryActivity, R.string.route_save_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun decimate(points: List<GeoPoint>, maxCount: Int): List<GeoPoint> {
        if (points.size <= maxCount) return points
        val step = (points.size - 1).toDouble() / (maxCount - 1)
        return (0 until maxCount).map { i -> points[(i * step).toInt().coerceAtMost(points.size - 1)] }
    }

    private fun bindStats(
        distMeters: Double,
        durationMs: Long,
        avgSpeed: Float,
        maxSpeed: Float,
        elevation: Double
    ) {
        val useKm = PreferenceManager.getDefaultSharedPreferences(this).getString("units", "km") == "km"
        tvTotalDistance.text = if (useKm) String.format("%.2f km", distMeters / 1000.0)
        else String.format("%.2f mi", distMeters / 1609.34)

        val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
        val mins = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
        val secs = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
        tvTotalDuration.text = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)

        val speedUnit = if (useKm) "km/h" else "mph"
        val avgSpeedVal = if (useKm) avgSpeed else avgSpeed / 1.60934f
        val maxSpeedVal = if (useKm) maxSpeed else maxSpeed / 1.60934f
        tvAvgSpeed.text = String.format("%.1f %s", avgSpeedVal, speedUnit)
        tvMaxSpeed.text = String.format("%.1f %s", maxSpeedVal, speedUnit)
        val elevationStr = if (useKm) String.format("%.0f m", elevation)
        else String.format("%.0f ft", elevation * 3.28084)
        tvElevation.text = elevationStr
    }

    private fun setupMap(points: List<TrackPoint>) {
        summaryMap.setTileSource(TileSourceFactory.MAPNIK)
        summaryMap.setMultiTouchControls(true)
        summaryMap.controller.setZoom(14.0)

        if (points.size >= 2) {
            val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
            val profile = ElevationProfile.build(points)
            val bounds = BoundingBox.fromGeoPoints(geoPoints)
            summaryMap.post {
                summaryMap.zoomToBoundingBox(bounds, true, 64)
                // Wait for the animated zoom/pan to actually settle before drawing the
                // route (the out-and-back offset needs the map's final on-screen scale
                // to look right - see metersForScreenOffset) or reading back pixel
                // positions for overlay placement.
                summaryMap.postDelayed({
                    drawRoute(points, geoPoints, profile)
                    positionOverlayCard(geoPoints)
                }, 400)
            }
        } else if (points.isNotEmpty()) {
            summaryMap.controller.setCenter(GeoPoint(points[0].latitude, points[0].longitude))
        }
    }

    private fun drawRoute(points: List<TrackPoint>, geoPoints: List<GeoPoint>, profile: ElevationProfile.Profile?) {
        val density = resources.displayMetrics.density
        if (profile != null) {
            for (segment in RouteLineStyler.build(points, profile)) {
                if (segment.points.size < 2) continue
                val polyline = Polyline().apply {
                    outlinePaint.color = colorFor(segment.category)
                    outlinePaint.strokeWidth = 6f * density
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    if (segment.dashLengthPx > 0f) {
                        val dashPx = segment.dashLengthPx * density
                        outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dashPx, dashPx), 0f)
                    }
                    setPoints(segment.points)
                }
                summaryMap.overlays.add(polyline)
            }
        } else {
            val polyline = Polyline().apply {
                outlinePaint.color = colorFor(ElevationProfile.GradeCategory.FLAT)
                outlinePaint.strokeWidth = 6f * density
                setPoints(geoPoints)
            }
            summaryMap.overlays.add(polyline)
        }
        summaryMap.invalidate()
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val baseMarginPx = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBarInset = bars.top
            bottomButtonBar.updatePadding(bottom = bars.bottom)
            // Keep the card clear of the status bar/cutout even before the route has
            // loaded and positionOverlayCard() has had a chance to pick a final corner.
            val lp = overlayCard.layoutParams as FrameLayout.LayoutParams
            if (lp.gravity and Gravity.TOP != 0) {
                lp.topMargin = baseMarginPx + topBarInset
                overlayCard.layoutParams = lp
            }
            insets
        }
    }

    /**
     * Places the overlay card in whichever corner of the map has the fewest
     * ride-track points nearby, so the card doesn't sit on top of the route.
     */
    private fun positionOverlayCard(geoPoints: List<GeoPoint>) {
        val mapWidth = summaryMap.width
        val mapHeight = summaryMap.height
        if (mapWidth == 0 || mapHeight == 0 || !summaryMap.isAttachedToWindow) return

        val projection = summaryMap.projection
        val trackPixels = geoPoints.map { projection.toPixels(it, null) }

        val density = resources.displayMetrics.density
        val margin = (12 * density).toInt()
        val cardWidth = overlayCard.width.takeIf { it > 0 } ?: (190 * density).toInt()
        val cardHeight = overlayCard.height.takeIf { it > 0 } ?: (230 * density).toInt()
        val buttonBarHeight = bottomButtonBar.height

        data class Corner(val gravity: Int, val rect: Rect)
        val candidates = listOf(
            Corner(
                Gravity.TOP or Gravity.START,
                Rect(0, topBarInset, cardWidth + margin * 2, topBarInset + cardHeight + margin * 2)
            ),
            Corner(
                Gravity.TOP or Gravity.END,
                Rect(mapWidth - cardWidth - margin * 2, topBarInset, mapWidth, topBarInset + cardHeight + margin * 2)
            ),
            Corner(
                Gravity.BOTTOM or Gravity.START,
                Rect(
                    0, mapHeight - buttonBarHeight - cardHeight - margin * 2,
                    cardWidth + margin * 2, mapHeight - buttonBarHeight
                )
            ),
            Corner(
                Gravity.BOTTOM or Gravity.END,
                Rect(
                    mapWidth - cardWidth - margin * 2, mapHeight - buttonBarHeight - cardHeight - margin * 2,
                    mapWidth, mapHeight - buttonBarHeight
                )
            )
        )

        val best = candidates.minByOrNull { corner ->
            trackPixels.count { corner.rect.contains(it.x, it.y) }
        } ?: return

        val baseMarginPx = (12 * density).toInt()
        overlayCard.updateLayoutParams<FrameLayout.LayoutParams> {
            gravity = best.gravity
            // The XML margin doesn't account for the status bar/cutout, which the
            // corner-scoring above already reserved space for - apply it here too
            // so a top-anchored card doesn't render under the status bar icons.
            topMargin = if (best.gravity and Gravity.TOP != 0) baseMarginPx + topBarInset else baseMarginPx
        }
    }

    private fun colorFor(category: ElevationProfile.GradeCategory): Int = when (category) {
        ElevationProfile.GradeCategory.FLAT -> android.graphics.Color.parseColor("#FF4CAF50")
        ElevationProfile.GradeCategory.UPHILL -> android.graphics.Color.parseColor("#FFEF5350")
        ElevationProfile.GradeCategory.DOWNHILL -> android.graphics.Color.parseColor("#FF42A5F5")
    }

    private fun isUsingKm(): Boolean =
        PreferenceManager.getDefaultSharedPreferences(this).getString("units", "km") == "km"

    private fun setupHillSummary(points: List<TrackPoint>, distanceMeters: Double) {
        val useKm = isUsingKm()
        val profile = ElevationProfile.build(points)
        elevationChart.setProfile(profile, useKm)
        if (profile == null) {
            tvHillRating.text = "⛰ " + getString(R.string.hills_flat)
            tvHillAscent.text = "↑ 0"
            tvHillDescent.text = "↓ 0"
            return
        }
        val rating = ElevationProfile.classifyHillRating(profile.totalAscentMeters, distanceMeters)
        tvHillRating.text = "⛰ " + getString(rating.labelResId)
        tvHillAscent.text = if (useKm) String.format(Locale.US, "↑ %.0f m", profile.totalAscentMeters)
        else String.format(Locale.US, "↑ %.0f ft", profile.totalAscentMeters * 3.28084)
        tvHillDescent.text = if (useKm) String.format(Locale.US, "↓ %.0f m", profile.totalDescentMeters)
        else String.format(Locale.US, "↓ %.0f ft", profile.totalDescentMeters * 3.28084)
    }

    private fun captureScreenshot(): Bitmap {
        val rootView = findViewById<View>(R.id.root_layout)
        val bmp = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        rootView.draw(canvas)
        return bmp
    }

    private fun saveScreenshot() {
        val bmp = captureScreenshot()
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "MyCycleMania_$dateStr.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MyCycleMania")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            val out: OutputStream? = contentResolver.openOutputStream(uri)
            out?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            Toast.makeText(this, R.string.screenshot_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareScreenshot() {
        val bmp = captureScreenshot()
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "MyCycleMania_$dateStr.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MyCycleMania")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            val out: OutputStream? = contentResolver.openOutputStream(uri)
            out?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        summaryMap.onResume()
    }

    override fun onPause() {
        super.onPause()
        summaryMap.onPause()
    }

    companion object {
        const val EXTRA_DISTANCE = "extra_distance"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val EXTRA_AVG_SPEED = "extra_avg_speed"
        const val EXTRA_MAX_SPEED = "extra_max_speed"
        const val EXTRA_ELEVATION = "extra_elevation"
        const val EXTRA_LATS = "extra_lats"
        const val EXTRA_LONS = "extra_lons"
        const val EXTRA_ALTS = "extra_alts"
        const val EXTRA_RIDE_ID = "extra_ride_id"
        private const val MAX_ROUTE_WAYPOINTS = 15
    }
}
