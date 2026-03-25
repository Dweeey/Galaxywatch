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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.sqrt

// 🚨 NEW: TensorFlow Lite Imports
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel

class BackgroundVitalsService : Service(), SensorEventListener {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db = Firebase.firestore
    private lateinit var sensorManager: SensorManager

    // Hardware Sensors
    private var accelerometer: Sensor? = null
    private var offBodySensor: Sensor? = null // NEW: Off-wrist sensor

    // 🚨 NEW: AI Variables
    private var tflite: Interpreter? = null
    private val sensorBuffer = FloatArray(512)
    private var bufferIndex = 0

    // State Variables
    private var currentHeartRate = "--"
    private var estimatedSys = 120
    private var estimatedDia = 80
    private var bpStatusText = "Normal"
    private var fallDetected = false
    private var fallMessage = "Scanning..."
    private var isWatchOnWrist = true // NEW: Battery saver flag

    private val measureClient by lazy { HealthServices.getClient(this).measureClient }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()

        // 🚨 NEW: Load the AI Brain
        loadAIBrain()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // 1. Start Fall Detection
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            // Using SENSOR_DELAY_GAME for faster AI data collection
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // 2. Start Off-Body Detection
        offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        offBodySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // 3. Start Heart Rate Monitor
        startHeartRateMonitoring()

        // 4. Start the Firebase Sync Loop (Every 60 Seconds)
        startFirebaseSyncLoop()
    }

    // 🚨 NEW: Load the TFLite Model from the assets folder
    private fun loadAIBrain() {
        try {
            val fileDescriptor = assets.openFd("sisfall_brain.tflite")
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val tfliteModel = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            tflite = Interpreter(tfliteModel)
            Log.d("FALL_AI", "Brain successfully loaded!")
        } catch (e: Exception) {
            Log.e("FALL_AI", "Error loading AI Brain: ${e.message}")
        }
    }

    private fun startForegroundServiceNotification() {
        // ID changed to build a silent channel
        val channelId = "VitalsServiceChannel_Silent"

        val channel = NotificationChannel(
            channelId,
            "Health Monitoring Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            // Disable all buzzing and sounds
            enableVibration(false)
            vibrationPattern = longArrayOf(0L)
            setSound(null, null)
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ElderCare Active")
            .setContentText("Monitoring vitals in background...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun startHeartRateMonitoring() {
        val callback = object : MeasureCallback {
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {}
            override fun onDataReceived(data: DataPointContainer) {
                // Only process heart rate if the watch is actually on the wrist!
                if (!isWatchOnWrist) return

                val latestHr = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value
                if (latestHr != null && latestHr > 0.0) {
                    val hrInt = latestHr.toInt()
                    currentHeartRate = hrInt.toString()

                    // Estimate BP
                    val diff = hrInt - 70
                    estimatedSys = 115 + (diff * 0.5).toInt()
                    estimatedDia = 75 + (diff * 0.2).toInt()

                    bpStatusText = when {
                        estimatedSys < 120 && estimatedDia < 80 -> "Normal"
                        estimatedSys < 130 && estimatedDia < 80 -> "Elevated"
                        estimatedSys < 140 || estimatedDia < 90 -> "High (Stage 1)"
                        else -> "High (Stage 2)"
                    }
                }
            }
        }

        serviceScope.launch {
            try {
                measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
            } catch (e: Exception) {
                Log.e("VitalsService", "Failed to register HR callback", e)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        // --- 1. AI FALL DETECTION LOGIC ---
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {

            // Only feed data to the AI if the watch is actually being worn
            if (isWatchOnWrist) {
                if (bufferIndex < 510) {
                    sensorBuffer[bufferIndex++] = event.values[0] // X
                    sensorBuffer[bufferIndex++] = event.values[1] // Y
                    sensorBuffer[bufferIndex++] = event.values[2] // Z
                }

                // When the buffer hits 512, ask the AI to make a prediction
                if (bufferIndex >= 512) {
                    runFallDetectionInference()
                    bufferIndex = 0 // Reset buffer for the next batch
                }
            }
        }

        // --- 2. OFF-WRIST DETECTION LOGIC ---
        if (event?.sensor?.type == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
            // event.values[0] returns 1.0 if ON body, 0.0 if OFF body
            val currentlyOnBody = event.values[0] != 0f

            if (isWatchOnWrist != currentlyOnBody) {
                isWatchOnWrist = currentlyOnBody

                if (!isWatchOnWrist) {
                    // WATCH TAKEN OFF!
                    currentHeartRate = "--"
                    bpStatusText = "Watch Off Wrist"
                    bufferIndex = 0 // 🚨 NEW: Clear half-finished AI data
                    pushToFirebase() // Instantly tell Caregiver
                } else {
                    // WATCH PUT BACK ON!
                    bpStatusText = "Scanning..."
                    pushToFirebase() // Instantly tell Caregiver
                }
            }
        }
    }

    // 🚨 NEW: AI Inference Function
    private fun runFallDetectionInference() {
        if (tflite == null) return

        val input = arrayOf(sensorBuffer)
        val output = arrayOf(FloatArray(3))

        try {
            tflite?.run(input, output)
            val fallProbability = output[0][1] // Assuming Index 1 is the 'Fall' probability

            // If the AI is >85% sure it's a fall, AND we aren't already alarming...
            if (fallProbability > 0.85f && !fallDetected) {
                val confidence = (fallProbability * 100).toInt()

                fallDetected = true
                fallMessage = "AI DETECTED! ($confidence%)"
                pushToFirebase() // Instantly alert Caregiver

                // Wait 5 seconds, then reset the alarm
                serviceScope.launch {
                    delay(5000)
                    if (isWatchOnWrist) { // Only reset if they didn't take the watch off
                        fallDetected = false
                        fallMessage = "Scanning..."
                        pushToFirebase()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FALL_AI", "Inference crashed: ${e.message}")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startFirebaseSyncLoop() {
        serviceScope.launch {
            while (isActive) {
                // Only do the heavy Firebase push if the watch is actually being worn!
                if (isWatchOnWrist) {
                    pushToFirebase()
                }
                delay(60 * 1000L) // Wait 60 seconds
            }
        }
    }

    private fun pushToFirebase() {
        val currentTimeMillis = System.currentTimeMillis()
        val patientRef = db.collection("patients").document("patient_001")

        // --- UPDATE THE UI FIRST ---
        SharedVitals.heartRate.value = currentHeartRate
        SharedVitals.sys.value = estimatedSys
        SharedVitals.dia.value = estimatedDia
        SharedVitals.bpStatusText.value = bpStatusText
        SharedVitals.fallDetected.value = fallDetected
        SharedVitals.fallMessage.value = fallMessage

        // 1. LIVE DASHBOARD UPDATE (Selective Update so SpO2 is safe!)
        val liveUpdates = hashMapOf<String, Any>(
            "heartRate" to currentHeartRate,
            "bloodPressure" to "$estimatedSys/$estimatedDia",
            "bpStatus" to bpStatusText,
            "fallDetected" to fallDetected,
            "statusMessage" to fallMessage,
            "timestamp" to currentTimeMillis
        )

        patientRef.update(liveUpdates)
            .addOnFailureListener {
                patientRef.set(liveUpdates, SetOptions.merge())
            }

        // 2. HISTORY LOG UPDATE (Keeps the ledger intact)
        val historyData = liveUpdates.clone() as HashMap<String, Any>
        patientRef.collection("history").document(currentTimeMillis.toString())
            .set(historyData)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        sensorManager.unregisterListener(this)
        tflite?.close() // 🚨 NEW: Clean up the AI memory when service dies
    }
}

// --- SHARED MEMORY OBJECT FOR THE UI ---
object SharedVitals {
    val heartRate = MutableStateFlow("--")
    val sys = MutableStateFlow(120)
    val dia = MutableStateFlow(80)
    val bpStatusText = MutableStateFlow("Normal")
    val fallDetected = MutableStateFlow(false)
    val fallMessage = MutableStateFlow("Scanning...")
}