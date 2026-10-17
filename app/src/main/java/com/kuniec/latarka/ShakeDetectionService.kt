package com.kuniec.latarka

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * A foreground service that listens for accelerometer events to detect the chop gesture.
 *
 * Reliability measures:
 * - Prefers the wake-up accelerometer when the device has one, so events keep arriving while the
 *   CPU would otherwise sleep with the screen off.
 * - Holds a partial wake lock for as long as the service runs (no timeout), because the normal
 *   accelerometer stops reporting once the CPU suspends.
 * - Uses SensorEvent.timestamp for gesture timing, so batched events keep their real spacing.
 */
class ShakeDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var flashlightController: FlashlightController
    private lateinit var shakeDetector: ShakeDetector
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(), fgsType)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        Log.i(TAG, "Using accelerometer: ${accelerometer?.name} (wake-up=${accelerometer?.isWakeUpSensor})")

        flashlightController = FlashlightController(this)
        shakeDetector = ShakeDetector(FlashlightConfig.load(this))

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Latarka::ShakeDetection")
        wakeLock.setReferenceCounted(false)
        @Suppress("WakelockTimeout")
        wakeLock.acquire()

        // maxReportLatencyUs = 0: deliver events immediately instead of batching them.
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME, 0)
    }

    /**
     * Re-loads the configuration when the service is (re)started.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        shakeDetector.updateConfig(FlashlightConfig.load(this))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val shouldToggle = shakeDetector.onSensorChanged(
                event.values[0],
                event.values[1],
                event.values[2],
                event.timestamp / 1_000_000L // nanoseconds -> milliseconds
            )
            if (shouldToggle) {
                flashlightController.toggleFlashlight()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        flashlightController.release()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val TAG = "ShakeDetectionService"
        const val CHANNEL_ID = "shake_service"
        const val NOTIFICATION_ID = 1
    }
}
