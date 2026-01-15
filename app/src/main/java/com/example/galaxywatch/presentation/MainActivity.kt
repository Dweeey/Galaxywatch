package com.example.galaxywatch.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Card
import com.example.galaxywatch.presentation.theme.GalaxyWatchTheme
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey

class MainActivity : ComponentActivity() {

    private lateinit var healthTrackingService: HealthTrackingService
    private var heartRateTracker: HealthTracker? = null

    private var heartRate by mutableStateOf("75")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS), 1)
        }

        healthTrackingService = HealthTrackingService(connectionListener, applicationContext)
        healthTrackingService.connectService()

        setContent {
            WearApp(heartRate = heartRate)
        }
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.d("HealthSDK", "Connected")
            try {
                heartRateTracker = healthTrackingService.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
                startTracking()
            } catch (_: IllegalArgumentException) {
                Log.e("HealthSDK", "Tracker not available")
            }
        }

        override fun onConnectionEnded() {}
        override fun onConnectionFailed(e: HealthTrackerException?) {
            Log.e("HealthSDK", "Connection failed")
        }
    }

    private fun startTracking() {
        heartRateTracker?.setEventListener(heartRateListener)
    }

    private fun stopTracking() {
        heartRateTracker?.unsetEventListener()
    }

    private val heartRateListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            dataPoints.firstOrNull()?.let { data ->
                val hr = data.getValue(ValueKey.HeartRateSet.HEART_RATE)
                if (hr > 0) {
                    heartRate = "$hr"
                }
            }
        }

        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError?) {
            Log.e("HealthSDK", "Heart rate tracker error")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
        healthTrackingService.disconnectService()
    }
}

@Composable
fun WearApp(heartRate: String) {
    GalaxyWatchTheme {
        Scaffold(
            timeText = { TimeText() },
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(1.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    HealthCard(modifier = Modifier.weight(0.3f)) {
                        HeartbeatContent(heartRate = heartRate)
                    }
                    HealthCard(modifier = Modifier.weight(0.3f)) {
                        BloodPressureContent()
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    HealthCard(modifier = Modifier.weight(0.3f)) {
                        Spo2Content()
                    }
                    HealthCard(modifier = Modifier.weight(0.3f)) {
                        FallDetectionContent()
                    }
                }
            }
        }
    }
}

@Composable
fun HealthCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        onClick = {},
        modifier = modifier.clip(RoundedCornerShape(12.dp))
    ) {
        Box(modifier = Modifier.padding(vertical = 0.3.dp, horizontal = 0.3.dp)) {
            content()
        }
    }
}

@Composable
fun HeartbeatContent(heartRate: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Favorite, contentDescription = "Heartbeat", tint = MaterialTheme.colors.primary, modifier = Modifier.size(12.dp))
        Text("Heartbeat", fontWeight = FontWeight.Bold, fontSize = 8.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text("$heartRate bpm", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("May 9, 2025", fontSize = 6.sp, color = MaterialTheme.colors.onSurfaceVariant)
        Spacer(modifier = Modifier.height(2.dp))
        Button(onClick = { }, modifier = Modifier.height(16.dp)) { Text("Details", fontSize = 6.sp) }
    }
}

@Composable
fun BloodPressureContent() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.FlashOn, contentDescription = "Blood Pressure", tint = MaterialTheme.colors.primary, modifier = Modifier.size(12.dp))
        Text("Blood Pressure", fontWeight = FontWeight.Bold, fontSize = 8.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text("120/80", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("May 9, 2025", fontSize = 6.sp, color = MaterialTheme.colors.onSurfaceVariant)
        Spacer(modifier = Modifier.height(2.dp))
        Button(onClick = { }, modifier = Modifier.height(16.dp)) { Text("Details", fontSize = 6.sp) }
    }
}

@Composable
fun Spo2Content() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Favorite, contentDescription = "SPO2 Level", tint = MaterialTheme.colors.primary, modifier = Modifier.size(12.dp))
        Text("SPO2 Level", fontWeight = FontWeight.Bold, fontSize = 8.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text("96%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("Normal", color = MaterialTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 6.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Button(onClick = { }, modifier = Modifier.height(16.dp)) { Text("Details", fontSize = 6.sp) }
    }
}

@Composable
fun FallDetectionContent() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.AccessibilityNew, contentDescription = "Fall Detected", tint = MaterialTheme.colors.primary, modifier = Modifier.size(12.dp))
        Text("Fall Detected", fontWeight = FontWeight.Bold, fontSize = 8.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text("NONE", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("", fontSize = 6.sp) // Placeholder for alignment
        Spacer(modifier = Modifier.height(2.dp))
        Button(onClick = { }, modifier = Modifier.height(16.dp)) { Text("Details", fontSize = 6.sp) }
    }
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp(heartRate = "75")
}
