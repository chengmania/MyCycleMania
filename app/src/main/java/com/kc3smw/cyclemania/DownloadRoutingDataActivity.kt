package com.kc3smw.cyclemania

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class DownloadRoutingDataActivity : AppCompatActivity() {

    private lateinit var listRegions: ListView
    private lateinit var tvStatus: TextView
    private lateinit var progressDownload: ProgressBar

    data class Region(val name: String, val url: String)

    private val regions = listOf(
        Region("Pennsylvania (US) — Recommended", "https://download.geofabrik.de/north-america/us/pennsylvania-latest.osm.pbf"),
        Region("New York (US)", "https://download.geofabrik.de/north-america/us/new-york-latest.osm.pbf"),
        Region("Maryland (US)", "https://download.geofabrik.de/north-america/us/maryland-latest.osm.pbf"),
        Region("Virginia (US)", "https://download.geofabrik.de/north-america/us/virginia-latest.osm.pbf"),
        Region("Ohio (US)", "https://download.geofabrik.de/north-america/us/ohio-latest.osm.pbf"),
        Region("District of Columbia (US)", "https://download.geofabrik.de/north-america/us/district-of-columbia-latest.osm.pbf"),
        Region("Great Britain (UK)", "https://download.geofabrik.de/europe/great-britain-latest.osm.pbf"),
        Region("Germany", "https://download.geofabrik.de/europe/germany-latest.osm.pbf"),
        Region("France", "https://download.geofabrik.de/europe/france-latest.osm.pbf"),
        Region("Netherlands", "https://download.geofabrik.de/europe/netherlands-latest.osm.pbf")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_routing)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.download_routing)

        listRegions = findViewById(R.id.list_regions)
        tvStatus = findViewById(R.id.tv_status)
        progressDownload = findViewById(R.id.progress_download)

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, regions.map { it.name })
        listRegions.adapter = adapter
        listRegions.setOnItemClickListener { _, _, position, _ ->
            startDownload(regions[position])
        }

        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            startDownload(regions[0])
        }
    }

    private fun startDownload(region: Region) {
        listRegions.isEnabled = false
        tvStatus.visibility = View.VISIBLE
        progressDownload.visibility = View.VISIBLE
        progressDownload.progress = 0

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) { downloadPbf(region) }
            if (success) {
                Toast.makeText(this@DownloadRoutingDataActivity, "Download complete: ${region.name}", Toast.LENGTH_LONG).show()
                tvStatus.text = "Downloaded: ${region.name}"
                deleteGraphCache()
                setResult(Activity.RESULT_OK)
            } else {
                Toast.makeText(this@DownloadRoutingDataActivity, "Download failed", Toast.LENGTH_LONG).show()
                tvStatus.text = "Download failed"
            }
            listRegions.isEnabled = true
            progressDownload.visibility = View.GONE
        }
    }

    private suspend fun downloadPbf(region: Region): Boolean {
        val dir = getExternalFilesDir("routing") ?: return false
        dir.mkdirs()
        val fileName = region.url.substringAfterLast("/")
        val outFile = File(dir, fileName)
        return try {
            val conn = URL(region.url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.connect()
                val total = conn.contentLength.toLong()
                val input: InputStream = conn.inputStream
                val buffer = ByteArray(8192)
                var downloaded = 0L
                outFile.outputStream().use { out ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        out.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            runOnUiThread {
                                progressDownload.progress = pct
                                tvStatus.text = getString(R.string.downloading, pct)
                            }
                        }
                    }
                }
                true
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            outFile.delete()
            throw e
        } catch (e: Exception) {
            outFile.delete()
            false
        }
    }

    private fun deleteGraphCache() {
        val graphDir = File(getExternalFilesDir("routing"), "graph_cache")
        graphDir.deleteRecursively()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
    }
}
