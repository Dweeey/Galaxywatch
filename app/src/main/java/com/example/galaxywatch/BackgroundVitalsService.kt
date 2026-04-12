package com.example.galaxywatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
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

        // Logging actions — trigger these from your app UI
        const val ACTION_START_LOGGING = "ACTION_START_LOGGING"
        const val ACTION_STOP_LOGGING  = "ACTION_STOP_LOGGING"   // saves as ADL (label=0)

        private const val PREFS_SYSTEM_STATE = "SYSTEM_STATE"
        private const val KEY_SOS_ACTIVE = "SOS_ACTIVE"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db = Firebase.firestore

    private lateinit var sensorManager: SensorManager

    private var accelerometer: Sensor? = null
    private var offBodySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var healthTrackingService: HealthTrackingService? = null
    private var hrTracker: HealthTracker? = null

    private var tflite: Interpreter? = null

    private var consecutiveHighScores = 0
    private val CONFIRMATION_WINDOWS = 3
    private val FALL_THRESHOLD = 0.45f
    private val VETO_THRESHOLD = 0.50f
    private val STILLNESS_VARIANCE_THRESHOLD = 0.3f
    private val POST_FALL_MOVEMENT_VETO = 1.5f

    private val sensorBuffer = FloatArray(512)

    @Volatile private var bufferIndex = 0
    @Volatile private var isVerifying = false
    @Volatile private var ignoreFallDetection = false
    @Volatile private var isManualSosActive = false

    private var sosSuppressionJob: Job? = null

    private val bufferLock = Any()

    private var currentHeartRate = "Scanning..."
    private var fallDetected = false
    private var fallMessage = "Scanning..."
    private var isWatchOnWrist = true

    private var lastFirebaseUpdateTime = 0L
    private val FIREBASE_UPDATE_INTERVAL = 30_000L

    // ── Gesture logging ──────────────────────────────────────────────────────
    @Volatile private var isLoggingMode = false
    private val loggedWindows = mutableListOf<FloatArray>()
    private val loggingLock = Any()
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d("SERVICE_DEBUG", "BackgroundVitalsService created")

        acquireWakeLock()
        startForegroundServiceNotification()
        loadLocalAIBrain()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, 20000)
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
                Log.d("GESTURE_LOG", "Logging started — perform your gestures now")
                return START_STICKY
            }

            ACTION_STOP_LOGGING -> {
                isLoggingMode = false
                saveLoggedData(label = 0)   // 0 = ADL / not a fall
                Log.d("GESTURE_LOG", "Logging stopped and saved")
                return START_STICKY
            }

            ACTION_TRIGGER_SOS -> {
                startManualSosSuppression()
                return START_STICKY
            }

            ACTION_CANCEL_FALL -> {
                Log.d("FALL_AI", "User cancelled fall alert")

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
                Log.d("FALL_AI", "Fall confirmed after timeout")

                fallDetected = true
                fallMessage = "FALL DETECTED!"

                SharedVitals.fallDetected.value = true
                SharedVitals.fallMessage.value = fallMessage

                pushDataUpdates(forceFirebase = true)

                serviceScope.launch {
                    delay(60_000L)

                    fallDetected = false
                    fallMessage = "Scanning..."
                    consecutiveHighScores = 0
                    isVerifying = false

                    SharedVitals.fallDetected.value = false
                    SharedVitals.fallMessage.value = fallMessage

                    pushDataUpdates(forceFirebase = true)

                    Log.d("FALL_AI", "Fall status reset to scanning after 1 minute")
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

        serviceScope.cancel()
        sensorManager.unregisterListener(this)
        hrTracker?.unsetEventListener()
        healthTrackingService?.disconnectService()
        tflite?.close()
        wakeLock?.release()
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

    private fun connectSamsungHealth() {
        val listener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                Log.d("SAMSUNG_SDK", "Connected to Samsung Health Platform")
                try {
                    hrTracker = healthTrackingService?.getHealthTracker(
                        HealthTrackerType.HEART_RATE_CONTINUOUS
                    )
                    hrTracker?.setEventListener(hrListener)
                } catch (e: Exception) {
                    Log.e("SAMSUNG_SDK", "Failed to open HR tracker: ${e.message}", e)
                }
            }

            override fun onConnectionEnded() {
                Log.w("SAMSUNG_SDK", "Disconnected from Health Platform")
            }

            override fun onConnectionFailed(e: HealthTrackerException?) {
                Log.e("SAMSUNG_SDK", "Connection failed: ${e?.message}")
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
                        Log.d("HR_DEBUG", "Heart rate updated locally: $currentHeartRate")
                    }
                } catch (e: Exception) {
                    Log.e("SAMSUNG_SDK", "HR read error: ${e.message}", e)
                }
            }
        }

        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError?) {
            Log.e("HR_DEBUG", "Tracker error: $e")
        }
    }

    private fun loadLocalAIBrain() {
        try {
            val fileDescriptor = assets.openFd("wedafall_brain.tflite")
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val mappedBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                fileDescriptor.startOffset,
                fileDescriptor.declaredLength
            )

            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }

            tflite = Interpreter(mappedBuffer, options)
            Log.d("FALL_AI", "Brain loaded successfully")
        } catch (e: Exception) {
            Log.e("FALL_AI", "Failed to load brain: ${e.message}", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER && isWatchOnWrist) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]

            val magnitude = Math.sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()

            synchronized(bufferLock) {
                if (bufferIndex < 512) {
                    sensorBuffer[bufferIndex++] = magnitude
                }

                if (bufferIndex >= 512) {
                    val snapshot = sensorBuffer.copyOf()
                    System.arraycopy(sensorBuffer, 256, sensorBuffer, 0, 256)
                    bufferIndex = 256

                    serviceScope.launch {
                        // If logging mode is on, capture the buffer and skip inference
                        if (isLoggingMode) {
                            synchronized(loggingLock) {
                                loggedWindows.add(snapshot)
                            }
                            Log.v("GESTURE_LOG", "Captured window #${loggedWindows.size}")
                            return@launch
                        }

                        runFallDetectionInference(snapshot)
                    }
                }
            }
        }

        if (event?.sensor?.type == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
            val currentlyOnBody = event.values[0] != 0f
            if (isWatchOnWrist != currentlyOnBody) {
                isWatchOnWrist = currentlyOnBody

                if (!isWatchOnWrist) {
                    currentHeartRate = "--"
                    SharedVitals.heartRate.value = "--"
                    synchronized(bufferLock) { bufferIndex = 0 }
                }

                pushDataUpdates(forceFirebase = true)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Gesture logger ───────────────────────────────────────────────────────
    /**
     * Saves all captured windows to a CSV file in the app's external files dir.
     * Pull from the watch with:
     *   adb pull /sdcard/Android/data/com.example.galaxywatch/files/ ./watch_data/
     *
     * @param label  0 = ADL (normal movement), 1 = Fall
     */
    private fun saveLoggedData(label: Int) {
        val windows: List<FloatArray>
        synchronized(loggingLock) {
            windows = loggedWindows.toList()
            loggedWindows.clear()
        }

        if (windows.isEmpty()) {
            Log.w("GESTURE_LOG", "No windows captured — nothing to save")
            return
        }

        serviceScope.launch {
            try {
                val filename = "gesture_log_label${label}_${System.currentTimeMillis()}.csv"
                val file = File(getExternalFilesDir(null), filename)

                file.bufferedWriter().use { writer ->
                    // Header: label, t0, t1, ..., t511
                    val header = "label," + (0 until 512).joinToString(",") { "t$it" }
                    writer.write(header)
                    writer.newLine()

                    for (window in windows) {
                        val row = "$label," + window.joinToString(",")
                        writer.write(row)
                        writer.newLine()
                    }
                }

                Log.d("GESTURE_LOG", "Saved ${windows.size} windows → ${file.absolutePath}")

            } catch (e: Exception) {
                Log.e("GESTURE_LOG", "Failed to save log: ${e.message}", e)
            }
        }
    }
    // ─────────────────────────────────────────────────────────────────────────

    private fun runFallDetectionInference(buffer: FloatArray) {
        if (tflite == null) return

        if (ignoreFallDetection || isManualSosActive || isSosActiveFromPrefs()) {
            Log.d("FALL_AI", "Ignored because manual SOS is active")
            return
        }

        val mean = buffer.average().toFloat()
        val variance = buffer.map { ((it - mean) * (it - mean)) }.average().toFloat()

        if (variance < STILLNESS_VARIANCE_THRESHOLD) {
            if (consecutiveHighScores > 0) consecutiveHighScores = 0
            Log.v("FALL_AI", "Still — skipping (variance=${"%.3f".format(variance)})")
            return
        }

        val maxMagnitude = buffer.max()
        if (maxMagnitude < 10f) {
            if (consecutiveHighScores > 0) consecutiveHighScores = 0
            Log.v("FALL_AI", "Low G — skipping (max=${maxMagnitude}m/s²)")
            return
        }

        val std = Math.sqrt(variance.toDouble()).toFloat()
        val normalizedBuffer = if (std > 0f) {
            FloatArray(512) { i -> (buffer[i] - mean) / std }
        } else {
            buffer.copyOf()
        }

        val input = Array(1) { Array(512) { i -> FloatArray(1).also { it[0] = normalizedBuffer[i] } } }
        val output = Array(1) { FloatArray(1) }

        try {
            tflite?.run(input, output)
            val fallProbability = output[0][0]

            Log.d(
                "FALL_AI",
                "p=${"%.3f".format(fallProbability)} var=${"%.3f".format(variance)} maxG=${"%.1f".format(maxMagnitude)}"
            )

            when {
                fallProbability > FALL_THRESHOLD -> {
                    consecutiveHighScores++
                    Log.d("FALL_AI", "High score $consecutiveHighScores/$CONFIRMATION_WINDOWS")

                    if (consecutiveHighScores >= CONFIRMATION_WINDOWS && !fallDetected && !isVerifying) {
                        isVerifying = true
                        Log.w("FALL_AI", "Suspected fall — verifying post-impact stillness")

                        serviceScope.launch {
                            delay(1500L)

                            var recentVariance = 0f
                            synchronized(bufferLock) {
                                val recentSamples = sensorBuffer.takeLast(75)
                                if (recentSamples.isNotEmpty()) {
                                    val recentMean = recentSamples.average().toFloat()
                                    recentVariance = recentSamples
                                        .map { (it - recentMean) * (it - recentMean) }
                                        .average()
                                        .toFloat()
                                }
                            }

                            if (recentVariance > POST_FALL_MOVEMENT_VETO) {
                                Log.d(
                                    "FALL_AI",
                                    "VETOED: post-impact variance=${"%.2f".format(recentVariance)}"
                                )
                                consecutiveHighScores = 0
                            } else {
                                Log.w(
                                    "FALL_AI",
                                    "CONFIRMED: post-impact still (variance=${"%.2f".format(recentVariance)})"
                                )

                                if (ignoreFallDetection || isManualSosActive || isSosActiveFromPrefs()) {
                                    Log.d("FALL_AI", "Fall confirmation blocked because SOS is active")
                                    fallDetected = false
                                    fallMessage = "Scanning..."
                                    SharedVitals.fallDetected.value = false
                                    SharedVitals.fallMessage.value = fallMessage
                                    pushDataUpdates(forceFirebase = true)
                                    isVerifying = false
                                    return@launch
                                }

                                fallDetected = true
                                fallMessage = "Checking..."
                                SharedVitals.fallDetected.value = true
                                SharedVitals.fallMessage.value = fallMessage

                                launchFallConfirmationActivity()
                            }

                            isVerifying = false
                        }
                    }
                }

                fallProbability < VETO_THRESHOLD -> {
                    if (!isVerifying) consecutiveHighScores = 0
                }

                else -> {
                    // hold counter
                }
            }
        } catch (e: Exception) {
            Log.e("FALL_AI", "Inference error: ${e.message}", e)
        }
    }

    private fun launchFallConfirmationActivity() {
        if (ignoreFallDetection || isManualSosActive || isSosActiveFromPrefs()) {
            Log.d("FALL_AI", "Blocked FallConfirmationActivity launch because SOS is active")
            return
        }

        val intent = Intent(
            this,
            com.example.galaxywatch.presentation.FallConfirmationActivity::class.java
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun startManualSosSuppression() {
        Log.d("SOS", "Manual SOS suppression started")

        isManualSosActive = true
        ignoreFallDetection = true
        setSosActivePref(true)

        isVerifying = false
        consecutiveHighScores = 0
        fallDetected = false
        fallMessage = "Scanning..."

        SharedVitals.fallDetected.value = false
        SharedVitals.fallMessage.value = fallMessage

        pushDataUpdates(forceFirebase = true)

        sosSuppressionJob?.cancel()
        sosSuppressionJob = serviceScope.launch {
            delay(60_000L)
            isManualSosActive = false
            ignoreFallDetection = false
            setSosActivePref(false)
            Log.d("SOS", "Manual SOS suppression ended")
        }
    }

    private fun setSosActivePref(active: Boolean) {
        getSharedPreferences(PREFS_SYSTEM_STATE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SOS_ACTIVE, active)
            .apply()
    }

    private fun isSosActiveFromPrefs(): Boolean {
        return getSharedPreferences(PREFS_SYSTEM_STATE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SOS_ACTIVE, false)
    }

    private fun pushDataUpdates(forceFirebase: Boolean = false) {
        val currentTimeMillis = System.currentTimeMillis()

        SharedVitals.heartRate.value = currentHeartRate
        SharedVitals.fallDetected.value = fallDetected
        SharedVitals.fallMessage.value = fallMessage

        if (!forceFirebase && currentTimeMillis - lastFirebaseUpdateTime < FIREBASE_UPDATE_INTERVAL) {
            return
        }

        lastFirebaseUpdateTime = currentTimeMillis

        val patientRef = db.collection("patients").document("patient_001")
        val liveUpdates = hashMapOf<String, Any>(
            "heartRate" to currentHeartRate,
            "fallDetected" to fallDetected,
            "statusMessage" to fallMessage,
            "timestamp" to currentTimeMillis
        )

        patientRef
            .set(liveUpdates, SetOptions.merge())
            .addOnSuccessListener { Log.d("FIREBASE_DEBUG", "Live data updated") }
            .addOnFailureListener { Log.e("FIREBASE_DEBUG", "Failed to update live data: ${it.message}") }

        patientRef
            .collection("history")
            .document(currentTimeMillis.toString())
            .set(HashMap(liveUpdates))
            .addOnSuccessListener { Log.d("FIREBASE_DEBUG", "History saved") }
            .addOnFailureListener { Log.e("FIREBASE_DEBUG", "Failed to save history: ${it.message}") }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "VitalsServiceChannel_Silent"

        val channel = NotificationChannel(
            channelId,
            "Health Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableVibration(false)
            setSound(null, null)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ElderCare Active")
            .setContentText("Monitoring heart rate and fall detection...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }
}

object SharedVitals {
    val heartRate = MutableStateFlow("--")
    val fallDetected = MutableStateFlow(false)
    val fallMessage = MutableStateFlow("Scanning...")
}