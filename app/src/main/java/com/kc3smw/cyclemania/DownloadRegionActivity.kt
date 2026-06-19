package com.kc3smw.cyclemania

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

class DownloadRegionActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var seekbarMinZoom: SeekBar
    private lateinit var seekbarMaxZoom: SeekBar
    private lateinit var tvMinZoom: TextView
    private lateinit var tvMaxZoom: TextView
    private lateinit var tvEstimate: TextView
    private lateinit var tvEta: TextView
    private lateinit var progressDownload: ProgressBar
    private lateinit var btnDownload: Button
    private lateinit var btnEasternPaPreset: Button
    private lateinit var rgTileSource: RadioGroup

    private var selectionOverlay: Polygon? = null
    private val downloadTotalBytes = AtomicLong(0)
    private var wakeLock: PowerManager.WakeLock? = null

    private val cyclosmTileSource = XYTileSource(
        "CyclOSM", 0, 19, 256, ".png",
        arrayOf(
            "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/",
            "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/",
            "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_region)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.download_region)

        Configuration.getInstance().userAgentValue = packageName
        val offlineTileDir = getExternalFilesDir("tiles")
        if (offlineTileDir != null) {
            Configuration.getInstance().osmdroidTileCache = offlineTileDir
        }

        mapView = findViewById(R.id.map_view)
        seekbarMinZoom = findViewById(R.id.seekbar_min_zoom)
        seekbarMaxZoom = findViewById(R.id.seekbar_max_zoom)
        tvMinZoom = findViewById(R.id.tv_min_zoom)
        tvMaxZoom = findViewById(R.id.tv_max_zoom)
        tvEstimate = findViewById(R.id.tv_estimate)
        tvEta = findViewById(R.id.tv_eta)
        progressDownload = findViewById(R.id.progress_download)
        btnDownload = findViewById(R.id.btn_download)
        btnEasternPaPreset = findViewById(R.id.btn_eastern_pa_preset)
        rgTileSource = findViewById(R.id.rg_tile_source)

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(12.0)

        setupZoomBars()
        drawSelectionRect()
        updateEstimate()

        mapView.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent): Boolean {
                drawSelectionRect(); updateEstimate(); return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent): Boolean {
                drawSelectionRect(); updateEstimate(); return false
            }
        })

        btnDownload.setOnClickListener { startDownload() }
        btnEasternPaPreset.setOnClickListener { snapToEasternPa() }

        if (intent.getBooleanExtra(EXTRA_EASTERN_PA, false)) {
            mapView.post {
                snapToEasternPa()
                startDownload()
            }
        }
    }

    private fun snapToEasternPa() {
        mapView.controller.setZoom(EPA_ZOOM)
        mapView.controller.setCenter(GeoPoint(EPA_LAT, EPA_LON))
        drawSelectionRect()
        updateEstimate()
    }

    private fun setupZoomBars() {
        val zoomOffset = 10
        fun updateLabels() {
            val min = seekbarMinZoom.progress + zoomOffset
            val max = seekbarMaxZoom.progress + zoomOffset
            tvMinZoom.text = "$min"
            tvMaxZoom.text = "$max"
            updateEstimate()
        }
        seekbarMinZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) { updateLabels() }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        seekbarMaxZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) { updateLabels() }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        updateLabels()
    }

    private fun getSelectionBounds(): BoundingBox {
        val center = mapView.mapCenter as GeoPoint
        val zoom = mapView.zoomLevelDouble
        val span = 180.0 / Math.pow(2.0, zoom) * 2
        return BoundingBox(
            center.latitude + span * 0.5,
            center.longitude + span * 0.5,
            center.latitude - span * 0.5,
            center.longitude - span * 0.5
        )
    }

    private fun drawSelectionRect() {
        selectionOverlay?.let { mapView.overlays.remove(it) }
        val box = getSelectionBounds()
        val polygon = Polygon().apply {
            fillColor = Color.argb(40, 0, 120, 255)
            strokeColor = Color.argb(200, 0, 120, 255)
            strokeWidth = 3f
            points = listOf(
                GeoPoint(box.latNorth, box.lonWest),
                GeoPoint(box.latNorth, box.lonEast),
                GeoPoint(box.latSouth, box.lonEast),
                GeoPoint(box.latSouth, box.lonWest),
                GeoPoint(box.latNorth, box.lonWest)
            )
        }
        selectionOverlay = polygon
        mapView.overlays.add(polygon)
        mapView.invalidate()
    }

    private fun estimateTileCount(box: BoundingBox, minZoom: Int, maxZoom: Int): Int {
        var count = 0
        for (z in minZoom..maxZoom) {
            val n = Math.pow(2.0, z.toDouble())
            val x1 = ((box.lonWest + 180.0) / 360.0 * n).toInt()
            val x2 = ((box.lonEast + 180.0) / 360.0 * n).toInt()
            val latRad1 = Math.toRadians(box.latNorth)
            val latRad2 = Math.toRadians(box.latSouth)
            val y1 = ((1.0 - Math.log(Math.tan(latRad1) + 1.0 / Math.cos(latRad1)) / Math.PI) / 2.0 * n).toInt()
            val y2 = ((1.0 - Math.log(Math.tan(latRad2) + 1.0 / Math.cos(latRad2)) / Math.PI) / 2.0 * n).toInt()
            count += (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1)
        }
        return count
    }

    private fun updateEstimate() {
        val box = getSelectionBounds()
        val minZoom = seekbarMinZoom.progress + 10
        val maxZoom = seekbarMaxZoom.progress + 10
        val count = estimateTileCount(box, minZoom, maxZoom)
        val mbEstimate = count.toLong() * 15 / 1024
        tvEstimate.text = getString(R.string.estimated_tiles, count, mbEstimate)
    }

    private fun startDownload() {
        val box = getSelectionBounds()
        val minZoom = seekbarMinZoom.progress + 10
        val maxZoom = seekbarMaxZoom.progress + 10
        progressDownload.visibility = View.VISIBLE
        tvEta.visibility = View.VISIBLE
        progressDownload.progress = 0
        btnDownload.isEnabled = false
        btnEasternPaPreset.isEnabled = false

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyCycleMania:TileDownload")
        wakeLock?.acquire(90 * 60 * 1000L) // 90 min max

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    downloadTiles(box, minZoom, maxZoom)
                }
            } finally {
                wakeLock?.release()
                wakeLock = null
            }
            progressDownload.progress = 100
            tvEta.text = ""
            tvEta.visibility = View.GONE
            postTilesNotification()
            setResult(Activity.RESULT_OK)
            btnDownload.isEnabled = true
            btnEasternPaPreset.isEnabled = true
        }
    }

    private suspend fun downloadTiles(box: BoundingBox, minZoom: Int, maxZoom: Int) {
        Configuration.getInstance().osmdroidTileCache?.mkdirs()
        downloadTotalBytes.set(0)
        val startMs = System.currentTimeMillis()

        val tiles = mutableListOf<Triple<Int, Int, Int>>()
        for (z in minZoom..maxZoom) {
            val n = Math.pow(2.0, z.toDouble()).toInt()
            val x1 = ((box.lonWest + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val x2 = ((box.lonEast + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val latRad1 = Math.toRadians(box.latNorth)
            val latRad2 = Math.toRadians(box.latSouth)
            val y1 = ((1.0 - Math.log(Math.tan(latRad1) + 1.0 / Math.cos(latRad1)) / Math.PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
            val y2 = ((1.0 - Math.log(Math.tan(latRad2) + 1.0 / Math.cos(latRad2)) / Math.PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
            for (x in x1..x2) for (y in y1..y2) tiles += Triple(z, x, y)
        }

        val sources = listOf("Mapnik", "CyclOSM")
        // Each tile is downloaded for both sources; total work = tiles × 2
        val total = (tiles.size * sources.size).coerceAtLeast(1)
        val done = AtomicInteger(0)
        val semaphore = Semaphore(24)

        coroutineScope {
            for ((z, x, y) in tiles) {
                for (source in sources) {
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            val bytes = downloadTile(source, z, x, y)
                            downloadTotalBytes.addAndGet(bytes)
                        }
                        val completedCount = done.incrementAndGet()
                        val pct = completedCount * 100 / total
                        val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1)
                        val speedBps = downloadTotalBytes.get() * 1000L / elapsedMs
                        val etaSec = if (completedCount > 0)
                            elapsedMs / 1000L * (total - completedCount).toLong() / completedCount
                        else 0L
                        withContext(Dispatchers.Main) {
                            progressDownload.progress = pct
                            tvEta.text = "$completedCount / $total tiles  •  ${formatSpeed(speedBps)}  •  ${formatEta(etaSec)}"
                        }
                    }
                }
            }
        }
    }

    private fun tileUrl(source: String, z: Int, x: Int, y: Int): String {
        val sub = arrayOf("a", "b", "c")[(x + y) % 3]
        return when (source) {
            "CyclOSM" -> "https://$sub.tile-cyclosm.openstreetmap.fr/cyclosm/$z/$x/$y.png"
            else -> "https://$sub.tile.openstreetmap.org/$z/$x/$y.png"
        }
    }

    private fun downloadTile(source: String, z: Int, x: Int, y: Int): Long {
        val tileDir = java.io.File(
            Configuration.getInstance().osmdroidTileCache,
            "$source/$z/$x"
        )
        val tileFile = java.io.File(tileDir, "$y.tile")
        if (tileFile.exists()) return 0L
        tileDir.mkdirs()
        val url = tileUrl(source, z, x, y)
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", packageName)
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.connect()
            if (conn.responseCode == 200) {
                val data = BufferedInputStream(conn.inputStream).readBytes()
                tileFile.writeBytes(data)
                conn.disconnect()
                return data.size.toLong()
            }
            conn.disconnect()
        } catch (_: Exception) {}
        return 0L
    }

    private fun formatSpeed(bps: Long): String = when {
        bps > 1_000_000L -> String.format("%.1f MB/s", bps / 1_000_000.0)
        bps > 1_000L -> String.format("%.0f KB/s", bps / 1_000.0)
        else -> "$bps B/s"
    }

    private fun formatEta(seconds: Long): String = when {
        seconds <= 0 -> "almost done"
        seconds > 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m left"
        seconds > 60 -> "${seconds / 60}m ${seconds % 60}s left"
        else -> "~${seconds}s left"
    }

    private fun postTilesNotification() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, MainActivity.NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notif_tiles_done_title))
            .setContentText(getString(R.string.notif_tiles_done_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(MainActivity.NOTIF_ID_TILES, notif)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }

    companion object {
        const val EXTRA_EASTERN_PA = "eastern_pa"
        private const val EPA_LAT = 40.35
        private const val EPA_LON = -75.45
        private const val EPA_ZOOM = 8.0
    }
}
