package com.example.galaxywatch.presentation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material.*
import com.example.galaxywatch.BackgroundVitalsService
import com.example.galaxywatch.SharedVitals // --- NEW: Import the shared memory object! ---
import com.example.galaxywatch.presentation.theme.GalaxyWatchTheme

// --- ZEGOCLOUD IMPORTS ---
import com.zegocloud.uikit.prebuilt.call.ZegoUIKitPrebuiltCallConfig
import com.zegocloud.uikit.prebuilt.call.config.ZegoMenuBarButtonName
import com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationConfig
import com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationService
import com.zegocloud.uikit.prebuilt.call.invite.internal.ZegoUIKitPrebuiltCallConfigProvider
import com.zegocloud.uikit.prebuilt.call.invite.widget.ZegoSendCallInvitationButton
import com.zegocloud.uikit.service.defines.ZegoUIKitUser

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. MULTIPLE PERMISSIONS LAUNCHER
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val sensorsGranted = permissions[Manifest.permission.BODY_SENSORS] ?: false
            val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

            if (sensorsGranted) {
                checkHealthConnectPermissions()
                // START BACKGROUND SERVICE ONCE PERMISSIONS GRANTED
                val serviceIntent = Intent(this, BackgroundVitalsService::class.java)
                startForegroundService(serviceIntent)
            }
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

            // 3. LAUNCH PERMISSION REQUEST IF NEEDED, OTHERWISE START SERVICE
            if (missingPermissions.isNotEmpty()) {
                LaunchedEffect(Unit) {
                    permissionLauncher.launch(missingPermissions.toTypedArray())
                }
            } else {
                LaunchedEffect(Unit) {
                    checkHealthConnectPermissions()
                    // START BACKGROUND SERVICE ON BOOT
                    val serviceIntent = Intent(this@MainActivity, BackgroundVitalsService::class.java)
                    startForegroundService(serviceIntent)
                }
            }

            MainAppLogic()
        }
    }

    private fun initZegoCloud() {
        val sharedPrefs = getSharedPreferences("ElderCarePrefs", Context.MODE_PRIVATE)
        val patientId = sharedPrefs.getString("PATIENT_ID", "patient_001") ?: "patient_001"
        val appID: Long = 1279737711L
        val appSign = "50a1c85a028c5224b00ec060afda1e71159d4cfdc124e124c441a981d83cd289"

        val callInvitationConfig = ZegoUIKitPrebuiltCallInvitationConfig()

        // --- NEW: OFFLINE PUSH NOTIFICATION CONFIGURATION ---
        val notificationConfig = com.zegocloud.uikit.prebuilt.call.config.ZegoNotificationConfig()
        notificationConfig.sound = "zego_uikit_sound_call"
        notificationConfig.channelID = "CallInvitation"
        notificationConfig.channelName = "CallInvitation"

        // Attach the push config to your main config
        callInvitationConfig.notificationConfig = notificationConfig
        // ----------------------------------------------------

        callInvitationConfig.provider = ZegoUIKitPrebuiltCallConfigProvider { _ ->
            val config = ZegoUIKitPrebuiltCallConfig.oneOnOneVoiceCall()
            config.useSpeakerWhenJoining = true
            config.turnOnMicrophoneWhenJoining = true
            config.topMenuBarConfig.isVisible = false
            config
        }

        ZegoUIKitPrebuiltCallInvitationService.init(
            application, appID, appSign, patientId, "Patient ($patientId)", callInvitationConfig
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        ZegoUIKitPrebuiltCallInvitationService.unInit()
    }

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

    val healthConnectClient = remember {
        try { HealthConnectClient.getOrCreate(context) }
        catch (e: Exception) { null }
    }

    // --- NEW: READ FROM SHARED MEMORY INSTEAD OF SENSORS ---
    val heartRate by SharedVitals.heartRate.collectAsState()
    val estimatedSys by SharedVitals.sys.collectAsState()
    val estimatedDia by SharedVitals.dia.collectAsState()
    val bpStatusText by SharedVitals.bpStatusText.collectAsState()
    val fallDetected by SharedVitals.fallDetected.collectAsState()
    val fallMessage by SharedVitals.fallMessage.collectAsState()

    // Calculate color based on the text string
    val bpStatusColor = when(bpStatusText) {
        "Normal" -> Color.Green
        "Elevated" -> Color.Yellow
        "High (Stage 1)" -> Color(0xFFFFA500)
        else -> Color.Red
    }

    var spo2 by remember { mutableStateOf("--") }
    var currentTime by remember { mutableStateOf(getCurrentTime()) }

    // Read SpO2 ONLY when the screen wakes up
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (healthConnectClient != null) {
                    scope.launch { spo2 = readLatestSpo2(healthConnectClient) }
                } else {
                    spo2 = ""
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Update the clock every second
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = getCurrentTime()
            delay(1000)
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

            // --- ZEGOCLOUD SOS CALL BUTTON ---
            Spacer(modifier = Modifier.height(4.dp))
            AndroidView(
                modifier = Modifier.size(36.dp),
                factory = { ctx ->
                    val themedContext = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_NoActionBar)
                    ZegoSendCallInvitationButton(themedContext).apply {
                        setIsVideoCall(false)
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