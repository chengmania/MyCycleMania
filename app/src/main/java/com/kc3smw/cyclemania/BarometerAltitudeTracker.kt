package com.kc3smw.cyclemania

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Fuses the device barometer with GPS altitude to produce a smoother altitude
 * reading than raw GPS altitude alone (which is typically noisy, +/-10-15m).
 *
 * The barometer is very sensitive to short-term pressure (and thus altitude)
 * changes but drifts over time as weather pressure shifts, and it only gives
 * a relative reading (needs a reference to convert to a real altitude). GPS
 * altitude is noisy point-to-point but has no long-term drift. A simple
 * complementary filter combines them: use the barometer for smooth short-term
 * changes, and slowly correct its zero-offset toward GPS to cancel drift.
 *
 * If the device has no barometer, [fuse] just passes the GPS altitude through.
 */
class BarometerAltitudeTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val isAvailable: Boolean get() = pressureSensor != null

    private var lastBaroAltitude: Double? = null
    private var offset: Double = 0.0
    private var initialized = false

    fun start() {
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PRESSURE) {
            val pressureHpa = event.values[0]
            lastBaroAltitude = SensorManager.getAltitude(
                SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                pressureHpa
            ).toDouble()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Call once per new GPS fix. Returns a fused altitude estimate in meters.
     */
    fun fuse(gpsAltitude: Double): Double {
        val baro = lastBaroAltitude ?: return gpsAltitude
        if (!initialized) {
            offset = gpsAltitude - baro
            initialized = true
            return gpsAltitude
        }
        // Slowly pull the barometer's zero-offset toward GPS so long-term drift
        // (weather pressure changes over a multi-hour ride) gets corrected,
        // without letting any single noisy GPS fix jerk the fused value around.
        val correctionRate = 0.03
        offset += correctionRate * (gpsAltitude - (baro + offset))
        return baro + offset
    }
}
