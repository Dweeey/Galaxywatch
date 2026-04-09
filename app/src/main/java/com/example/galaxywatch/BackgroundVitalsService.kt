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

// Samsung Privileged Health SDK Imports
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey

// Local Fall Detection Brain Imports
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel

class BackgroundVitalsService : Service(), SensorEventListener {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db = Firebase.firestore
    private lateinit var sensorManager: SensorManager

    // Hardware Sensors
    private var accelerometer: Sensor? = null
    private var offBodySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Samsung SDK Variables
    private var healthTrackingService: HealthTrackingService? = null
    private var hrTracker: HealthTracker? = null

    // AI Variables
    private var tflite: Interpreter? = null
    // Add these fields at the top of the class alongside other AI Variables
    private var consecutiveHighScores = 0
    private val CONFIRMATION_WINDOWS  = 2    // need 2 consecutive windows above threshold
    private val FALL_THRESHOLD        = 0.5f // raised from 0.2 — reduces false alarms
    private val VETO_THRESHOLD =  0.35f // below this = definitely not a fall
    private val sensorBuffer = FloatArray(512)
    private val STILLNESS_VARIANCE_THRESHOLD = 0.5f

    // FIX: @Volatile prevents stale reads across threads
    @Volatile private var bufferIndex = 0
    @Volatile private var isVerifying = false
    private val POST_FALL_MOVEMENT_VETO = 3.0f

    // FIX: Lock object to prevent race conditions on the buffera
    private val bufferLock = Any()

    // State Variables
    private var currentHeartRate = "Scanning..."
    private var fallDetected = false
    private var fallMessage = "Scanning..."
    private var isWatchOnWrist = true

    // ─────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ElderCare::VitalsWakeLock"
        )
        wakeLock?.acquire()

        startForegroundServiceNotification()
        loadLocalAIBrain()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // FIX: Use 20000 microseconds = 50Hz to match training data sampling rate.
        // SENSOR_DELAY_GAME runs at ~200Hz which mismatches the model's expected input.
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, 20000)
        }

        offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        offBodySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Boot up the Samsung Health SDK
        healthTrackingService = HealthTrackingService(samsungConnectionListener, this)
        healthTrackingService?.connectService()

        // Start the throttled Firebase sync loop
        startSyncLoop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        sensorManager.unregisterListener(this)
        hrTracker?.unsetEventListener()
        healthTrackingService?.disconnectService()
        tflite?.close()
        wakeLock?.release()
    }

    // ─────────────────────────────────────────────
    // SAMSUNG HEALTH SDK
    // ─────────────────────────────────────────────

    private val samsungConnectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.d("SAMSUNG_SDK", "✅ Connected to Samsung Health Platform!")
            try {
                hrTracker = healthTrackingService?.getHealthTracker(
                    HealthTrackerType.HEART_RATE_CONTINUOUS
                )
                hrTracker?.setEventListener(hrListener)
            } catch (e: Exception) {
                Log.e("SAMSUNG_SDK", "Failed to open HR tracker: ${e.message}")
            }
        }

        override fun onConnectionEnded() {
            Log.d("SAMSUNG_SDK", "Disconnected from Health Platform.")
        }

        override fun onConnectionFailed(e: HealthTrackerException?) {
            Log.e("SAMSUNG_SDK", "Connection Failed. Is Samsung Developer Mode enabled?")
        }
    }

    private val hrListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataList: List<DataPoint>) {
            if (!isWatchOnWrist) return
            for (data in dataList) {
                try {
                    val hr = data.getValue(ValueKey.HeartRateSet.HEART_RATE) as? Int ?: continue
                    if (hr > 0) {
                        currentHeartRate = hr.toString()
                    }
                } catch (e: Exception) {
                    Log.e("SAMSUNG_SDK", "HR read error: ${e.message}")
                }
            }
        }

        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError?) {}
    }

    // ─────────────────────────────────────────────
    // AI BRAIN LOADER
    // ─────────────────────────────────────────────

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
            Log.d("FALL_AI", "✅ Brain loaded successfully")
        } catch (e: Exception) {
            Log.e("FALL_AI", "❌ Failed to load brain: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────
    // SENSOR EVENTS
    // ─────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent?) {

        // ── Accelerometer → Fall Detection ──
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER && isWatchOnWrist) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]

            // FIX: Compute magnitude — matches training preprocessing exactly.
            // Training used sqrt(ax²+ay²+az²), NOT raw XYZ channels.
            val magnitude = Math.sqrt(
                (ax * ax + ay * ay + az * az).toDouble()
            ).toFloat()

            // FIX: synchronized block prevents buffer corruption from
            // simultaneous reads/writes across the sensor and IO threads.
            synchronized(bufferLock) {
                if (bufferIndex < 512) {
                    sensorBuffer[bufferIndex++] = magnitude
                }

                if (bufferIndex >= 512) {
                    // Snapshot the buffer for inference (fast, stays on sensor thread)
                    val snapshot = sensorBuffer.copyOf()

                    // FIX: Sliding window — keep last 256 samples (50% overlap).
                    // This means inference runs every 256 new samples instead of
                    // every 512, so falls near window boundaries are never missed.
                    System.arraycopy(sensorBuffer, 256, sensorBuffer, 0, 256)
                    bufferIndex = 256

                    // FIX: Run inference on IO thread, NOT the sensor/UI thread.
                    // Inference takes ~10–50ms and would cause ANR crashes on main thread.
                    serviceScope.launch {
                        runFallDetectionInference(snapshot)
                    }
                }
            }
        }

        // ── Off-Body Sensor → Wrist Detection ──
        if (event?.sensor?.type == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
            val currentlyOnBody = event.values[0] != 0f
            if (isWatchOnWrist != currentlyOnBody) {
                isWatchOnWrist = currentlyOnBody

                if (!isWatchOnWrist) {
                    // Watch removed — clear stale data
                    currentHeartRate = "--"
                    // FIX: Reset buffer so old movement data doesn't
                    // contaminate the next session when watch is put back on.
                    synchronized(bufferLock) { bufferIndex = 0 }
                }

                // Push wrist state change immediately
                pushDataUpdates()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ─────────────────────────────────────────────
    // FALL DETECTION INFERENCE
    // ─────────────────────────────────────────────

    private fun runFallDetectionInference(buffer: FloatArray) {
        if (tflite == null) return

        // Guard 1: Stillness check
        val mean = buffer.average().toFloat()
        val variance = buffer.map { ((it - mean) * (it - mean)) }.average().toFloat()

        if (variance < STILLNESS_VARIANCE_THRESHOLD) {
            if (consecutiveHighScores > 0) consecutiveHighScores = 0
            Log.v("FALL_AI", "Still — skipping (variance=${"%.3f".format(variance)})")
            return
        }

        // Guard 2: Magnitude check
        val maxMagnitude = buffer.max()
        if (maxMagnitude < 12f) {
            if (consecutiveHighScores > 0) consecutiveHighScores = 0
            Log.v("FALL_AI", "Low G — skipping (max=${maxMagnitude}m/s²)")
            return
        }

        // Normalize
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

            Log.d("FALL_AI", "p=${"%.3f".format(fallProbability)} var=${"%.3f".format(variance)} maxG=${"%.1f".format(maxMagnitude)}")

            when {
                fallProbability > FALL_THRESHOLD -> {
                    consecutiveHighScores++
                    Log.d("FALL_AI", "High score $consecutiveHighScores/$CONFIRMATION_WINDOWS")

                    // KEY FIX: Don't alert immediately — enter a 3-second verification window.
                    // Shaving = arm keeps moving vigorously after the "impact" → variance stays HIGH → VETO
                    // Real fall = person is on the ground → variance drops LOW → CONFIRM
                    if (consecutiveHighScores >= CONFIRMATION_WINDOWS && !fallDetected && !isVerifying) {
                        isVerifying = true
                        Log.w("FALL_AI", "⚠️ Suspected fall — verifying post-impact stillness...")

                        serviceScope.launch {
                            delay(1500L)  // wait 1.5 seconds and sample what the wrist is doing

                            var recentVariance = 0f
                            synchronized(bufferLock) {
                                // Only grab the last 75 samples (1.5 seconds) or 150 samples (3 seconds)
                                val samplesToLookBack = 75

                                // Create a mini-list of just those recent samples
                                val recentSamples = sensorBuffer.takeLast(samplesToLookBack)

                                if (recentSamples.isNotEmpty()) {
                                    val recentMean = recentSamples.average().toFloat()
                                    recentVariance = recentSamples
                                        .map { (it - recentMean) * (it - recentMean) }
                                        .average().toFloat()
                                }
                            }

                            if (recentVariance > POST_FALL_MOVEMENT_VETO) {
                                // Still moving vigorously — almost certainly an ADL (shaving, gesturing, etc.)
                                Log.d("FALL_AI", "❌ VETOED: post-impact variance=${"%.2f".format(recentVariance)} — likely ADL")
                                consecutiveHighScores = 0
                            } else {
                                // Wrist went still — consistent with lying on the ground
                                Log.w("FALL_AI", "✅ CONFIRMED: post-impact still (variance=${"%.2f".format(recentVariance)})")
                                fallDetected = true
                                fallMessage = "FALL DETECTED! (${(fallProbability * 100).toInt()}%)"
                                pushDataUpdates()

                                delay(10_000L)
                                if (isWatchOnWrist) {
                                    fallDetected = false
                                    fallMessage = "Scanning..."
                                    consecutiveHighScores = 0
                                    pushDataUpdates()
                                }
                            }
                            isVerifying = false
                        }
                    }
                }

                fallProbability < VETO_THRESHOLD -> {
                    if (!isVerifying) consecutiveHighScores = 0  // don't reset mid-verification
                }

                else -> { /* mid-zone — hold counter */ }
            }

        } catch (e: Exception) {
            Log.e("FALL_AI", "Inference error: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────
    // DATA SYNC
    // ─────────────────────────────────────────────

    // Periodic sync loop — pushes vitals to Firebase every 30 seconds.
    // Fall events bypass this and push immediately via pushDataUpdates().
    private fun startSyncLoop() {
        serviceScope.launch {
            while (isActive) {
                if (isWatchOnWrist &&
                    currentHeartRate != "--" &&
                    currentHeartRate != "Scanning..."
                ) {
                    pushDataUpdates()
                }
                delay(30_000L)
            }
        }
    }

    // Single source of truth for both UI and Firebase updates.
    private fun pushDataUpdates() {
        val currentTimeMillis = System.currentTimeMillis()

        // 1. Update the in-memory UI state
        SharedVitals.heartRate.value = currentHeartRate
        SharedVitals.fallDetected.value = fallDetected
        SharedVitals.fallMessage.value = fallMessage

        // 2. Push live status to Firebase (merge so we don't overwrite other fields)
        val patientRef = db.collection("patients").document("patient_001")
        val liveUpdates = hashMapOf<String, Any>(
            "heartRate"     to currentHeartRate,
            "fallDetected"  to fallDetected,
            "statusMessage" to fallMessage,
            "timestamp"     to currentTimeMillis
        )

        patientRef
            .update(liveUpdates)
            .addOnFailureListener {
                // Document may not exist yet — create it
                patientRef.set(liveUpdates, SetOptions.merge())
            }

        // 3. Append to history subcollection (timestamped record)
        patientRef
            .collection("history")
            .document(currentTimeMillis.toString())
            .set(liveUpdates.clone() as HashMap<String, Any>)
    }

    // ─────────────────────────────────────────────
    // NOTIFICATION
    // ─────────────────────────────────────────────

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
            .setContentText("Monitoring vitals and fall detection...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }
}

// ─────────────────────────────────────────────
// SHARED STATE (UI ↔ Service)
// ─────────────────────────────────────────────

object SharedVitals {
    val heartRate    = MutableStateFlow("--")
    val fallDetected = MutableStateFlow(false)
    val fallMessage  = MutableStateFlow("Scanning...")
}