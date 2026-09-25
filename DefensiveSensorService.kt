package com.example.defensivesensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat

class DefensiveSensorService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var targetSensor: Sensor? = null

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private var minHardwareDelayUs: Int = 0
    private var safeTargetDelayUs: Int = 0
    private var safeMinIntervalNs: Long = 0L

    private var lastEventTimestampNs: Long = 0L
    private var lastLogTimestampNs: Long = 0L

    // Reusable StringBuilder to reduce heap allocation overhead
    private val logBuilder = StringBuilder(128)

    companion object {
        const val EXTRA_SENSOR_TYPE = "extra_sensor_type"
        const val EXTRA_POLLING_RATE_US = "extra_polling_rate_us"

        private const val PREFS_NAME = "sensor_service_prefs"
        private const val KEY_LAST_SENSOR_TYPE = "last_sensor_type"
        private const val KEY_LAST_RATE_US = "last_rate_us"

        private const val CHANNEL_ID = "sensor_service_channel"
        private const val NOTIF_ID = 1001

        private const val SAFETY_MARGIN_FACTOR = 1.1111 // 10% safety margin back-off
        private const val LOG_THROTTLE_NS = 33_333_333L // Throttles Logcat output to ~30 Hz to avoid logd drops
    }

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()

        sensorThread = HandlerThread("SensorBgThread", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
            start()
            sensorHandler = Handler(looper)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val sensorType: Int
        val requestedRateUs: Int

        if (intent != null) {
            sensorType = intent.getIntExtra(EXTRA_SENSOR_TYPE, Sensor.TYPE_ACCELEROMETER)
            requestedRateUs = intent.getIntExtra(EXTRA_POLLING_RATE_US, 1000)

            prefs.edit()
                .putInt(KEY_LAST_SENSOR_TYPE, sensorType)
                .putInt(KEY_LAST_RATE_US, requestedRateUs)
                .apply()
        } else {
            // Restore both last sensor type and last requested polling rate on START_STICKY recovery
            sensorType = prefs.getInt(KEY_LAST_SENSOR_TYPE, Sensor.TYPE_ACCELEROMETER)
            requestedRateUs = prefs.getInt(KEY_LAST_RATE_US, 1000)
        }

        setupMaxSafeSensorStream(sensorType, requestedRateUs)
        return START_STICKY
    }

    private fun setupMaxSafeSensorStream(sensorType: Int, userRequestedRateUs: Int) {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (sensorManager == null) {
                stopSelf()
                return
            }

            targetSensor = sensorManager?.getDefaultSensor(sensorType)
            if (targetSensor == null) {
                stopSelf()
                return
            }

            minHardwareDelayUs = targetSensor?.minDelay ?: 0

            // Compute target rate with 10% safety headroom over hardware floor
            val absoluteMinSafeUs = if (minHardwareDelayUs > 0) {
                (minHardwareDelayUs * SAFETY_MARGIN_FACTOR).toInt()
            } else {
                0
            }

            // Diagnostic log warning if requested rate gets clamped by the 10% safety margin floor
            if (userRequestedRateUs in 1 until absoluteMinSafeUs) {
                Log.w(
                    "DefensiveSensor",
                    "Requested rate (${userRequestedRateUs}µs) is below the minimum safe hardware threshold (${absoluteMinSafeUs}µs). Clamping to safe rate."
                )
            }

            // Apply user rate if set higher than 0, otherwise clamp to safe floor
            safeTargetDelayUs = if (userRequestedRateUs > 0) {
                userRequestedRateUs.coerceAtLeast(absoluteMinSafeUs)
            } else {
                absoluteMinSafeUs
            }

            safeMinIntervalNs = safeTargetDelayUs.toLong() * 1000L

            sensorManager?.unregisterListener(this)

            val registered = sensorManager?.registerListener(
                this,
                targetSensor,
                safeTargetDelayUs,
                0,
                sensorHandler
            ) ?: false

            if (!registered) {
                Log.w("DefensiveSensor", "Primary listener registration failed. Falling back to SENSOR_DELAY_FASTEST.")
                
                // Synchronize state variables with fallback rate to avoid software throttling mismatch
                safeTargetDelayUs = SensorManager.SENSOR_DELAY_FASTEST
                safeMinIntervalNs = 0L

                sensorManager?.registerListener(
                    this, 
                    targetSensor, 
                    SensorManager.SENSOR_DELAY_FASTEST, 
                    sensorHandler
                )
            }
        } catch (e: SecurityException) {
            stopSelf()
        } catch (t: Throwable) {
            stopSelf()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != targetSensor?.type) return

        val currentNs = event.timestamp

        if (safeMinIntervalNs > 0L && (currentNs - lastEventTimestampNs < safeMinIntervalNs) && lastEventTimestampNs != 0L) {
            return
        }
        lastEventTimestampNs = currentNs

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        printToTerminal(currentNs, x, y, z)
    }

    private fun printToTerminal(timestampNs: Long, x: Float, y: Float, z: Float) {
        if (timestampNs - lastLogTimestampNs >= LOG_THROTTLE_NS) {
            lastLogTimestampNs = timestampNs

            logBuilder.setLength(0)
            logBuilder.append("TS: ").append(timestampNs)
                .append(" | X: ").append(x)
                .append(" | Y: ").append(y)
                .append(" | Z: ").append(z)

            Log.v("DefensiveSensor", logBuilder.toString())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun promoteToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sensor Data Streamer",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Defensive Sensor Stream Active")
            .setContentText("Sampling sensor on background thread")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Throwable) {}

        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}package com.example.defensivesensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat

class DefensiveSensorService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var targetSensor: Sensor? = null

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private var minHardwareDelayUs: Int = 0
    private var safeTargetDelayUs: Int = 0
    private var safeMinIntervalNs: Long = 0L

    private var lastEventTimestampNs: Long = 0L
    private var lastLogTimestampNs: Long = 0L

    // Reusable StringBuilder to reduce heap allocation overhead
    private val logBuilder = StringBuilder(128)

    companion object {
        const val EXTRA_SENSOR_TYPE = "extra_sensor_type"
        private const val PREFS_NAME = "sensor_service_prefs"
        private const val KEY_LAST_SENSOR_TYPE = "last_sensor_type"
        private const val CHANNEL_ID = "sensor_service_channel"
        private const val NOTIF_ID = 1001

        private const val SAFETY_MARGIN_FACTOR = 1.1111 // 10% safety margin back-off
        private const val LOG_THROTTLE_NS = 33_333_333L // Throttles Logcat output to ~30 Hz to avoid logd drops
    }

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()

        // Background thread with URGENT_DISPLAY priority for tight timing loops
        sensorThread = HandlerThread("SensorBgThread", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
            start()
            sensorHandler = Handler(looper)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val sensorType = if (intent != null) {
            val type = intent.getIntExtra(EXTRA_SENSOR_TYPE, Sensor.TYPE_ACCELEROMETER)
            prefs.edit().putInt(KEY_LAST_SENSOR_TYPE, type).apply()
            type
        } else {
            // Restore last configured sensor type on START_STICKY service restarts
            prefs.getInt(KEY_LAST_SENSOR_TYPE, Sensor.TYPE_ACCELEROMETER)
        }

        setupMaxSafeSensorStream(sensorType)
        return START_STICKY
    }

    private fun setupMaxSafeSensorStream(sensorType: Int) {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (sensorManager == null) {
                stopSelf()
                return
            }

            targetSensor = sensorManager?.getDefaultSensor(sensorType)
            if (targetSensor == null) {
                stopSelf()
                return
            }

            minHardwareDelayUs = targetSensor?.minDelay ?: 0

            if (minHardwareDelayUs > 0) {
                safeTargetDelayUs = (minHardwareDelayUs * SAFETY_MARGIN_FACTOR).toInt()
                safeMinIntervalNs = safeTargetDelayUs.toLong() * 1000L
            } else {
                safeTargetDelayUs = SensorManager.SENSOR_DELAY_FASTEST
                safeMinIntervalNs = 0L
            }

            sensorManager?.unregisterListener(this)

            val registered = sensorManager?.registerListener(
                this,
                targetSensor,
                safeTargetDelayUs,
                0,
                sensorHandler
            ) ?: false

            if (!registered) {
                sensorManager?.registerListener(this, targetSensor, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
            }
        } catch (e: SecurityException) {
            stopSelf()
        } catch (t: Throwable) {
            stopSelf()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != targetSensor?.type) return

        val currentNs = event.timestamp

        if (safeMinIntervalNs > 0L && (currentNs - lastEventTimestampNs < safeMinIntervalNs) && lastEventTimestampNs != 0L) {
            return
        }
        lastEventTimestampNs = currentNs

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        printToTerminal(currentNs, x, y, z)
    }

    private fun printToTerminal(timestampNs: Long, x: Float, y: Float, z: Float) {
        // Throttling print frequency to ~30 Hz prevents logd line dropping
        if (timestampNs - lastLogTimestampNs >= LOG_THROTTLE_NS) {
            lastLogTimestampNs = timestampNs

            logBuilder.setLength(0)
            logBuilder.append("TS: ").append(timestampNs)
                .append(" | X: ").append(x)
                .append(" | Y: ").append(y)
                .append(" | Z: ").append(z)

            Log.v("DefensiveSensor", logBuilder.toString())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun promoteToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sensor Data Streamer",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Max-Rate Sensor Stream Active")
            .setContentText("Sampling at hardware max on background thread")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ (API 29+) 3-argument call passing matching specialUse bitmask
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            // Android 9 (API 28) 2-argument call
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Throwable) {}

        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
