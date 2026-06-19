package com.kc3smw.cyclemania

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var mapFragment: MapFragment
    private lateinit var navigationManager: NavigationManager
    private lateinit var ttsManager: TtsManager
    private lateinit var waypointManager: WaypointManager

    private var recordingService: RideRecordingService? = null
    private var serviceBound = false
    private var lastBearing = 0f
    private val trackGeoPoints = mutableListOf<org.osmdroid.util.GeoPoint>()

    private lateinit var fabColumnRight: LinearLayout
    private lateinit var fabRecord: FloatingActionButton
    private lateinit var fabPause: FloatingActionButton
    private lateinit var fabTts: FloatingActionButton
    private lateinit var fabDownload: FloatingActionButton
    private lateinit var fabTerrain: FloatingActionButton
    private lateinit var fabRecenter: FloatingActionButton
    private lateinit var fabMapStyle: FloatingActionButton
    private lateinit var fabOrientation: FloatingActionButton
    private lateinit var statsBar: View
    private lateinit var tvDistance: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var routeInfoPanel: View
    private lateinit var tvRouteDistance: TextView
    private lateinit var tvRouteTime: TextView

    private val useKm: Boolean
        get() = PreferenceManager.getDefaultSharedPreferences(this).getString("units", "km") == "km"
    private var terrainOn = false
    private var cycleMapOn = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as RideRecordingService.RecordingBinder
            recordingService = binder.getService()
            serviceBound = true
            observeService()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            recordingService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            bindAndStartService()
        } else {
            Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    private val routingDownloadLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) handleRoutingDownloadComplete()
    }

    private val tileDownloadLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (!navigationManager.isInitialized()) lifecycleScope.launch { initAndWarmUp() }
        }
    }

    private var setupCheckDone = false
    private var suppressRouteErrorToast = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mapFragment = MapFragment()
        mapFragment.onLongPressListener = { geoPoint -> onMapLongPress(geoPoint) }
        mapFragment.onWaypointDeleteRequest = { index -> confirmDeleteWaypoint(index) }
        mapFragment.onWaypointDragged = { index, point ->
            waypointManager.updateWaypoint(index, point)
        }
        mapFragment.onFollowModeChanged = { following ->
            fabRecenter.visibility = if (following) View.GONE else View.VISIBLE
        }
        mapFragment.onNorthUpChanged = { northUp ->
            fabOrientation.backgroundTintList = ContextCompat.getColorStateList(
                this, if (northUp) R.color.colorPrimary else R.color.colorAccent
            )
            val label = if (northUp) "North Up" else "Heading Up"
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.map_container, mapFragment)
            .commit()

        navigationManager = NavigationManager(this)
        ttsManager = TtsManager(this)
        waypointManager = WaypointManager()
        waypointManager.onChanged = { onWaypointsChanged() }

        fabColumnRight = findViewById(R.id.fab_column_right)
        fabRecord = findViewById(R.id.fab_record)
        fabPause = findViewById(R.id.fab_pause)
        fabTts = findViewById(R.id.fab_tts)
        fabDownload = findViewById(R.id.fab_download)
        fabTerrain = findViewById(R.id.fab_terrain)
        fabRecenter = findViewById(R.id.fab_recenter)
        fabMapStyle = findViewById(R.id.fab_map_style)
        fabOrientation = findViewById(R.id.fab_orientation)
        statsBar = findViewById(R.id.stats_bar)
        tvDistance = findViewById(R.id.tv_distance)
        tvDuration = findViewById(R.id.tv_duration)
        tvSpeed = findViewById(R.id.tv_speed)
        routeInfoPanel = findViewById(R.id.route_info_panel)
        tvRouteDistance = findViewById(R.id.tv_route_distance)
        tvRouteTime = findViewById(R.id.tv_route_time)

        fabRecord.setOnClickListener { onRecordButtonClick() }
        fabPause.setOnClickListener { onPauseButtonClick() }
        fabTts.setOnClickListener { toggleTts() }
        fabDownload.setOnClickListener { showDownloadMenu() }
        fabTerrain.setOnClickListener { toggleTerrain() }
        fabMapStyle.setOnClickListener { toggleMapStyle() }
        fabOrientation.setOnClickListener { toggleOrientation() }
        fabRecenter.setOnClickListener { mapFragment.setFollowMode(true) }
        findViewById<View>(R.id.btn_clear_route).setOnClickListener { clearRoute() }
        // Default is north-up, so orientation FAB starts with colorPrimary (not colorAccent)
        fabOrientation.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorPrimary)

        createNotificationChannel()
        checkAndRequestPermissions()
        checkSafetyDisclaimer()
    }

    private fun checkSafetyDisclaimer() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("safety_disclaimer_accepted", false)) return
        val pad = (16 * resources.displayMetrics.density).toInt()
        val checkBox = CheckBox(this).apply {
            text = "Don't show this again"
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(this)
            .setTitle("Safety Notice")
            .setMessage(
                "My Cycle Mania is a tool for planning and recording rides.\n\n" +
                "Always review your planned route for safety before riding. " +
                "Road conditions, traffic, shoulders, and hazards may not be " +
                "accurately reflected in map or routing data.\n\n" +
                "Ride safely and obey all traffic laws."
            )
            .setView(checkBox)
            .setPositiveButton("Got It") { _, _ ->
                if (checkBox.isChecked) {
                    prefs.edit().putBoolean("safety_disclaimer_accepted", true).apply()
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                getString(R.string.notif_channel_setup_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun postNotification(id: Int, title: String, text: String) {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(id, notif)
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            bindAndStartService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun bindAndStartService() {
        val intent = Intent(this, RideRecordingService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun observeService() {
        recordingService?.stats?.observe(this) { stats ->
            updateStatsBar(stats)
        }
        recordingService?.location?.observe(this) { loc ->
            val bearing = if (loc.hasBearing()) loc.bearing else lastBearing
            lastBearing = bearing
            if (mapFragment.isAdded) {
                mapFragment.updateLocation(loc.latitude, loc.longitude, bearing)
            }
            if (navigationManager.instructions.isNotEmpty()) {
                navigationManager.checkAndAnnounce(loc.latitude, loc.longitude, ttsManager)
            }
            if (recordingService?.isRecording() == true) {
                trackGeoPoints.add(GeoPoint(loc.latitude, loc.longitude))
                if (mapFragment.isAdded) mapFragment.updateTrackPolyline(trackGeoPoints)
            }
        }
        if (!setupCheckDone) {
            setupCheckDone = true
            restoreWaypoints()
            checkOfflineData()
        }
    }

    private fun onRecordButtonClick() {
        val svc = recordingService ?: return
        if (!svc.isRecording()) {
            svc.startRecording()
            trackGeoPoints.clear()
            fabRecord.setImageResource(android.R.drawable.ic_media_ff)
            fabPause.visibility = View.VISIBLE
            statsBar.visibility = View.VISIBLE
            ttsManager.speak("Ride started")
        } else {
            val finalStats = svc.stopRecording()
            fabRecord.setImageResource(android.R.drawable.ic_media_play)
            fabPause.visibility = View.GONE
            val points = svc.recorder.trackPoints
            val gpxFile = GpxExporter.export(this, points, finalStats.distanceMeters)
            if (gpxFile != null) {
                Toast.makeText(this, getString(R.string.gpx_saved, gpxFile.name), Toast.LENGTH_LONG).show()
            }
            ttsManager.speak("Ride stopped")
            launchRideSummary(finalStats)
        }
    }

    private fun onPauseButtonClick() {
        val svc = recordingService ?: return
        if (svc.recorder.trackPoints.isNotEmpty()) {
            svc.pauseRecording()
            fabPause.setImageResource(android.R.drawable.ic_media_play)
            fabPause.setOnClickListener {
                svc.resumeRecording()
                fabPause.setImageResource(android.R.drawable.ic_media_pause)
                fabPause.setOnClickListener { onPauseButtonClick() }
            }
        }
    }

    private fun toggleTts() {
        ttsManager.isEnabled = !ttsManager.isEnabled
        val msg = if (ttsManager.isEnabled) "Voice on" else "Voice off"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        ttsManager.speak(msg)
    }

    private fun toggleOrientation() {
        if (!mapFragment.isAdded) return
        mapFragment.toggleNorthUp()
    }

    private fun toggleTerrain() {
        if (!mapFragment.isAdded) return
        terrainOn = mapFragment.toggleTerrain()
        fabTerrain.backgroundTintList = ContextCompat.getColorStateList(
            this, if (terrainOn) R.color.colorAccent else R.color.colorPrimary
        )
    }

    private fun toggleMapStyle() {
        if (!mapFragment.isAdded) return
        cycleMapOn = mapFragment.toggleMapStyle()
        fabMapStyle.backgroundTintList = ContextCompat.getColorStateList(
            this, if (cycleMapOn) R.color.colorAccent else R.color.colorPrimary
        )
        val label = if (cycleMapOn) "Cycle Map" else "Standard Map"
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }

    private fun showDownloadMenu() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun checkOfflineData() {
        lifecycleScope.launch {
            val hasPbf = withContext(Dispatchers.IO) { navigationManager.hasPbfFile() }
            val hasTiles = withContext(Dispatchers.IO) {
                getExternalFilesDir("tiles")?.listFiles()?.any { it.isDirectory } == true
            }
            if (hasPbf && hasTiles) {
                if (!navigationManager.isInitialized()) {
                    lifecycleScope.launch { initAndWarmUp() }
                }
                return@launch
            }
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            val explained = prefs.getBoolean("setup_explained", false)
            if (!explained) {
                prefs.edit().putBoolean("setup_explained", true).apply()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.welcome_title)
                    .setMessage(R.string.welcome_message)
                    .setPositiveButton(R.string.welcome_start) { _, _ -> launchSetup(hasPbf) }
                    .setNegativeButton(R.string.setup_later, null)
                    .show()
            } else {
                val missing = buildString {
                    if (!hasPbf) append("• Routing data (Pennsylvania, ~120 MB)\n")
                    if (!hasTiles) append("• Map tiles for Eastern PA (~30 MB)")
                }.trim()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.setup_title)
                    .setMessage("Missing offline data:\n\n$missing\n\nWi-Fi recommended.")
                    .setPositiveButton(R.string.setup_download_now) { _, _ -> launchSetup(hasPbf) }
                    .setNegativeButton(R.string.setup_later, null)
                    .show()
            }
        }
    }

    private fun launchSetup(hasPbf: Boolean) {
        if (!hasPbf) {
            routingDownloadLauncher.launch(
                Intent(this, DownloadRoutingDataActivity::class.java)
                    .putExtra(DownloadRoutingDataActivity.EXTRA_AUTO_START, true)
            )
        } else {
            tileDownloadLauncher.launch(
                Intent(this, DownloadRegionActivity::class.java)
                    .putExtra(DownloadRegionActivity.EXTRA_EASTERN_PA, true)
            )
        }
    }

    private suspend fun initAndWarmUp() {
        postNotification(
            NOTIF_ID_ROUTING,
            getString(R.string.notif_routing_building_title),
            getString(R.string.notif_routing_building_text)
        )
        val ok = navigationManager.initialize()
        if (ok) {
            navigationManager.warmUp()
            postNotification(
                NOTIF_ID_ROUTING,
                getString(R.string.notif_routing_ready_title),
                getString(R.string.notif_routing_ready_text)
            )
            // If the user placed waypoints while the index was building, route them now.
            if (waypointManager.waypoints.isNotEmpty()) recalculateRoute()
        }
    }

    private fun handleRoutingDownloadComplete() {
        lifecycleScope.launch {
            val hasTiles = withContext(Dispatchers.IO) {
                getExternalFilesDir("tiles")?.listFiles()?.any { it.isDirectory } == true
            }
            if (!hasTiles) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.setup_tiles_prompt_title)
                    .setMessage(R.string.setup_tiles_prompt_msg)
                    .setPositiveButton(R.string.setup_download_now) { _, _ ->
                        tileDownloadLauncher.launch(
                            Intent(this@MainActivity, DownloadRegionActivity::class.java)
                                .putExtra(DownloadRegionActivity.EXTRA_EASTERN_PA, true)
                        )
                    }
                    .setNegativeButton(R.string.setup_later) { _, _ ->
                        if (!navigationManager.isInitialized()) lifecycleScope.launch { initAndWarmUp() }
                    }
                    .show()
            } else {
                if (!navigationManager.isInitialized()) lifecycleScope.launch { initAndWarmUp() }
            }
        }
    }

    private fun onMapLongPress(geoPoint: GeoPoint) {
        waypointManager.addWaypoint(geoPoint)
    }

    private fun onWaypointsChanged() {
        saveWaypoints()
        if (!mapFragment.isAdded) return
        mapFragment.updateWaypointMarkers(waypointManager.waypoints)
        recalculateRoute()
    }

    private fun saveWaypoints() {
        val str = waypointManager.waypoints
            .joinToString("|") { "${it.latitude},${it.longitude}" }
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putString("saved_waypoints", str).apply()
    }

    private fun restoreWaypoints() {
        val str = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("saved_waypoints", "") ?: return
        if (str.isBlank()) return
        val points = str.split("|").mapNotNull { part ->
            val c = part.split(",")
            if (c.size == 2) try { GeoPoint(c[0].toDouble(), c[1].toDouble()) }
            catch (_: NumberFormatException) { null } else null
        }
        if (points.isNotEmpty()) {
            suppressRouteErrorToast = true
            waypointManager.loadWaypoints(points)
            onWaypointsChanged()
        }
    }

    private fun recalculateRoute() {
        val points = waypointManager.getOrderedPoints()
        if (points.isEmpty()) {
            mapFragment.clearRoute()
            routeInfoPanel.visibility = View.GONE
            setFabColumnBottomMargin(96)
            return
        }

        val loc = recordingService?.location?.value
        val allPoints = if (loc != null) {
            listOf(GeoPoint(loc.latitude, loc.longitude)) + points
        } else {
            points
        }
        if (allPoints.size < 2) return

        lifecycleScope.launch {
            val showError = !suppressRouteErrorToast
            suppressRouteErrorToast = false
            if (!navigationManager.isInitialized()) {
                if (!navigationManager.hasPbfFile()) {
                    if (showError) Toast.makeText(this@MainActivity, R.string.no_pbf_file, Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (navigationManager.isInitializingNow) {
                    // Already building in background — initAndWarmUp() will call recalculateRoute()
                    // when done. Just let the user know their waypoint is queued.
                    Toast.makeText(this@MainActivity, R.string.routing_loading_wait, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val ok = navigationManager.initialize()
                if (!ok) {
                    if (showError) Toast.makeText(this@MainActivity, R.string.routing_init_failed, Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
            val ok = navigationManager.routeWaypoints(allPoints)
            if (ok) {
                if (mapFragment.isAdded) mapFragment.showRoute(navigationManager.routePoints)
                showRouteInfo(navigationManager.routeDistanceMeters, navigationManager.routeTimeSeconds)
            } else {
                Toast.makeText(this@MainActivity, R.string.route_not_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeleteWaypoint(index: Int) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_waypoint, index + 1))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                waypointManager.removeWaypoint(index)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearRoute() {
        waypointManager.clearAll()
        navigationManager.clearRoute()
        if (mapFragment.isAdded) mapFragment.clearRoute()
        routeInfoPanel.visibility = View.GONE
        setFabColumnBottomMargin(96)
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().remove("saved_waypoints").apply()
    }

    private fun setFabColumnBottomMargin(dp: Int) {
        val px = (dp * resources.displayMetrics.density).toInt()
        val params = fabColumnRight.layoutParams as android.view.ViewGroup.MarginLayoutParams
        params.bottomMargin = px
        fabColumnRight.requestLayout()
    }

    private fun showRouteInfo(distMeters: Double, timeSec: Double) {
        val distStr = if (useKm) String.format("%.1f km", distMeters / 1000.0)
        else String.format("%.1f mi", distMeters / 1609.34)
        val mins = (timeSec / 60).toLong()
        val timeStr = if (mins < 60) "${mins}m" else "${mins / 60}h ${mins % 60}m"
        tvRouteDistance.text = distStr
        tvRouteTime.text = timeStr
        routeInfoPanel.visibility = View.VISIBLE
        setFabColumnBottomMargin(148)
    }

    private fun updateStatsBar(stats: RideStats) {
        val distStr = if (useKm) String.format("%.2f km", stats.distanceMeters / 1000.0)
        else String.format("%.2f mi", stats.distanceMeters / 1609.34)
        tvDistance.text = distStr

        val hours = TimeUnit.MILLISECONDS.toHours(stats.durationMs)
        val mins = TimeUnit.MILLISECONDS.toMinutes(stats.durationMs) % 60
        val secs = TimeUnit.MILLISECONDS.toSeconds(stats.durationMs) % 60
        tvDuration.text = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)

        val speedVal = if (useKm) stats.currentSpeedKph else stats.currentSpeedKph / 1.60934f
        tvSpeed.text = String.format("%.1f", speedVal)
    }

    private fun launchRideSummary(stats: RideStats) {
        val svc = recordingService ?: return
        val intent = Intent(this, RideSummaryActivity::class.java).apply {
            putExtra(RideSummaryActivity.EXTRA_DISTANCE, stats.distanceMeters)
            putExtra(RideSummaryActivity.EXTRA_DURATION_MS, stats.durationMs)
            putExtra(RideSummaryActivity.EXTRA_AVG_SPEED, svc.recorder.avgSpeedKph())
            putExtra(RideSummaryActivity.EXTRA_MAX_SPEED, stats.maxSpeedKph)
            putExtra(RideSummaryActivity.EXTRA_ELEVATION, stats.elevationGainMeters)
            val lats = svc.recorder.trackPoints.map { it.latitude }.toDoubleArray()
            val lons = svc.recorder.trackPoints.map { it.longitude }.toDoubleArray()
            putExtra(RideSummaryActivity.EXTRA_LATS, lats)
            putExtra(RideSummaryActivity.EXTRA_LONS, lons)
        }
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SETTINGS, 0, R.string.settings)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_SETTINGS -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("keep_screen_on", true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (!navigationManager.isInitialized() && navigationManager.hasPbfFile()) {
            lifecycleScope.launch { navigationManager.initialize() }
        }
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        ttsManager.shutdown()
        navigationManager.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val MENU_SETTINGS = 1
        const val NOTIF_CHANNEL_ID = "cyclemania_setup"
        const val NOTIF_ID_ROUTING = 10
        const val NOTIF_ID_TILES = 11
    }
}
