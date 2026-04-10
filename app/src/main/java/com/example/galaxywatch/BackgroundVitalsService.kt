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
import android.app.AlertDialog
import android.content.DialogInterface

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
    private var consecutiveHighScores = 0
    private val CONFIRMATION_WINDOWS  = 2
    private val FALL_THRESHOLD        = 0.45f
    private val VETO_THRESHOLD =  0.30f
    private val STILLNESS_VARIANCE_THRESHOLD = 0.3f
    private val sensorBuffer = FloatArray(512)
    private val POST_FALL_MOVEMENT_VETO = 3.0f

    @Volatile private var bufferIndex = 0
    @Volatile private var isVerifying = false
    private val bufferLock = Any()

    // State Variables
    private var currentHeartRate = "Scanning..."
    private var fallDetected = false
    private var fallMessage = "Scanning..."
    private var isWatchOnWrist = true
    private var fallTimerJob: Job? = null

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
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, 20000)
        }

        offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        offBodySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        healthTrackingService = HealthTrackingService(samsungConnectionListener, this)
        healthTrackingService?.connectService()

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
                    synchronized(bufferLock) { bufferIndex = 0 }
                }
                pushDataUpdates()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun runFallDetectionInference(buffer: FloatArray) {
        if (tflite == null) return

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

            Log.d("FALL_AI", "p=${"%.3f".format(fallProbability)} var=${"%.3f".format(variance)} maxG=${"%.1f".format(maxMagnitude)}")

            when {
                fallProbability > FALL_THRESHOLD -> {
                    consecutiveHighScores++
                    Log.d("FALL_AI", "High score $consecutiveHighScores/$CONFIRMATION_WINDOWS")

                    if (consecutiveHighScores >= CONFIRMATION_WINDOWS && !fallDetected && !isVerifying) {
                        isVerifying = true
                        Log.w("FALL_AI", "⚠️ Suspected fall — verifying post-impact stillness...")

                        serviceScope.launch {
                            delay(1500L)

                            var recentVariance = 0f
                            synchronized(bufferLock) {
                                val recentSamples = sensorBuffer.takeLast(75)

                                if (recentSamples.isNotEmpty()) {
                                    val recentMean = recentSamples.average().toFloat()
                                    recentVariance = recentSamples
                                        .map { (it - recentMean) * (it - recentMean) }
                                        .average().toFloat()
                                }
                            }

                            if (recentVariance > POST_FALL_MOVEMENT_VETO) {
                                Log.d("FALL_AI", "❌ VETOED: post-impact variance=${"%.2f".format(recentVariance)} — likely ADL")
                                consecutiveHighScores = 0
                            } else {
                                Log.w("FALL_AI", "✅ CONFIRMED: post-impact still (variance=${"%.2f".format(recentVariance)})")
                                fallDetected = true
                                fallMessage = "FALL DETECTED! (${(fallProbability * 100).toInt()}%)"
                                pushDataUpdates()

                                // Start the 10-second timer and ask if the user is okay
                                startFallConfirmationTimer()
                            }
                            isVerifying = false
                        }
                    }
                }

                fallProbability < VETO_THRESHOLD -> {
                    if (!isVerifying) consecutiveHighScores = 0
                }

                else -> { /* mid-zone — hold counter */ }
            }

        } catch (e: Exception) {
            Log.e("FALL_AI", "Inference error: ${e.message}")
        }
    }

    private fun startFallConfirmationTimer() {
        fallTimerJob = serviceScope.launch {
            delay(10000L)  // Wait for 10 seconds before confirming fall if no response

            // If no response after 10 seconds, confirm fall detection
            if (fallDetected) {
                Log.w("FALL_AI", "No response, automatically confirming fall.")
                fallMessage = "Fall Detected"
                pushDataUpdates()
            }
        }

        // Show the "Are you okay?" popup
        showFallConfirmationDialog()
    }

    private fun showFallConfirmationDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Are you okay?")
            .setMessage("We detected a fall. Are you okay?")
            .setPositiveButton("Yes") { _, _ ->
                // If the user responds "Yes", cancel the timer and update status
                fallDetected = false
                fallMessage = "Scanning..."
                pushDataUpdates()
                fallTimerJob?.cancel()  // Cancel the timer
            }
            .setNegativeButton("No") { _, _ ->
                // If the user responds "No", confirm the fall
                fallMessage = "Fall Detected!"
                pushDataUpdates()
                fallTimerJob?.cancel()  // Cancel the timer
            }
            .create()

        dialog.show()
    }

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
                patientRef.set(liveUpdates, SetOptions.merge())
            }

        // 3. Append to history subcollection (timestamped record)
        patientRef
            .collection("history")
            .document(currentTimeMillis.toString())
            .set(liveUpdates.clone() as HashMap<String, Any>)
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