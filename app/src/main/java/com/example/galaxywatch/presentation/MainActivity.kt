package com.example.galaxywatch.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material.*
import com.example.galaxywatch.presentation.theme.GalaxyWatchTheme
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            setContent { MainAppLogic() }
        }

        val healthConnectPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            // handled in UI
        }

        setContent {
            val isBodySensorGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BODY_SENSORS
            ) == PackageManager.PERMISSION_GRANTED

            if (!isBodySensorGranted) {
                LaunchedEffect(Unit) {
                    permissionLauncher.launch(Manifest.permission.BODY_SENSORS)
                }
            }

            LaunchedEffect(Unit) {
                try {
                    val client = HealthConnectClient.getOrCreate(this@MainActivity)
                    val permissions = setOf(
                        HealthPermission.getReadPermission(OxygenSaturationRecord::class)
                    )
                    if (!client.permissionController.getGrantedPermissions().containsAll(permissions)) {
                        healthConnectPermissionLauncher.launch(permissions.toTypedArray())
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Health Connect not available", e)
                }
            }

            MainAppLogic()
        }
    }
}

@Composable
fun MainAppLogic() {
    GalaxyWatchTheme {
        WearApp()
    }
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val measureClient = remember { HealthServices.getClient(context).measureClient }
    val scope = rememberCoroutineScope()

    val healthConnectClient = remember {
        try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            Log.e("HealthConnect", "Service not available on this device", e)
            null
        }
    }

    var heartRate by remember { mutableStateOf("...") }
    var spo2 by remember { mutableStateOf("--") }
    var currentTime by remember { mutableStateOf(getCurrentTime()) }
    var supportsHeartRate by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        try {
            val capabilities = measureClient.getCapabilitiesAsync().await()
            supportsHeartRate = capabilities.supportedDataTypesMeasure.contains(DataType.HEART_RATE_BPM)
        } catch (e: Exception) {
            supportsHeartRate = false
        }

        if (healthConnectClient != null) {
            spo2 = readLatestSpo2(healthConnectClient)
        } else {
            spo2 = "N/A"
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (healthConnectClient != null) {
                    scope.launch {
                        spo2 = readLatestSpo2(healthConnectClient)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = getCurrentTime()
            delay(1000)
        }
    }

    LaunchedEffect(supportsHeartRate) {
        if (supportsHeartRate == false) {
            heartRate = "N/A"
            return@LaunchedEffect
        }
        while (true) {
            val hrValue: Double? = withTimeoutOrNull(30000L) {
                callbackFlow {
                    val callback = object : MeasureCallback {
                        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {}
                        override fun onDataReceived(data: DataPointContainer) {
                            val latest = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value
                            if (latest != null && latest > 0.0) trySend(latest)
                        }
                    }
                    measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
                    awaitClose { measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback) }
                }.first()
            }
            heartRate = hrValue?.toInt()?.toString() ?: "--"
            delay(60 * 1000L)
        }
    }

    Scaffold(
        timeText = { TimeText() },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(1.dp),
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
                    Spo2Content(spo2 = spo2)
                }
                HealthCard(modifier = Modifier.weight(0.3f)) {
                    FallDetectionContent()
                }
            }
        }
    }
}

// --- HELPER FUNCTIONS ---

fun launchSamsungHealthSpo2(context: Context) {
    // FIX: Check BOTH possible package names
    val packages = listOf(
        "com.sec.android.app.shealth",       // Phone/Standard version
        "com.samsung.android.wear.shealth"   // Wear OS specific version
    )

    val deepLink = "shealth://oxygen_saturation"
    var appFound = false

    for (packageName in packages) {
        try {
            // Check if this package exists on the watch
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                appFound = true
                // Try Deep Link first
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                    intent.setPackage(packageName)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    return // Success! Stop here.
                } catch (e: Exception) {
                    // Deep link failed, just open the app normally
                    context.startActivity(launchIntent)
                    return // Success! Stop here.
                }
            }
        } catch (e: Exception) {
            // Continue to next package
        }
    }

    if (!appFound) {
        Toast.makeText(context, "Samsung Health app not installed", Toast.LENGTH_SHORT).show()
    }
}

suspend fun readLatestSpo2(client: HealthConnectClient): String {
    return try {
        val response: ReadRecordsResponse<OxygenSaturationRecord> = client.readRecords(
            ReadRecordsRequest(
                recordType = OxygenSaturationRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    startTime = Instant.now().minus(24, ChronoUnit.HOURS),
                    endTime = Instant.now()
                ),
                ascendingOrder = false,
                pageSize = 1
            )
        )
        val record = response.records.firstOrNull()
        if (record != null) "${record.percentage.value.toInt()}%" else "No Data"
    } catch (e: Exception) {
        Log.e("SPO2", "Error reading health connect", e)
        "Perm needed"
    }
}

fun getCurrentTime(): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date())
}

// --- COMPOSABLES ---

@Composable
fun HealthCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        onClick = {},
        modifier = modifier.clip(RoundedCornerShape(12.dp))
    ) {
        Box(modifier = Modifier.padding(2.dp)) {
            content()
        }
    }
}

@Composable
fun Spo2Content(spo2: String) {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Favorite, "SPO2", tint = MaterialTheme.colors.primary, modifier = Modifier.size(12.dp))
        Text("SPO2 Level", fontWeight = FontWeight.Bold, fontSize = 8.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text(spo2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = {
                launchSamsungHealthSpo2(context)
            },
            modifier = Modifier.height(20.dp),
            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface)
        ) {
            Text("MEASURE", fontSize = 6.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun HeartbeatContent(heartRate: String, currentTime: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Favorite, "Heartbeat", tint = MaterialTheme.colors.primary, modifier = Modifier.size(12.dp))
        Text("Heartbeat", fontWeight = FontWeight.Bold, fontSize = 8.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text("$heartRate bpm", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(currentTime, fontSize = 6.sp, color = MaterialTheme.colors.onSurface)
    }
}

@Composable
fun BloodPressureContent() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.FlashOn, "BP", tint = MaterialTheme.colors.primary, modifier = Modifier.size(12.dp))
        Text("Blood Pressure", fontWeight = FontWeight.Bold, fontSize = 8.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text("120/80", fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun FallDetectionContent() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.AccessibilityNew, "Fall", tint = MaterialTheme.colors.primary, modifier = Modifier.size(12.dp))
        Text("Fall Detected", fontWeight = FontWeight.Bold, fontSize = 8.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text("NONE", fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}