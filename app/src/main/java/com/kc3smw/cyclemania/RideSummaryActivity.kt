package com.kc3smw.cyclemania

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
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
    private lateinit var btnSaveScreenshot: Button
    private lateinit var btnShare: Button

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
        btnSaveScreenshot = findViewById(R.id.btn_save_screenshot)
        btnShare = findViewById(R.id.btn_share)

        val distMeters = intent.getDoubleExtra(EXTRA_DISTANCE, 0.0)
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        val avgSpeed = intent.getFloatExtra(EXTRA_AVG_SPEED, 0f)
        val maxSpeed = intent.getFloatExtra(EXTRA_MAX_SPEED, 0f)
        val elevation = intent.getDoubleExtra(EXTRA_ELEVATION, 0.0)
        val lats = intent.getDoubleArrayExtra(EXTRA_LATS) ?: doubleArrayOf()
        val lons = intent.getDoubleArrayExtra(EXTRA_LONS) ?: doubleArrayOf()

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

        setupMap(lats, lons)

        btnSaveScreenshot.setOnClickListener { saveScreenshot() }
        btnShare.setOnClickListener { shareScreenshot() }
    }

    private fun setupMap(lats: DoubleArray, lons: DoubleArray) {
        summaryMap.setTileSource(TileSourceFactory.MAPNIK)
        summaryMap.setMultiTouchControls(true)
        summaryMap.controller.setZoom(14.0)

        if (lats.size >= 2 && lons.size >= 2) {
            val points = lats.zip(lons.toList()).map { (lat, lon) -> GeoPoint(lat, lon) }
            val polyline = Polyline().apply {
                color = android.graphics.Color.RED
                width = 6f
                setPoints(points)
            }
            summaryMap.overlays.add(polyline)
            val bounds = BoundingBox.fromGeoPoints(points)
            summaryMap.post { summaryMap.zoomToBoundingBox(bounds, true, 64) }
        } else if (lats.isNotEmpty()) {
            summaryMap.controller.setCenter(GeoPoint(lats[0], lons[0]))
        }
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
    }
}
