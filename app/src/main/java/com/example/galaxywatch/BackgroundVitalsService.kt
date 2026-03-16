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

class BackgroundVitalsService : Service(), SensorEventListener {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db = Firebase.firestore
    private lateinit var sensorManager: SensorManager

    // Hardware Sensors
    private var accelerometer: Sensor? = null
    private var offBodySensor: Sensor? = null // NEW: Off-wrist sensor

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

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // 1. Start Fall Detection
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
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
        // --- 1. FALL DETECTION LOGIC ---
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val gForce = sqrt(x * x + y * y + z * z) / 9.81

            // Only trigger a fall if the watch is actually being worn!
            if (gForce > 2.5 && !fallDetected && isWatchOnWrist) {
                fallDetected = true
                fallMessage = "DETECTED!"
                pushToFirebase() // Instantly alert Caregiver

                serviceScope.launch {
                    delay(5000) // Keep the fall status active for 5 seconds
                    fallDetected = false
                    fallMessage = "Scanning..."
                    pushToFirebase() // Update Firebase that fall is cleared
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
                    pushToFirebase() // Instantly tell Caregiver
                } else {
                    // WATCH PUT BACK ON!
                    bpStatusText = "Scanning..."
                    pushToFirebase() // Instantly tell Caregiver
                }
            }
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