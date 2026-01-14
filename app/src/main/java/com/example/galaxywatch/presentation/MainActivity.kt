package com.example.galaxywatch.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.wear.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.samsung.android.service.health.tracking.*
import com.samsung.android.service.health.tracking.data.*
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    // Samsung Health
    private lateinit var healthTrackingService: HealthTrackingService
    private var heartRateTracker: HealthTracker? = null
    private var spo2Tracker: HealthTracker? = null

    // Accelerometer
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // Compose states
    private var heartRate by mutableStateOf("--")
    private var spo2 by mutableStateOf("--")
    private var accel by mutableStateOf("X: --  Y: --  Z: --")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Permission
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS), 1)
        }

        // Sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Samsung Health
        healthTrackingService =
            HealthTrackingService(connectionListener, applicationContext)
        healthTrackingService.connectService()

        // Compose UI
        setContent {
            WearApp(
                heartRate = heartRate,
                spo2 = spo2,
                accel = accel,
                onStart = { startTracking() },
                onStop = { stopTracking() },
                onMeasureSpo2 = { measureSpO2() }
            )
        }
    }

    // --- Samsung Health Connection ---
    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.d("HealthSDK", "Connected")

            try {
                heartRateTracker = healthTrackingService.getHealthTracker(
                    HealthTrackerType.HEART_RATE_CONTINUOUS
                )
                spo2Tracker = healthTrackingService.getHealthTracker(
                    HealthTrackerType.SPO2_ON_DEMAND
                )
            } catch (_: IllegalArgumentException) {
                Log.e("HealthSDK", "Tracker not available")
            }
        }

        override fun onConnectionEnded() {}
        override fun onConnectionFailed(e: HealthTrackerException?) {
            Log.e("HealthSDK", "Connection failed")
        }
    }

    // --- Controls ---
    private fun startTracking() {
        heartRateTracker?.setEventListener(heartRateListener)

        accelerometer?.let {
            sensorManager.registerListener(
                accelListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }

        Toast.makeText(this, "Tracking Started", Toast.LENGTH_SHORT).show()
    }

    private fun stopTracking() {
        heartRateTracker?.unsetEventListener()
        spo2Tracker?.unsetEventListener()
        sensorManager.unregisterListener(accelListener)
        Toast.makeText(this, "Tracking Stopped", Toast.LENGTH_SHORT).show()
    }

    private fun measureSpO2() {
        spo2Tracker?.setEventListener(spo2Listener)
        Toast.makeText(this, "Measuring SpO2", Toast.LENGTH_SHORT).show()
    }

    // --- Health Data ---
    private val heartRateListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (data in dataPoints) {
                val hr = data.getValue(ValueKey.HeartRateSet.HEART_RATE)
                if (hr > 0) {
                    heartRate = "$hr bpm"
                }
            }
        }

        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError?) {
            Log.e("HealthSDK", "Heart rate tracker error")
        }
    }

    private val spo2Listener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (data in dataPoints) {
                val status = data.getValue(ValueKey.SpO2Set.STATUS)
                if (status == 2) { // STATUS_MEASUREMENT_SUCCEEDED
                    val value = data.getValue(ValueKey.SpO2Set.SPO2)
                    spo2 = "$value %"
                    spo2Tracker?.unsetEventListener()
                }
            }
        }

        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError?) {
            Log.e("HealthSDK", "SpO2 tracker error")
        }
    }

    // --- Accelerometer ---
    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                accel = "X: ${it.values[0].roundToInt()}  " +
                        "Y: ${it.values[1].roundToInt()}  " +
                        "Z: ${it.values[2].roundToInt()}"
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        healthTrackingService.disconnectService()
    }
}

@Composable
fun WearApp(
    heartRate: String,
    spo2: String,
    accel: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMeasureSpo2: () -> Unit
) {
    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("❤️ Heart Rate: $heartRate")
            Spacer(Modifier.height(6.dp))
            Text("🫁 SpO₂: $spo2")
            Spacer(Modifier.height(6.dp))
            Text("📐 $accel")

            Spacer(Modifier.height(12.dp))

            Button(onClick = onStart) { Text("Start") }
            Button(onClick = onStop) { Text("Stop") }
            Button(onClick = onMeasureSpo2) { Text("SpO₂") }
        }
    }
}

//@Preview(device = androidx.compose.ui.tooling.preview.Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
//@Composable
//fun DefaultPreview() {
//    WearApp(
//        heartRate = "72 bpm",
//        spo2 = "9%",
//        accel = "X: 0 Y: 0 Z: 9",
//        onStart = {},
//        onStop = {},
//        onMeasureSpo2 = {}
//    )
//}
