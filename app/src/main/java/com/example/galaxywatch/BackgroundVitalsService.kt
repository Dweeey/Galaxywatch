package com.example.galaxywatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.galaxywatch.presentation.FallConfirmationActivity
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

class BackgroundVitalsService : Service(), SensorEventListener {

    companion object {
        @Volatile
        var isRunning = false

        const val ACTION_CONFIRM_FALL = "ACTION_CONFIRM_FALL"
        const val ACTION_CANCEL_FALL = "ACTION_CANCEL_FALL"
        const val ACTION_TRIGGER_SOS = "ACTION_TRIGGER_SOS"
        const val ACTION_START_LOGGING = "ACTION_START_LOGGING"
        const val ACTION_STOP_LOGGING = "ACTION_STOP_LOGGING"
        const val ACTION_AUTO_RETURN = "ACTION_AUTO_RETURN"

        // RAW DATA RECORDING ACTIONS
        const val ACTION_START_RAW_RECORDING = "ACTION_START_RAW_RECORDING"
        const val ACTION_STOP_RAW_RECORDING = "ACTION_STOP_RAW_RECORDING"
        const val EXTRA_RAW_LABEL = "EXTRA_RAW_LABEL"
        const val EXTRA_RAW_DURATION_MS = "EXTRA_RAW_DURATION_MS"

        const val FALL_ALERT_NOTIFICATION_ID = 1001

        private const val FALL_ALERT_CHANNEL_ID = "fall_alerts"
        private const val PREFS_SYSTEM_STATE = "SYSTEM_STATE"
        private const val KEY_SOS_ACTIVE = "SOS_ACTIVE"
        private const val KEY_FALL_ALERT_ACTIVE = "FALL_ALERT_ACTIVE"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db = Firebase.firestore

    private lateinit var sensorManager: SensorManager

    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var offBodySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var healthTrackingService: HealthTrackingService? = null
    private var hrTracker: HealthTracker? = null
    private var tflite: Interpreter? = null

    private val CONFIRMATION_WINDOWS = 2
    private val FALL_THRESHOLD = 0.68f
    private val VETO_THRESHOLD = 0.25f
    private val STILLNESS_VARIANCE_THRESHOLD = 0.3f
    private val POST_FALL_MOVEMENT_VETO = 0.10f

    private val RAW_WINDOW = 256
    private val RAW_OVERLAP = 128
    private val MODEL_LEN = 512

    private val accelBuffer = FloatArray(RAW_WINDOW)
    private val gyroBuffer = FloatArray(RAW_WINDOW)

    @Volatile
    private var bufferIndex = 0

    @Volatile
    private var latestGyroMag = 0f

    @Volatile
    private var consecutiveHighScores = 0

    @Volatile
    private var isVerifying = false

    @Volatile
    private var ignoreFallDetection = false

    @Volatile
    private var isManualSosActive = false

    private var sosSuppressionJob: Job? = null
    private var confirmedFallResetJob: Job? = null
    private var fallSafetyTimeoutJob: Job? = null
    private var autoReturnJob: Job? = null
    private val bufferLock = Any()

    private var currentHeartRate = "Scanning..."
    private var fallDetected = false
    private var fallMessage = "Scanning..."
    private var isWatchOnWrist = true

    private var lastFirebaseUpdateTime = 0L
    private val FIREBASE_UPDATE_INTERVAL = 30_000L

    @Volatile
    private var isLoggingMode = false

    private val loggedWindows = mutableListOf<Pair<FloatArray, FloatArray>>()
    private val loggingLock = Any()

    // RAW MOTION RECORDING STATE
    private data class RawMotionSample(
        val timestamp: Long,
        val label: String,
        val ax: Float,
        val ay: Float,
        val az: Float,
        val accelMag: Float,
        val gx: Float,
        val gy: Float,
        val gz: Float,
        val gyroMag: Float
    )

    private val rawRecordingLock = Any()
    private val rawSamples = mutableListOf<RawMotionSample>()

    @Volatile
    private var isRawRecording = false

    @Volatile
    private var rawRecordingLabel = "unlabeled"

    @Volatile
    private var latestGx = 0f

    @Volatile
    private var latestGy = 0f

    @Volatile
    private var latestGz = 0f

    private var rawRecordingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d("SERVICE_DEBUG", "BackgroundVitalsService created")

        acquireWakeLock()
        startForegroundServiceNotification()
        loadLocalAIBrain()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let { sensorManager.registerListener(this, it, 20_000) }

        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope!!, 20_000)
            Log.d("SERVICE_DEBUG", "Gyroscope registered")
        } else {
            Log.w("SERVICE_DEBUG", "No gyroscope found - gyro channel will be zero-filled")
        }

        offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        offBodySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        connectSamsungHealth()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LOGGING -> {
                synchronized(loggingLock) { loggedWindows.clear() }
                isLoggingMode = true
                Log.d("GESTURE_LOG", "Logging started")
                return START_STICKY
            }

            ACTION_STOP_LOGGING -> {
                isLoggingMode = false
                saveLoggedData(label = 0)
                return START_STICKY
            }

            ACTION_START_RAW_RECORDING -> {
                val label = intent.getStringExtra(EXTRA_RAW_LABEL) ?: "unlabeled"
                val durationMs = intent.getLongExtra(EXTRA_RAW_DURATION_MS, 30_000L)

                startRawRecording(label, durationMs)
                return START_STICKY
            }

            ACTION_STOP_RAW_RECORDING -> {
                stopRawRecordingAndSave()
                return START_STICKY
            }

            ACTION_TRIGGER_SOS -> {
                startManualSosSuppression()
                return START_STICKY
            }

            ACTION_CANCEL_FALL -> {
                Log.d("FALL_AI", "User cancelled fall alert")
                clearFallAlertState()
                NotificationManagerCompat.from(this).cancel(FALL_ALERT_NOTIFICATION_ID)

                fallSafetyTimeoutJob?.cancel()
                fallSafetyTimeoutJob = null

                confirmedFallResetJob?.cancel()
                confirmedFallResetJob = null

                fallDetected = false
                fallMessage = "Scanning..."
                consecutiveHighScores = 0
                isVerifying = false
                SharedVitals.fallDetected.value = false
                SharedVitals.fallMessage.value = fallMessage
                pushDataUpdates(forceFirebase = true)
                return START_STICKY
            }

            ACTION_CONFIRM_FALL -> {
                Log.d("FALL_AI", "Fall confirmed from confirmation flow")
                clearFallAlertState()
                NotificationManagerCompat.from(this).cancel(FALL_ALERT_NOTIFICATION_ID)
                showConfirmedFallForOneMinute()
                return START_STICKY
            }

            ACTION_AUTO_RETURN -> {
                val delayMs = intent.getLongExtra("delay_ms", 0L)

                autoReturnJob?.cancel()
                autoReturnJob = serviceScope.launch {
                    delay(delayMs)
                    Log.d("SPO2", "Returning to app after ${delayMs}ms")

                    startActivity(
                        Intent(
                            this@BackgroundVitalsService,
                            com.example.galaxywatch.presentation.MainActivity::class.java
                        ).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            )
                        }
                    )
                }
                return START_STICKY
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d("SERVICE_DEBUG", "BackgroundVitalsService destroyed")
        isRunning = false
        super.onDestroy()

        autoReturnJob?.cancel()
        rawRecordingJob?.cancel()

        serviceScope.cancel()
        sensorManager.unregisterListener(this)
        hrTracker?.unsetEventListener()
        healthTrackingService?.disconnectService()
        tflite?.close()
        wakeLock?.release()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                if (!isWatchOnWrist) return

                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val mag = Math.sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()

                // RAW RECORDING: Save ax, ay, az plus latest gx, gy, gz
                if (isRawRecording) {
                    val gyroMagNow = Math.sqrt(
                        (latestGx * latestGx + latestGy * latestGy + latestGz * latestGz).toDouble()
                    ).toFloat()

                    synchronized(rawRecordingLock) {
                        rawSamples.add(
                            RawMotionSample(
                                timestamp = System.currentTimeMillis(),
                                label = rawRecordingLabel,
                                ax = ax,
                                ay = ay,
                                az = az,
                                accelMag = mag,
                                gx = latestGx,
                                gy = latestGy,
                                gz = latestGz,
                                gyroMag = gyroMagNow
                            )
                        )
                    }
                }

                synchronized(bufferLock) {
                    if (bufferIndex < RAW_WINDOW) {
                        accelBuffer[bufferIndex] = mag
                        gyroBuffer[bufferIndex] = latestGyroMag
                        bufferIndex++
                    }

                    if (bufferIndex >= RAW_WINDOW) {
                        val accelSnap = accelBuffer.copyOf()
                        val gyroSnap = gyroBuffer.copyOf()

                        System.arraycopy(
                            accelBuffer,
                            RAW_OVERLAP,
                            accelBuffer,
                            0,
                            RAW_WINDOW - RAW_OVERLAP
                        )
                        System.arraycopy(
                            gyroBuffer,
                            RAW_OVERLAP,
                            gyroBuffer,
                            0,
                            RAW_WINDOW - RAW_OVERLAP
                        )
                        bufferIndex = RAW_WINDOW - RAW_OVERLAP

                        serviceScope.launch {
                            val accelResampled = linearResample(accelSnap, MODEL_LEN)
                            val gyroResampled = linearResample(gyroSnap, MODEL_LEN)

                            if (isLoggingMode) {
                                synchronized(loggingLock) {
                                    loggedWindows.add(Pair(accelResampled, gyroResampled))
                                }
                                Log.v("GESTURE_LOG", "Window #${loggedWindows.size} captured")
                                return@launch
                            }

                            runFallDetectionInference(accelResampled, gyroResampled)
                        }
                    }
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                if (!isWatchOnWrist) return

                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]

                latestGx = gx
                latestGy = gy
                latestGz = gz

                latestGyroMag = Math.sqrt((gx * gx + gy * gy + gz * gz).toDouble()).toFloat()
            }

            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> {
                val onBody = event.values[0] != 0f
                if (isWatchOnWrist != onBody) {
                    isWatchOnWrist = onBody
                    if (!isWatchOnWrist) {
                        currentHeartRate = "--"
                        SharedVitals.heartRate.value = "--"
                        synchronized(bufferLock) { bufferIndex = 0 }
                    }
                    pushDataUpdates(forceFirebase = true)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun linearResample(input: FloatArray, targetLen: Int): FloatArray {
        if (input.size == targetLen) return input.copyOf()
        val output = FloatArray(targetLen)
        val ratio = (input.size - 1).toFloat() / (targetLen - 1).toFloat()

        for (i in 0 until targetLen) {
            val pos = i * ratio
            val lo = pos.toInt().coerceAtMost(input.size - 2)
            val frac = pos - lo
            output[i] = input[lo] * (1f - frac) + input[lo + 1] * frac
        }
        return output
    }

    private fun runFallDetectionInference(accelBuf: FloatArray, gyroBuf: FloatArray) {
        if (tflite == null) return
        if (ignoreFallDetection || isManualSosActive || isSosActiveFromPrefs()) {
            Log.d("FALL_AI", "Skipped - SOS active")
            return
        }

        val accelMean = accelBuf.average().toFloat()
        val accelVar = accelBuf.map { (it - accelMean) * (it - accelMean) }.average().toFloat()
        val maxG = accelBuf.maxOrNull() ?: 0f
        val maxGyro = gyroBuf.maxOrNull() ?: 0f

        if (accelVar < STILLNESS_VARIANCE_THRESHOLD) {
            if (consecutiveHighScores > 0) consecutiveHighScores = 0
            Log.v("FALL_AI", "Still - skip (accelVar=${"%.3f".format(accelVar)})")
            return
        }

        if (maxG < 10.5f) {
            if (consecutiveHighScores > 0) consecutiveHighScores = 0
            Log.v("FALL_AI", "Low-G - skip (maxG=${"%.2f".format(maxG)})")
            return
        }

        val highAccelCount = accelBuf.count { it > 12.5f }
        val highGyroCount = gyroBuf.count { it > 1.5f }

        if (highAccelCount < 15) {
            consecutiveHighScores = 0
            Log.d("FALL_AI", "Rejected: brief accel burst (likely wrist gesture)")
            return
        }

        if (maxG > 10.5f && maxGyro < 1.3f) {
            consecutiveHighScores = 0
            Log.d("FALL_AI", "Rejected: accel spike without enough rotation")
            return
        }

        if (highGyroCount < 6) {
            consecutiveHighScores = 0
            Log.d("FALL_AI", "Rejected: too little gyro activity for a real fall")
            return
        }

        fun normalise(buf: FloatArray): FloatArray {
            val mean = buf.average().toFloat()
            val std = kotlin.math.sqrt(
                buf.map { ((it - mean) * (it - mean)).toDouble() }.average()
            ).toFloat()

            return if (std > 0f) {
                FloatArray(MODEL_LEN) { i -> (buf[i] - mean) / std }
            } else {
                buf.copyOf()
            }
        }

        val normAccel = normalise(accelBuf)
        val normGyro = normalise(gyroBuf)

        val input = Array(1) { Array(MODEL_LEN) { i -> floatArrayOf(normAccel[i], normGyro[i]) } }
        val output = Array(1) { FloatArray(1) }

        try {
            tflite?.run(input, output)
            val p = output[0][0]

            Log.d(
                "FALL_AI",
                "p=${"%.3f".format(p)} maxG=${"%.2f".format(maxG)} maxGyro=${"%.2f".format(maxGyro)} aCount=$highAccelCount gCount=$highGyroCount"
            )

            when {
                p > FALL_THRESHOLD -> {
                    consecutiveHighScores++
                    Log.d("FALL_AI", "Score $consecutiveHighScores/$CONFIRMATION_WINDOWS")

                    if (consecutiveHighScores >= CONFIRMATION_WINDOWS && !fallDetected && !isVerifying) {
                        isVerifying = true
                        Log.w("FALL_AI", "Suspected fall - checking post-impact fusion stillness")

                        serviceScope.launch {
                            delay(2500L)

                            var recentAccelVar = 0f
                            var recentGyroMax = 0f

                            synchronized(bufferLock) {
                                val end = bufferIndex
                                val start = (end - 125).coerceAtLeast(0)

                                val recentA = accelBuffer.slice(start until end)
                                val recentG = gyroBuffer.slice(start until end)

                                if (recentA.isNotEmpty()) {
                                    val recentMean = recentA.average().toFloat()
                                    recentAccelVar = recentA
                                        .map { (it - recentMean) * (it - recentMean) }
                                        .average()
                                        .toFloat()
                                }

                                if (recentG.isNotEmpty()) {
                                    recentGyroMax = recentG.maxOrNull() ?: 0f
                                }
                            }

                            val stillAfterImpact = recentAccelVar <= POST_FALL_MOVEMENT_VETO
                            val lowRotationAfterImpact = recentGyroMax < 1.2f

                            if (!stillAfterImpact && recentGyroMax > 1.8f && maxG > 14f) {
                                Log.w(
                                    "FALL_AI",
                                    "RISKY MOVEMENT: jump-like / forceful landing (recentVar=${"%.2f".format(recentAccelVar)}, recentGyro=${"%.2f".format(recentGyroMax)})"
                                )
                                fallDetected = false
                                fallMessage = "Risky Movement!"
                                SharedVitals.fallDetected.value = false
                                SharedVitals.fallMessage.value = fallMessage
                                pushDataUpdates(forceFirebase = true)
                                consecutiveHighScores = 0
                                isVerifying = false
                                return@launch
                            }

                            if (!stillAfterImpact || !lowRotationAfterImpact) {
                                Log.d(
                                    "FALL_AI",
                                    "VETOED: continued motion after impact (var=${"%.2f".format(recentAccelVar)}, gyro=${"%.2f".format(recentGyroMax)})"
                                )
                                consecutiveHighScores = 0
                                isVerifying = false
                            } else {
                                Log.w(
                                    "FALL_AI",
                                    "CONFIRMED: still after impact (var=${"%.2f".format(recentAccelVar)}, gyro=${"%.2f".format(recentGyroMax)})"
                                )

                                if (ignoreFallDetection || isManualSosActive || isSosActiveFromPrefs()) {
                                    fallDetected = false
                                    fallMessage = "Scanning..."
                                    SharedVitals.fallDetected.value = false
                                    SharedVitals.fallMessage.value = fallMessage
                                    pushDataUpdates(forceFirebase = true)
                                    isVerifying = false
                                    return@launch
                                }

                                showConfirmedFallForOneMinute()
                                showFallConfirmationNotification()
                            }
                        }
                    }
                }

                p < VETO_THRESHOLD -> {
                    if (!isVerifying) consecutiveHighScores = 0
                }

                else -> {
                    Unit
                }
            }
        } catch (e: Exception) {
            Log.e("FALL_AI", "Inference error: ${e.message}", e)
        }
    }

    private fun showConfirmedFallForOneMinute() {
        fallSafetyTimeoutJob?.cancel()
        fallSafetyTimeoutJob = null

        confirmedFallResetJob?.cancel()

        fallDetected = true
        fallMessage = "FALL DETECTED!"
        isVerifying = true
        SharedVitals.fallDetected.value = true
        SharedVitals.fallMessage.value = fallMessage
        pushDataUpdates(forceFirebase = true)

        confirmedFallResetJob = serviceScope.launch {
            delay(60_000L)
            fallDetected = false
            fallMessage = "Scanning..."
            consecutiveHighScores = 0
            isVerifying = false
            SharedVitals.fallDetected.value = false
            SharedVitals.fallMessage.value = fallMessage
            pushDataUpdates(forceFirebase = true)
            Log.d("FALL_AI", "Fall status reset after 1 minute")
        }
    }

    private fun showFallConfirmationNotification() {
        if (ignoreFallDetection || isManualSosActive || isSosActiveFromPrefs()) return

        if (isFallAlertActive()) {
            Log.d("FALL_AI", "Skipped - fall confirmation already active")
            return
        }

        setFallAlertActive(true)

        val fullScreenIntent = Intent(this, FallConfirmationActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            2001,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, BackgroundVitalsService::class.java).apply {
            action = ACTION_CANCEL_FALL
        }

        val cancelPendingIntent = PendingIntent.getService(
            this,
            2002,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, FALL_ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Fall detected")
            .setContentText("Please confirm if you're okay.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "I'M OK",
                cancelPendingIntent
            )
            .build()

        try {
            NotificationManagerCompat.from(this).notify(FALL_ALERT_NOTIFICATION_ID, notification)
            Log.d("FALL_AI", "Fall confirmation notification posted")
        } catch (e: SecurityException) {
            clearFallAlertState()
            Log.e("FALL_AI", "Notification permission denied: ${e.message}", e)
        }
    }

    // RAW MOTION RECORDING FUNCTIONS
    private fun startRawRecording(label: String, durationMs: Long = 30_000L) {
        Log.d("RAW_RECORD", "Started raw recording: $label for ${durationMs}ms")

        synchronized(rawRecordingLock) {
            rawSamples.clear()
        }

        rawRecordingLabel = label
        isRawRecording = true

        rawRecordingJob?.cancel()
        rawRecordingJob = serviceScope.launch {
            delay(durationMs)
            stopRawRecordingAndSave()
        }
    }

    private fun stopRawRecordingAndSave() {
        if (!isRawRecording) return

        isRawRecording = false
        rawRecordingJob?.cancel()
        rawRecordingJob = null

        val samplesToSave: List<RawMotionSample>

        synchronized(rawRecordingLock) {
            samplesToSave = rawSamples.toList()
            rawSamples.clear()
        }

        if (samplesToSave.isEmpty()) {
            Log.w("RAW_RECORD", "No raw samples captured")
            return
        }

        serviceScope.launch {
            saveRawMotionCsv(samplesToSave)
        }
    }

    private fun saveRawMotionCsv(samples: List<RawMotionSample>) {
        try {
            val safeLabel = samples.firstOrNull()?.label
                ?.replace(" ", "_")
                ?.replace("/", "_")
                ?.replace(",", "_")
                ?: "unlabeled"

            val file = File(
                getExternalFilesDir(null),
                "raw_motion_${safeLabel}_${System.currentTimeMillis()}.csv"
            )

            file.bufferedWriter().use { writer ->
                writer.write("timestamp,label,ax,ay,az,accelMag,gx,gy,gz,gyroMag")
                writer.newLine()

                for (sample in samples) {
                    val safeSampleLabel = sample.label.replace(",", "_")

                    writer.write(
                        "${sample.timestamp}," +
                                "$safeSampleLabel," +
                                "${sample.ax}," +
                                "${sample.ay}," +
                                "${sample.az}," +
                                "${sample.accelMag}," +
                                "${sample.gx}," +
                                "${sample.gy}," +
                                "${sample.gz}," +
                                "${sample.gyroMag}"
                    )
                    writer.newLine()
                }
            }

            Log.d("RAW_RECORD", "Saved ${samples.size} raw samples to ${file.absolutePath}")

        } catch (e: Exception) {
            Log.e("RAW_RECORD", "Failed to save raw motion CSV: ${e.message}", e)
        }
    }

    private fun saveLoggedData(label: Int) {
        val windows: List<Pair<FloatArray, FloatArray>>
        synchronized(loggingLock) {
            windows = loggedWindows.toList()
            loggedWindows.clear()
        }

        if (windows.isEmpty()) {
            Log.w("GESTURE_LOG", "No windows to save")
            return
        }

        serviceScope.launch {
            try {
                val filename = "gesture_log_label${label}_${System.currentTimeMillis()}.csv"
                val file = File(getExternalFilesDir(null), filename)

                file.bufferedWriter().use { writer ->
                    val accelCols = (0 until MODEL_LEN).joinToString(",") { "a$it" }
                    val gyroCols = (0 until MODEL_LEN).joinToString(",") { "g$it" }
                    writer.write("label,$accelCols,$gyroCols")
                    writer.newLine()

                    for ((accel, gyro) in windows) {
                        writer.write("$label,${accel.joinToString(",")},${gyro.joinToString(",")}")
                        writer.newLine()
                    }
                }

                Log.d("GESTURE_LOG", "Saved ${windows.size} windows -> ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e("GESTURE_LOG", "Save failed: ${e.message}", e)
            }
        }
    }

    private fun connectSamsungHealth() {
        val listener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                Log.d("SAMSUNG_SDK", "Connected")
                try {
                    hrTracker = healthTrackingService?.getHealthTracker(
                        HealthTrackerType.HEART_RATE_CONTINUOUS
                    )
                    hrTracker?.setEventListener(hrListener)
                } catch (e: Exception) {
                    Log.e("SAMSUNG_SDK", "HR tracker error: ${e.message}", e)
                }
            }

            override fun onConnectionEnded() {
                Log.w("SAMSUNG_SDK", "Disconnected")
            }

            override fun onConnectionFailed(e: HealthTrackerException?) {
                Log.e("SAMSUNG_SDK", "Failed: ${e?.message}")
            }
        }

        healthTrackingService = HealthTrackingService(listener, this)
        healthTrackingService?.connectService()
    }

    private val hrListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataList: List<DataPoint>) {
            if (!isWatchOnWrist) return

            for (data in dataList) {
                try {
                    val hr = data.getValue(ValueKey.HeartRateSet.HEART_RATE) as? Int ?: continue
                    if (hr > 0) {
                        currentHeartRate = hr.toString()
                        SharedVitals.heartRate.value = currentHeartRate
                        pushDataUpdates(forceFirebase = false)
                    }
                } catch (e: Exception) {
                    Log.e("SAMSUNG_SDK", "HR error: ${e.message}", e)
                }
            }
        }

        override fun onFlushCompleted() = Unit

        override fun onError(e: HealthTracker.TrackerError?) {
            Log.e("HR_DEBUG", "Error: $e")
        }
    }

    private fun loadLocalAIBrain() {
        try {
            val fd = assets.openFd("wedafall_brain_1.tflite")
            val mapped = FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            )
            tflite = Interpreter(mapped, Interpreter.Options().apply { setNumThreads(2) })
            Log.d("FALL_AI", "Brain loaded - expects input (1, 512, 2)")
        } catch (e: Exception) {
            Log.e("FALL_AI", "Brain load failed: ${e.message}", e)
        }
    }

    private fun startManualSosSuppression() {
        Log.d("SOS", "Suppression started")
        isManualSosActive = true
        ignoreFallDetection = true
        setSosActivePref(true)
        clearFallAlertState()
        NotificationManagerCompat.from(this).cancel(FALL_ALERT_NOTIFICATION_ID)

        isVerifying = false
        consecutiveHighScores = 0
        fallDetected = false
        fallMessage = "Scanning..."
        SharedVitals.fallDetected.value = false
        SharedVitals.fallMessage.value = fallMessage
        pushDataUpdates(forceFirebase = true)

        fallSafetyTimeoutJob?.cancel()
        fallSafetyTimeoutJob = null

        confirmedFallResetJob?.cancel()
        confirmedFallResetJob = null

        sosSuppressionJob?.cancel()
        sosSuppressionJob = serviceScope.launch {
            delay(60_000L)
            isManualSosActive = false
            ignoreFallDetection = false
            setSosActivePref(false)
            Log.d("SOS", "Suppression ended")
        }
    }

    private fun setSosActivePref(active: Boolean) =
        getSharedPreferences(PREFS_SYSTEM_STATE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SOS_ACTIVE, active)
            .apply()

    private fun isSosActiveFromPrefs() =
        getSharedPreferences(PREFS_SYSTEM_STATE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SOS_ACTIVE, false)

    private fun setFallAlertActive(active: Boolean) =
        getSharedPreferences(PREFS_SYSTEM_STATE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FALL_ALERT_ACTIVE, active)
            .apply()

    private fun isFallAlertActive() =
        getSharedPreferences(PREFS_SYSTEM_STATE, Context.MODE_PRIVATE)
            .getBoolean(KEY_FALL_ALERT_ACTIVE, false)

    private fun clearFallAlertState() {
        setFallAlertActive(false)
    }

    private fun pushDataUpdates(forceFirebase: Boolean = false) {
        val now = System.currentTimeMillis()

        SharedVitals.heartRate.value = currentHeartRate
        SharedVitals.fallDetected.value = fallDetected
        SharedVitals.fallMessage.value = fallMessage

        if (!forceFirebase && now - lastFirebaseUpdateTime < FIREBASE_UPDATE_INTERVAL) return
        lastFirebaseUpdateTime = now

        val ref = db.collection("patients").document("patient_001")
        val updates = hashMapOf<String, Any>(
            "heartRate" to currentHeartRate,
            "fallDetected" to fallDetected,
            "statusMessage" to fallMessage,
            "timestamp" to now
        )

        ref.set(updates, SetOptions.merge())
        ref.collection("history").document(now.toString()).set(HashMap(updates))
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ElderCare::VitalsWakeLock"
        )
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()
    }

    private fun startForegroundServiceNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val serviceChannelId = "VitalsServiceChannel_Silent"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    serviceChannelId,
                    "Health Monitoring",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    enableVibration(false)
                    setSound(null, null)
                }
            )

            notificationManager.createNotificationChannel(
                NotificationChannel(
                    FALL_ALERT_CHANNEL_ID,
                    "Fall Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Urgent fall confirmation alerts"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 700, 300, 700, 300, 700)
                }
            )
        }

        startForeground(
            1,
            NotificationCompat.Builder(this, serviceChannelId)
                .setContentTitle("ElderCare Active")
                .setContentText("Monitoring heart rate and fall detection...")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build()
        )
    }
}

object SharedVitals {
    val heartRate = MutableStateFlow("--")
    val fallDetected = MutableStateFlow(false)
    val fallMessage = MutableStateFlow("Scanning...")
}