package com.example.galaxywatch.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.*
import androidx.health.services.client.getCapabilities
import androidx.wear.compose.material.*
import com.example.galaxywatch.presentation.theme.GalaxyWatchTheme
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Permission launcher to handle the result
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            // Trigger a UI update
            setContent {
                MainAppLogic(initialPermission = isGranted)
            }
        }

        setContent {
            // Check initial permission state
            val isGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BODY_SENSORS
            ) == PackageManager.PERMISSION_GRANTED

            if (!isGranted) {
                // Launch permission request if not granted
                LaunchedEffect(Unit) {
                    permissionLauncher.launch(Manifest.permission.BODY_SENSORS)
                }
            }

            MainAppLogic(initialPermission = isGranted)
        }
    }
}

@Composable
fun MainAppLogic(initialPermission: Boolean) {
    // We track permission state here so if it changes, we re-render
    var hasPermission by remember { mutableStateOf(initialPermission) }

    GalaxyWatchTheme {
        if (hasPermission) {
            WearApp()
        } else {
            // Simple placeholder while waiting for permission
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Waiting for sensors...", fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    // Initialize Health Services Client
    val measureClient = remember { HealthServices.getClient(context).measureClient }

    // State
    var heartRate by remember { mutableStateOf("...") }
    var currentTime by remember { mutableStateOf(getCurrentTime()) }
    var supportsHeartRate by remember { mutableStateOf<Boolean?>(null) }

    // Check capabilities on launch
    LaunchedEffect(Unit) {
        try {
            val capabilities = measureClient.getCapabilities()
            supportsHeartRate = DataType.HEART_RATE_BPM in capabilities.supportedDataTypesMeasure
            Log.d("HeartRate", "Device supports HR: $supportsHeartRate")
        } catch (e: Exception) {
            Log.e("HeartRate", "Error checking capabilities", e)
            supportsHeartRate = false
        }
    }


    // 1. Clock Loop (Updates every second)
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = getCurrentTime()
            delay(1000)
        }
    }

    // 2. Heart Rate Loop (Updates once per minute)
    LaunchedEffect(supportsHeartRate) {
        when (supportsHeartRate) {
            false -> {
                heartRate = "N/A" // Not Available
                return@LaunchedEffect
            }
            null -> {
                heartRate = "..." // Checking...
                return@LaunchedEffect
            }
            true -> {
                // Proceed with measurement
            }
        }


        while (true) {
            Log.d("HeartRate", "Starting 1-minute measurement cycle...")

            // Use withTimeoutOrNull to handle cases where no data is received.
            val hrValue: Double? = withTimeoutOrNull(30000L) { // 30-second timeout
                callbackFlow {
                    val callback = object : MeasureCallback {
                        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
                            Log.d("HeartRate", "Availability: $availability")
                            // The timeout logic handles sensor unavailability.
                        }

                        override fun onDataReceived(data: DataPointContainer) {
                            val latest = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value
                            Log.d("HeartRate", "Data received: $latest")
                            if (latest != null && latest > 0.0) {
                                trySend(latest)
                            }
                        }
                    }

                    Log.d("HeartRate", "Registering callback...")
                    measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)

                    awaitClose {
                        Log.d("HeartRate", "Unregistering callback...")
                        measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
                    }
                }.first() // waits for first data point, then cancels flow
            }


            // Update UI State
            if (hrValue != null) {
                heartRate = hrValue.toInt().toString()
            } else {
                Log.w("HeartRate", "Timeout or sensor unavailable. No value received in 30s.")
                heartRate = "--"
            }


            // Wait 30 seconds before next measurement (30s timeout + 30s delay = ~1 minute)
            delay(30 * 1000L)
        }
    }

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
                    HeartbeatContent(heartRate = heartRate, currentTime = currentTime)
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
fun HeartbeatContent(heartRate: String, currentTime: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Favorite, contentDescription = "Heartbeat", tint = MaterialTheme.colors.primary, modifier = Modifier.size(12.dp))
        Text("Heartbeat", fontWeight = FontWeight.Bold, fontSize = 8.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text("$heartRate bpm", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(currentTime, fontSize = 6.sp, color = MaterialTheme.colors.onSurface)
        Spacer(modifier = Modifier.height(2.dp))
        Button(onClick = {}, modifier = Modifier.height(16.dp)) { Text("Details", fontSize = 6.sp) }
    }
}

@Composable
fun BloodPressureContent() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.FlashOn, contentDescription = "Blood Pressure", tint = MaterialTheme.colors.primary, modifier = Modifier.size(12.dp))
        Text("Blood Pressure", fontWeight = FontWeight.Bold, fontSize = 8.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text("120/80", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(getCurrentTime(), fontSize = 6.sp, color = MaterialTheme.colors.onSurface)
        Spacer(modifier = Modifier.height(2.dp))
        Button(onClick = {}, modifier = Modifier.height(16.dp)) { Text("Details", fontSize = 6.sp) }
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
        Button(onClick = {}, modifier = Modifier.height(16.dp)) { Text("Details", fontSize = 6.sp) }
    }
}

@Composable
fun FallDetectionContent() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.AccessibilityNew, contentDescription = "Fall Detected", tint = MaterialTheme.colors.primary, modifier = Modifier.size(12.dp))
        Text("Fall Detected", fontWeight = FontWeight.Bold, fontSize = 8.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text("NONE", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("", fontSize = 6.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Button(onClick = {}, modifier = Modifier.height(16.dp)) { Text("Details", fontSize = 6.sp) }
    }
}

fun getCurrentTime(): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date())
}
