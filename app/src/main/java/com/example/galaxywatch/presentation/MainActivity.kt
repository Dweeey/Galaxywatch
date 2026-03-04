package com.example.galaxywatch.presentation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.WindowManager
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

// --- ZEGOCLOUD IMPORTS ---
import com.zegocloud.uikit.prebuilt.call.ZegoUIKitPrebuiltCallConfig
import com.zegocloud.uikit.prebuilt.call.config.ZegoMenuBarButtonName
import com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationConfig
import com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationService
import com.zegocloud.uikit.prebuilt.call.invite.internal.ZegoUIKitPrebuiltCallConfigProvider
import com.zegocloud.uikit.prebuilt.call.invite.widget.ZegoSendCallInvitationButton
import com.zegocloud.uikit.service.defines.ZegoUIKitUser

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.Arrays
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. MULTIPLE PERMISSIONS LAUNCHER (Sensors + Microphone)
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val sensorsGranted = permissions[Manifest.permission.BODY_SENSORS] ?: false
            val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

            if (sensorsGranted) checkHealthConnectPermissions()
            if (!micGranted) {
                Toast.makeText(this, "Mic is required for SOS Calls!", Toast.LENGTH_LONG).show()
            }
        }

        // --- ZEGOCLOUD INITIALIZATION ---
        initZegoCloud()

        setContent {
            // 2. CHECK WHICH PERMISSIONS ARE MISSING ON BOOT
            val missingPermissions = mutableListOf<String>()

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(Manifest.permission.BODY_SENSORS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(Manifest.permission.RECORD_AUDIO)
            }

            // 3. LAUNCH PERMISSION REQUEST IF NEEDED
            if (missingPermissions.isNotEmpty()) {
                LaunchedEffect(Unit) {
                    permissionLauncher.launch(missingPermissions.toTypedArray())
                }
            } else {
                LaunchedEffect(Unit) { checkHealthConnectPermissions() }
            }

            MainAppLogic()
        }
    }

    private fun initZegoCloud() {
        val sharedPrefs = getSharedPreferences("ElderCarePrefs", Context.MODE_PRIVATE)
        val patientId = sharedPrefs.getString("PATIENT_ID", "patient_001") ?: "patient_001"

        val appID: Long = 1279737711L
        val appSign = "50a1c85a028c5224b00ec060afda1e71159d4cfdc124e124c441a981d83cd289"

        val callInvitationConfig = com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationConfig()

        callInvitationConfig.provider = com.zegocloud.uikit.prebuilt.call.invite.internal.ZegoUIKitPrebuiltCallConfigProvider { invitationData ->
            val config = com.zegocloud.uikit.prebuilt.call.ZegoUIKitPrebuiltCallConfig.oneOnOneVoiceCall()

            // --- CLEAN WATCH UI SETTINGS ---
            config.useSpeakerWhenJoining = true
            config.turnOnMicrophoneWhenJoining = true

            // Disable everything that causes "Overflow" or "Floating Window" crashes
            config.topMenuBarConfig.isVisible = false
            config.bottomMenuBarConfig.hideByClick = false
            config.bottomMenuBarConfig.maxCount = 2

            // Only show the two buttons that actually fit on a watch face
            config.bottomMenuBarConfig.buttons = java.util.Arrays.asList(
                com.zegocloud.uikit.prebuilt.call.config.ZegoMenuBarButtonName.HANG_UP_BUTTON,
                com.zegocloud.uikit.prebuilt.call.config.ZegoMenuBarButtonName.TOGGLE_MICROPHONE_BUTTON
            )

            config
        }

        com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationService.init(
            application, appID, appSign, patientId, "Patient ($patientId)", callInvitationConfig
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        ZegoUIKitPrebuiltCallInvitationService.unInit()
    }

    // Health Connect Permissions
    private fun checkHealthConnectPermissions() {
        try {
            val client = HealthConnectClient.getOrCreate(this)
            val permissions = setOf(HealthPermission.getReadPermission(OxygenSaturationRecord::class))
        } catch (e: Exception) {
            Log.e("MainActivity", "Health Connect not available or permissions missing", e)
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
    val scope = rememberCoroutineScope()

    // --- KEEP SCREEN ON ---
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val measureClient = remember { HealthServices.getClient(context).measureClient }
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val healthConnectClient = remember {
        try { HealthConnectClient.getOrCreate(context) }
        catch (e: Exception) { null }
    }

    var heartRate by remember { mutableStateOf("...") }
    var spo2 by remember { mutableStateOf("--") }
    var currentTime by remember { mutableStateOf(getCurrentTime()) }
    var supportsHeartRate by remember { mutableStateOf<Boolean?>(null) }

    var estimatedSys by remember { mutableStateOf(120) }
    var estimatedDia by remember { mutableStateOf(80) }
    var bpStatusColor by remember { mutableStateOf(Color.Green) }
    var bpStatusText by remember { mutableStateOf("Normal") }

    var fallDetected by remember { mutableStateOf(false) }
    var fallMessage by remember { mutableStateOf("Scanning...") }

    val db = remember { Firebase.firestore }

    LaunchedEffect(heartRate, spo2, fallDetected, estimatedSys, estimatedDia) {
        val currentTimeMillis = System.currentTimeMillis()

        val healthData = hashMapOf(
            "heartRate" to heartRate,
            "spo2" to spo2,
            "bloodPressure" to "$estimatedSys/$estimatedDia",
            "bpStatus" to bpStatusText,
            "fallDetected" to fallDetected,
            "statusMessage" to fallMessage,
            "timestamp" to currentTimeMillis
        )

        val patientRef = db.collection("patients").document("patient_001")

        patientRef.set(healthData)
            .addOnFailureListener { Log.w("Firebase", "Error updating status") }

        patientRef.collection("history").document(currentTimeMillis.toString())
            .set(healthData)
            .addOnSuccessListener { Log.d("Firebase", "Historical data point saved!") }
            .addOnFailureListener { e -> Log.w("Firebase", "Error saving history", e) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (healthConnectClient != null) {
                    scope.launch { spo2 = readLatestSpo2(healthConnectClient) }
                } else {
                    spo2 = "No API"
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]

                    val gForce = sqrt(x * x + y * y + z * z) / 9.81

                    if (gForce > 2.5) {
                        fallDetected = true
                        fallMessage = "DETECTED!"
                        scope.launch {
                            delay(5000)
                            fallDetected = false
                            fallMessage = "Scanning..."
                        }
                    }
                }
            }
        }
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = getCurrentTime()
            delay(1000)
        }
    }

    LaunchedEffect(Unit) {
        try {
            val capabilities = measureClient.getCapabilitiesAsync().await()
            supportsHeartRate = capabilities.supportedDataTypesMeasure.contains(DataType.HEART_RATE_BPM)
        } catch (e: Exception) {
            supportsHeartRate = false
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

            val hrInt = hrValue?.toInt()
            heartRate = hrInt?.toString() ?: "--"

            if (hrInt != null) {
                val diff = hrInt - 70
                val estimatedS = 115 + (diff * 0.5).toInt()
                val estimatedD = 75 + (diff * 0.2).toInt()

                estimatedSys = estimatedS
                estimatedDia = estimatedD

                if (estimatedS < 120 && estimatedD < 80) {
                    bpStatusText = "Normal"
                    bpStatusColor = Color.Green
                } else if (estimatedS < 130 && estimatedD < 80) {
                    bpStatusText = "Elevated"
                    bpStatusColor = Color.Yellow
                } else if (estimatedS < 140 || estimatedD < 90) {
                    bpStatusText = "High (Stage 1)"
                    bpStatusColor = Color(0xFFFFA500)
                } else {
                    bpStatusText = "High (Stage 2)"
                    bpStatusColor = Color.Red
                }
            }
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
                    BloodPressureContent(estimatedSys, estimatedDia, bpStatusText, bpStatusColor)
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                HealthCard(modifier = Modifier.weight(0.3f)) {
                    IntentSpo2Content(spo2 = spo2)
                }
                HealthCard(
                    modifier = Modifier.weight(0.3f),
                    backgroundColor = if (fallDetected) Color.Red else MaterialTheme.colors.surface
                ) {
                    FallDetectionContent(isFallDetected = fallDetected, statusMessage = fallMessage)
                }
            }

            // --- ZEGOCLOUD SOS CALL BUTTON (WITH MAGIC WRAPPER) ---
            Spacer(modifier = Modifier.height(4.dp))
            AndroidView(
                modifier = Modifier.size(36.dp),
                factory = { ctx ->
                    // Give the button a fake "Phone" theme so it doesn't crash on Wear OS
                    val themedContext = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_NoActionBar)

                    ZegoSendCallInvitationButton(themedContext).apply {
                        setIsVideoCall(false)

                        // THIS EXACTLY MATCHES YOUR FLUTTER APP CAREGIVER ID
                        setInvitees(listOf(ZegoUIKitUser("5yeapeXNTZcofATleG5ZHZ8siZt2", "Caregiver")))
                    } as android.view.View
                }
            )
        }
    }
}

// --- HELPER FUNCTIONS ---

fun launchSamsungHealthSpo2(context: Context) {
    val packages = listOf("com.sec.android.app.shealth", "com.samsung.android.wear.shealth")
    val deepLink = "shealth://oxygen_saturation"

    for (packageName in packages) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                    intent.setPackage(packageName)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    return
                } catch (e: Exception) {
                    context.startActivity(launchIntent)
                    return
                }
            }
        } catch (e: Exception) { }
    }
    Toast.makeText(context, "Samsung Health app not installed", Toast.LENGTH_SHORT).show()
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
fun HealthCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colors.surface,
    content: @Composable () -> Unit
) {
    Card(
        onClick = {},
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = backgroundColor,
            endBackgroundColor = backgroundColor
        )
    ) {
        Box(modifier = Modifier.padding(2.dp)) { content() }
    }
}

@Composable
fun IntentSpo2Content(spo2: String) {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Favorite, "SPO2", tint = Color.Cyan, modifier = Modifier.size(12.dp))
        Text("SPO2 Level", fontWeight = FontWeight.Bold, fontSize = 8.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text(spo2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = { launchSamsungHealthSpo2(context) },
            modifier = Modifier.height(20.dp),
            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface)
        ) {
            Text("OPEN APP", fontSize = 6.sp, fontWeight = FontWeight.Bold)
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
fun BloodPressureContent(systolic: Int, diastolic: Int, status: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.FlashOn, "BP", tint = MaterialTheme.colors.primary, modifier = Modifier.size(12.dp))
        Text("BP (Est.)", fontWeight = FontWeight.Bold, fontSize = 8.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text("$systolic/$diastolic", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(status, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun FallDetectionContent(isFallDetected: Boolean, statusMessage: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (isFallDetected) Icons.Default.Warning else Icons.Default.AccessibilityNew,
            "Fall",
            tint = if (isFallDetected) Color.White else MaterialTheme.colors.primary,
            modifier = Modifier.size(12.dp)
        )
        Text("Fall Status", fontWeight = FontWeight.Bold, fontSize = 8.sp, color = if (isFallDetected) Color.White else MaterialTheme.colors.onSurface)
        Spacer(modifier = Modifier.height(1.dp))
        Text(statusMessage, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isFallDetected) Color.White else MaterialTheme.colors.onSurface)
    }
}