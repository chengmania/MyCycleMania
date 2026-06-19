package com.kc3smw.cyclemania

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.*

class RideRecordingService : Service() {

    inner class RecordingBinder : Binder() {
        fun getService() = this@RideRecordingService
    }

    private val binder = RecordingBinder()
    val recorder = RideRecorder()

    private val _stats = MutableLiveData<RideStats>()
    val stats: LiveData<RideStats> = _stats

    private val _location = MutableLiveData<android.location.Location>()
    val location: LiveData<android.location.Location> = _location

    private lateinit var fusedClient: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRecording = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                _location.postValue(loc)
                if (isRecording) {
                    recorder.addPoint(loc.latitude, loc.longitude, loc.altitude, loc.speed)
                    _stats.postValue(recorder.currentStats())
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(getString(R.string.gps_notification_text)))
        startLocationUpdates()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        fusedClient.removeLocationUpdates(locationCallback)
        wakeLock?.release()
        super.onDestroy()
    }

    fun startRecording() {
        recorder.start()
        isRecording = true
        acquireWakeLock()
        updateNotification("Recording ride…")
    }

    fun pauseRecording() {
        recorder.pause()
        updateNotification("Ride paused")
    }

    fun resumeRecording() {
        recorder.resume()
        updateNotification("Recording ride…")
    }

    fun stopRecording(): RideStats {
        isRecording = false
        recorder.pause()
        val stats = recorder.currentStats()
        wakeLock?.release()
        wakeLock = null
        updateNotification(getString(R.string.gps_notification_text))
        return stats
    }

    fun isRecording() = isRecording

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyCycleMania:RecordingLock")
        wakeLock?.acquire()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ride_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String = getString(R.string.recording_notification_text)): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val CHANNEL_ID = "ride_recording"
        const val NOTIF_ID = 1001
    }
}
