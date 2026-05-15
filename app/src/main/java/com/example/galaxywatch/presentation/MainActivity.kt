package com.example.galaxywatch.presentation

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import com.example.galaxywatch.ZegoCallManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.galaxywatch.BackgroundVitalsService
import com.example.galaxywatch.SharedVitals
import com.example.galaxywatch.presentation.theme.GalaxyWatchTheme
import com.google.firebase.firestore.FirebaseFirestore
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.zegocloud.uikit.prebuilt.call.invite.widget.ZegoSendCallInvitationButton
import com.zegocloud.uikit.service.defines.ZegoUIKitUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.temporal.ChronoUnit

private val CardBg = Color(0xFF1C1C2E)
private val AccentBlue = Color(0xFF4FC3F7)
private val AccentGreen = Color(0xFF66BB6A)
private val AccentRed = Color(0xFFEF5350)
private val AccentAmber = Color(0xFFFFCA28)
private val AccentPurple = Color(0xFFCE93D8)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFB0BEC5)
private val DividerColor = Color(0xFF2A2A3E)

sealed class BpState {
    object Idle : BpState()
    object SensorError : BpState()
    object TooNoisy : BpState()
    data class Measuring(val secondsLeft: Int) : BpState()
    data class Result(val sbp: Int, val dbp: Int, val category: BpCategory) : BpState()
}

enum class BpCategory(val label: String, val color: Color) {
    Normal("Normal", AccentGreen),
    Elevated("Elevated", AccentAmber),
    High("High BP", AccentRed),
    Crisis("Crisis!", AccentRed),
}

fun classifyBp(sbp: Int, dbp: Int): BpCategory = when {
    sbp >= 180 || dbp >= 120 -> BpCategory.Crisis
    sbp >= 140 || dbp >= 90 -> BpCategory.High
    sbp >= 130 || dbp >= 80 -> BpCategory.Elevated
    else -> BpCategory.Normal
}

class MainActivity : ComponentActivity() {

    private var healthTrackingService: HealthTrackingService? = null
    var captureManager: PpgCaptureManager? = null

    @Volatile
    var latestSpo2FromSdk: String = "--"

    @Volatile
    var latestSpo2SdkTimestamp: Long = 0L

    private var spo2Tracker: HealthTracker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { perms ->
            if (hasBodySensorsPermission()) {
                startVitalsServiceIfPossible()
            }

            if (perms[Manifest.permission.RECORD_AUDIO] == false) {
                Toast.makeText(this, "Mic required for SOS calls!", Toast.LENGTH_LONG).show()
            }
        }

        initZegoCloud()
        initSamsungHealthForUI()

        setContent {
            val missing = buildList {
                if (
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BODY_SENSORS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.BODY_SENSORS)
                }

                if (
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.RECORD_AUDIO)
                }

                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            if (missing.isNotEmpty()) {
                LaunchedEffect(Unit) { permissionLauncher.launch(missing.toTypedArray()) }
            } else {
                LaunchedEffect(Unit) { startVitalsServiceIfPossible() }
            }

            GalaxyWatchTheme { WearApp(this) }
        }
    }

    private fun initSamsungHealthForUI() {
        val listener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                Log.d("UI_SENSOR", "Samsung Health connected for UI")
                captureManager = PpgCaptureManager(healthTrackingService!!)
            }

            override fun onConnectionEnded() {
                Log.d("UI_SENSOR", "Samsung Health disconnected")
            }

            override fun onConnectionFailed(e: HealthTrackerException?) {
                Log.e("UI_SENSOR", "Samsung Health connection failed: ${e?.message}")
            }
        }

        healthTrackingService = HealthTrackingService(listener, this)
        healthTrackingService?.connectService()
    }

    internal fun startSpo2SdkTracker() {
        val trackerType: HealthTrackerType? = try {
            HealthTrackerType::class.java
                .getField("SPO2_CONTINUOUS")
                .get(null) as? HealthTrackerType
        } catch (_: NoSuchFieldException) {
            try {
                HealthTrackerType::class.java
                    .getField("SPO2")
                    .get(null) as? HealthTrackerType
            } catch (_: NoSuchFieldException) {
                Log.w("SPO2_SDK", "No SPO2 tracker available on this SDK")
                null
            }
        }

        if (trackerType == null) return

        try {
            spo2Tracker = healthTrackingService?.getHealthTracker(trackerType)
            spo2Tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataList: List<DataPoint>) {
                    for (data in dataList) {
                        try {
                            val value = data.getValue(ValueKey.SpO2Set.SPO2) as? Int ?: continue
                            if (value > 0) {
                                latestSpo2FromSdk = "$value%"
                                latestSpo2SdkTimestamp = System.currentTimeMillis()
                                Log.d("SPO2_SDK", "SDK reading: $value%")
                            }
                        } catch (e: Exception) {
                            Log.e("SPO2_SDK", "Parse error: ${e.message}")
                        }
                    }
                }

                override fun onFlushCompleted() = Unit

                override fun onError(e: HealthTracker.TrackerError?) {
                    Log.e("SPO2_SDK", "Tracker error: $e")
                }
            })
            Log.d("SPO2_SDK", "SpO2 tracker started: $trackerType")
        } catch (e: Exception) {
            Log.w("SPO2_SDK", "Could not start SpO2 tracker: ${e.message}")
        }
    }

    internal fun stopSpo2SdkTracker() {
        spo2Tracker?.unsetEventListener()
        spo2Tracker = null
        Log.d("SPO2_SDK", "SpO2 tracker stopped")
    }

    private fun initZegoCloud() {
        val initialized = ZegoCallManager.ensureInitialized(applicationContext)
        Log.d("ZEGO_INIT", "Call service initialized: $initialized")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSpo2SdkTracker()
        captureManager?.release()
        healthTrackingService?.disconnectService()
    }

    private fun hasBodySensorsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startVitalsServiceIfPossible() {
        if (!hasBodySensorsPermission()) return

        checkHealthConnectPermissions()
        ensureFullScreenIntentAccess()

        if (!BackgroundVitalsService.isRunning) {
            startForegroundService(Intent(this, BackgroundVitalsService::class.java))
        }
    }

    private fun ensureFullScreenIntentAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            if (!notificationManager.canUseFullScreenIntent()) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }

    private fun checkHealthConnectPermissions() {
        try {
            HealthConnectClient.getOrCreate(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "Health Connect unavailable", e)
        }
    }
}

@Composable
fun WearApp(activity: MainActivity) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val win = (context as? Activity)?.window
        win?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val healthClient = remember {
        try {
            HealthConnectClient.getOrCreate(context)
        } catch (_: Exception) {
            null
        }
    }

    val heartRate by SharedVitals.heartRate.collectAsState()
    val fallDetected by SharedVitals.fallDetected.collectAsState()
    val fallMessage by SharedVitals.fallMessage.collectAsState()

    var bpState by remember { mutableStateOf<BpState>(BpState.Idle) }
    var spo2 by remember { mutableStateOf("--") }
    var isTrackingSpO2 by remember { mutableStateOf(false) }

    var isLogging by remember { mutableStateOf(false) }
    var loggingCountdown by remember { mutableStateOf(0) }

    var rawRecordingLabel by remember { mutableStateOf<String?>(null) }
    var rawRecordingCountdown by remember { mutableStateOf(0) }

    fun startRawRecording(label: String) {
        if (rawRecordingCountdown > 0) {
            Toast.makeText(context, "Recording already in progress...", Toast.LENGTH_SHORT).show()
            return
        }

        context.startService(
            Intent(context, BackgroundVitalsService::class.java).apply {
                action = BackgroundVitalsService.ACTION_START_RAW_RECORDING
                putExtra(BackgroundVitalsService.EXTRA_RAW_LABEL, label)
                putExtra(BackgroundVitalsService.EXTRA_RAW_DURATION_MS, 30_000L)
            }
        )

        rawRecordingLabel = label
        rawRecordingCountdown = 30

        scope.launch {
            for (i in 30 downTo 1) {
                rawRecordingCountdown = i
                delay(1000L)
            }

            rawRecordingCountdown = 0
            rawRecordingLabel = null

            Toast.makeText(context, "Raw motion CSV saved", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && healthClient != null && !isTrackingSpO2) {
                scope.launch {
                    spo2 = readLatestSpo2(healthClient)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Scaffold(
        timeText = { TimeText() },
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 4.dp,
                        end = 4.dp,
                        top = 24.dp,
                        bottom = 72.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    VitalCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Favorite,
                        iconTint = AccentRed,
                        label = "Heart Rate"
                    ) {
                        Text(
                            text = heartRate,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "bpm",
                            fontSize = 9.sp,
                            color = TextSecondary
                        )
                    }

                    BpCard(
                        modifier = Modifier.weight(1f),
                        bpState = bpState,
                        onTap = {
                            if (bpState is BpState.Measuring) {
                                Toast.makeText(context, "Already measuring...", Toast.LENGTH_SHORT)
                                    .show()
                                return@BpCard
                            }

                            if (activity.captureManager == null) {
                                Toast.makeText(
                                    context,
                                    "Sensor connecting, please wait...",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@BpCard
                            }

                            scope.launch {
                                runBpMeasurement(
                                    captureManager = activity.captureManager!!,
                                    context = context,
                                    onState = { bpState = it }
                                )
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    VitalCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Favorite,
                        iconTint = AccentBlue,
                        label = "SpO2"
                    ) {
                        Text(
                            text = spo2,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        if (isTrackingSpO2) {
                            Text(
                                text = "Syncing...",
                                fontSize = 8.sp,
                                color = TextSecondary
                            )
                        }

                        Spacer(Modifier.height(3.dp))

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isTrackingSpO2) AccentBlue.copy(alpha = 0.2f)
                                    else DividerColor
                                )
                                .clickable(enabled = !isTrackingSpO2) {
                                    val trackStartMs = System.currentTimeMillis()
                                    activity.latestSpo2FromSdk = "--"
                                    activity.latestSpo2SdkTimestamp = 0L
                                    isTrackingSpO2 = true
                                    spo2 = "..."

                                    activity.startSpo2SdkTracker()
                                    launchSamsungHealthSpo2(activity)

                                    scope.launch {
                                        var foundValue: String? = null
                                        var source = ""

                                        for (attempt in 1..180) {
                                            delay(1_000L)

                                            val sdkValue = activity.latestSpo2FromSdk
                                            val sdkTimestamp = activity.latestSpo2SdkTimestamp

                                            if (sdkValue != "--" && sdkTimestamp > trackStartMs) {
                                                foundValue = sdkValue
                                                source = "SDK"
                                                break
                                            }

                                            if (healthClient != null) {
                                                val hcResult = readLatestSpo2AfterTime(
                                                    healthClient,
                                                    Instant.ofEpochMilli(trackStartMs)
                                                )
                                                if (hcResult != null) {
                                                    foundValue = hcResult
                                                    source = "Health Connect"
                                                    break
                                                }
                                            }
                                        }

                                        activity.stopSpo2SdkTracker()
                                        isTrackingSpO2 = false

                                        if (foundValue != null) {
                                            val finalValue = foundValue ?: "--"
                                            spo2 = finalValue
                                            Log.d("SPO2", "UI updated from $source: $finalValue")
                                        } else {
                                            val lastKnown = if (healthClient != null) {
                                                readLatestSpo2(healthClient)
                                            } else {
                                                "--"
                                            }
                                            spo2 = if (lastKnown == "No Data") "--" else lastKnown
                                            Log.d("SPO2", "Poll timed out - last known: $spo2")
                                        }

                                        context.startService(
                                            Intent(
                                                context,
                                                BackgroundVitalsService::class.java
                                            ).apply {
                                                action = BackgroundVitalsService.ACTION_AUTO_RETURN
                                                putExtra("delay_ms", 0L)
                                            }
                                        )
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isTrackingSpO2) "..." else "MEASURE",
                                fontSize = 7.sp,
                                color = AccentBlue,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    VitalCard(
                        modifier = Modifier.weight(1f),
                        icon = if (fallDetected) Icons.Default.Warning
                        else Icons.Default.AccessibilityNew,
                        iconTint = if (fallDetected) AccentRed else AccentGreen,
                        label = "Fall Status",
                        bgColor = if (fallDetected) AccentRed.copy(alpha = 0.2f) else CardBg,
                        borderColor = if (fallDetected) AccentRed else DividerColor
                    ) {
                        Text(
                            text = fallMessage,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (fallDetected) AccentRed else AccentGreen,
                            textAlign = TextAlign.Center
                        )
                    }
                }

//                RawMotionRecorderCard(
//                    activeLabel = rawRecordingLabel,
//                    countdown = rawRecordingCountdown,
//                    onStill = { startRawRecording("still") },
//                    onMove = { startRawRecording("hand_movement") },
//                    onFall = { startRawRecording("fall_like") }
//                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "SOS CALL", fontSize = 7.sp, color = TextSecondary)
                Spacer(Modifier.height(2.dp))
                AndroidView(
                    modifier = Modifier.size(40.dp),
                    factory = { ctx ->
                        val themed = ContextThemeWrapper(
                            ctx,
                            android.R.style.Theme_DeviceDefault_NoActionBar
                        )
                        ZegoSendCallInvitationButton(themed).apply {
                            setIsVideoCall(false)
                            setInvitees(
                                listOf(
                                    ZegoUIKitUser(
                                        "5yeapeXNTZcofATleG5ZHZ8siZt2",
                                        "Caregiver"
                                    )
                                )
                            )
                        } as android.view.View
                    }
                )
            }
        }
    }
}

@Composable
fun RawMotionRecorderCard(
    activeLabel: String?,
    countdown: Int,
    onStill: () -> Unit,
    onMove: () -> Unit,
    onFall: () -> Unit
) {
    val isRecording = countdown > 0

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isRecording) AccentPurple.copy(alpha = 0.15f) else CardBg)
            .border(
                1.dp,
                if (isRecording) AccentPurple else DividerColor,
                RoundedCornerShape(12.dp)
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Raw Motion Recorder",
                fontSize = 8.sp,
                color = TextSecondary
            )

            Text(
                text = if (isRecording) {
                    "Recording $activeLabel... ${countdown}s"
                } else {
                    "Select motion type"
                },
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = if (isRecording) AccentPurple else TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(5.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RawRecordButton(
                    modifier = Modifier.weight(1f),
                    text = "STILL",
                    enabled = !isRecording,
                    color = AccentGreen,
                    onClick = onStill
                )

                RawRecordButton(
                    modifier = Modifier.weight(1f),
                    text = "MOVE",
                    enabled = !isRecording,
                    color = AccentBlue,
                    onClick = onMove
                )

                RawRecordButton(
                    modifier = Modifier.weight(1f),
                    text = "FALL",
                    enabled = !isRecording,
                    color = AccentRed,
                    onClick = onFall
                )
            }
        }
    }
}

@Composable
fun RawRecordButton(
    modifier: Modifier = Modifier,
    text: String,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (enabled) color.copy(alpha = 0.9f)
                else DividerColor
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 4.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun GestureLoggerCard(
    isLogging: Boolean,
    countdown: Int,
    onStartStop: () -> Unit
) {
    val borderColor = if (isLogging) AccentPurple else DividerColor
    val bgColor = if (isLogging) AccentPurple.copy(alpha = 0.15f) else CardBg

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "Gesture Logger", fontSize = 8.sp, color = TextSecondary)
                if (isLogging) {
                    Text(
                        text = "Recording... ${countdown}s left",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentPurple
                    )
                    Text(
                        text = "Do your arm swing / gesture",
                        fontSize = 7.sp,
                        color = TextSecondary
                    )
                } else {
                    Text(text = "Tap to record gestures", fontSize = 9.sp, color = TextSecondary)
                    Text(text = "for AI training data", fontSize = 7.sp, color = TextSecondary)
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isLogging) AccentRed else AccentPurple)
                    .clickable { onStartStop() }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isLogging) "STOP" else "START",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
        }
    }
}

private suspend fun runBpMeasurement(
    captureManager: PpgCaptureManager,
    context: Context,
    onState: (BpState) -> Unit
) {
    captureManager.startRecording()

    for (i in 30 downTo 1) {
        onState(BpState.Measuring(i))
        delay(1000L)
    }

    val recording = captureManager.stopRecording()
    val rawData = recording.samples
    val sampleRate = recording.sampleRate

    Log.d("BP_MEASURE", "Raw points: ${rawData.size} at ${sampleRate}Hz")

    if (rawData.size < 50) {
        onState(BpState.SensorError)
        Log.e("BP_MEASURE", "Not enough data: ${rawData.size} points")
        return
    }

    exportDataToCsv(context, rawData)

    val cropSamples = (sampleRate * 2).toInt()
    val cropped = if (rawData.size > cropSamples) {
        rawData.subList(cropSamples, rawData.size)
    } else {
        rawData
    }

    val filtered = ArrayList<SensorReading>(cropped.size)
    var smoothed = cropped[0].value
    var baseline = cropped[0].value

    for (r in cropped) {
        smoothed += 0.3f * (r.value - smoothed)
        baseline += 0.02f * (smoothed - baseline)
        filtered.add(SensorReading(r.timestamp, smoothed - baseline))
    }

    val minPeakDistanceMs = 333L
    val variance = filtered.sumOf { (it.value * it.value).toDouble() } / filtered.size
    val threshold = Math.sqrt(variance).toFloat() * 0.3f

    val peaks = mutableListOf<Long>()
    var lastPeak = 0L

    for (i in 1 until filtered.size - 1) {
        val prev = filtered[i - 1].value
        val current = filtered[i].value
        val next = filtered[i + 1].value
        val isPeak = current > prev &&
                current >= next &&
                current > threshold &&
                (filtered[i].timestamp - lastPeak) > minPeakDistanceMs

        if (isPeak) {
            peaks.add(filtered[i].timestamp)
            lastPeak = filtered[i].timestamp
        }
    }

    Log.d("BP_MEASURE", "Peaks: ${peaks.size}")

    if (peaks.size < 5) {
        onState(BpState.TooNoisy)
        Log.w("BP_MEASURE", "Too few peaks (${peaks.size}) - signal too noisy")
        return
    }

    val rawIbis = (1 until peaks.size).map { (peaks[it] - peaks[it - 1]).toFloat() }
    val sortedIbis = rawIbis.sorted()
    val medianIbi = sortedIbis[sortedIbis.size / 2]
    val validIbis = sortedIbis.filter {
        it >= medianIbi * 0.75f && it <= medianIbi * 1.25f
    }

    if (validIbis.isEmpty()) {
        onState(BpState.TooNoisy)
        return
    }

    val meanIbiMs = validIbis.average()
    val ibiSec = (meanIbiMs / 1000.0).toFloat()
    val hr = (60f / ibiSec).toInt()
    val hrv = Math.sqrt(
        validIbis.map { (it - meanIbiMs) * (it - meanIbiMs) }.average()
    ).toFloat()
    val amplitude = validIbis.indices.map { i ->
        filtered.firstOrNull { it.timestamp == peaks[i] }?.value ?: 0f
    }.average().toFloat()

    val sbpRaw = ((0.7928 * hr) + (-4.1396 * ibiSec) + 73.8357 - (hrv * 0.05)).toInt()
    val dbpRaw =
        ((-0.7776 * hr) + (-110.2632 * ibiSec) + (0.000926 * amplitude) + 221.5346).toInt()

    val sbp = (sbpRaw - 5).coerceIn(60, 220)
    val dbp = dbpRaw.coerceIn(40, 140)

    Log.d("BP_MEASURE", "HR=${hr}bpm IBI=${ibiSec}s HRV=${hrv}ms -> SBP=$sbp DBP=$dbp")

    val category = classifyBp(sbp, dbp)
    onState(BpState.Result(sbp, dbp, category))
    saveBpToFirebase(context, sbp, dbp, category)
}

fun saveBpToFirebase(context: Context, sbp: Int, dbp: Int, category: BpCategory) {
    val prefs = context.getSharedPreferences("ElderCarePrefs", Context.MODE_PRIVATE)
    val patientId = prefs.getString("PATIENT_ID", "patient_001") ?: "patient_001"
    val db = FirebaseFirestore.getInstance()

    val bpString = "$sbp/$dbp"
    val timestamp = System.currentTimeMillis()

    val liveUpdates = mapOf("bloodPressure" to bpString, "bpStatus" to category.label)
    val historyData = mapOf(
        "bloodPressure" to bpString,
        "bpStatus" to category.label,
        "systolic" to sbp,
        "diastolic" to dbp,
        "timestamp" to timestamp
    )

    val patientRef = db.collection("patients").document(patientId)
    patientRef.set(liveUpdates, com.google.firebase.firestore.SetOptions.merge())
        .addOnFailureListener { e ->
            Log.e("BP_FIREBASE", "Live update failed: ${e.message}")
        }

    patientRef.collection("blood_pressure").document(timestamp.toString()).set(historyData)
        .addOnSuccessListener {
            Log.d("BP_FIREBASE", "Saved BP: $bpString (${category.label})")
        }
        .addOnFailureListener { e ->
            Log.e("BP_FIREBASE", "History save failed: ${e.message}")
        }
}

@Composable
fun BpCard(modifier: Modifier, bpState: BpState, onTap: () -> Unit) {
    val borderColor = when (bpState) {
        is BpState.Result -> bpState.category.color
        is BpState.Measuring -> AccentBlue
        is BpState.TooNoisy -> AccentAmber
        is BpState.SensorError -> AccentRed
        else -> DividerColor
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onTap() }
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.MonitorHeart,
                contentDescription = "BP",
                tint = AccentBlue,
                modifier = Modifier.size(12.dp)
            )
            Text(text = "Blood Pressure", fontSize = 7.sp, color = TextSecondary)
            Spacer(Modifier.height(3.dp))

            when (bpState) {
                is BpState.Idle -> {
                    Text("Tap to", fontSize = 9.sp, color = TextSecondary)
                    Text(
                        "Measure",
                        fontSize = 10.sp,
                        color = AccentBlue,
                        fontWeight = FontWeight.Bold
                    )
                }

                is BpState.Measuring -> {
                    Text(
                        text = "${bpState.secondsLeft}s",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentBlue
                    )
                    Text("Hold still", fontSize = 8.sp, color = TextSecondary)
                }

                is BpState.Result -> {
                    Text(
                        text = "${bpState.sbp}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = bpState.category.color
                    )
                    Text(text = "${bpState.dbp} mmHg", fontSize = 10.sp, color = TextSecondary)
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(bpState.category.color.copy(alpha = 0.2f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = bpState.category.label,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = bpState.category.color
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("Tap to retry", fontSize = 7.sp, color = TextSecondary)
                }

                is BpState.TooNoisy -> {
                    Text(
                        "Too Noisy",
                        fontSize = 9.sp,
                        color = AccentAmber,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Move less,", fontSize = 8.sp, color = TextSecondary)
                    Text("tap retry", fontSize = 8.sp, color = TextSecondary)
                }

                is BpState.SensorError -> {
                    Text("Sensor", fontSize = 9.sp, color = AccentRed, fontWeight = FontWeight.Bold)
                    Text("Error", fontSize = 9.sp, color = AccentRed)
                    Text("Tap retry", fontSize = 7.sp, color = TextSecondary)
                }
            }
        }
    }
}

@Composable
fun VitalCard(
    modifier: Modifier,
    icon: ImageVector,
    iconTint: Color,
    label: String,
    bgColor: Color = CardBg,
    borderColor: Color = DividerColor,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, tint = iconTint, modifier = Modifier.size(12.dp))
            Text(text = label, fontSize = 7.sp, color = TextSecondary)
            Spacer(Modifier.height(3.dp))
            content()
        }
    }
}

fun exportDataToCsv(context: Context, data: List<SensorReading>) {
    if (data.isEmpty()) return

    try {
        val file = File(
            context.getExternalFilesDir(null),
            "PPG_RawData_${System.currentTimeMillis()}.csv"
        )
        FileWriter(file).use { writer ->
            writer.append("Timestamp,PPG_Value\n")
            for (r in data) {
                writer.append("${r.timestamp},${r.value}\n")
            }
        }
        Log.d("CSV_EXPORT", "Saved: ${file.absolutePath}")
    } catch (e: Exception) {
        Log.e("CSV_EXPORT", "Failed to export CSV: ${e.message}")
    }
}

fun launchSamsungHealthSpo2(context: Context) {
    val pkg = "com.samsung.android.wear.shealth"
    val launchFlags = if (context is Activity) {
        Intent.FLAG_ACTIVITY_CLEAR_TOP
    } else {
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    val actions = listOf(
        "com.samsung.android.wear.shealth.intent.action.VIEW_SPO2_MEASURE",
        "com.samsung.android.wear.shealth.intent.action.VIEW_SPO2_MAIN"
    )

    for (action in actions) {
        try {
            context.startActivity(
                Intent(action).apply {
                    setPackage(pkg)
                    addFlags(launchFlags)
                }
            )
            return
        } catch (_: Exception) {
            Unit
        }
    }

    val activities = listOf(
        "$pkg.app.spo2.view.measure.Spo2MeasuringActivity",
        "$pkg.app.spo2.view.Spo2Activity"
    )

    for (activityClass in activities) {
        try {
            context.startActivity(
                Intent().apply {
                    setClassName(pkg, activityClass)
                    addFlags(launchFlags)
                }
            )
            return
        } catch (_: Exception) {
            Unit
        }
    }

    context.packageManager.getLaunchIntentForPackage(pkg)
        ?.apply { addFlags(launchFlags) }
        ?.let { context.startActivity(it) }
        ?: Toast.makeText(context, "Samsung Health not found", Toast.LENGTH_SHORT).show()
}

suspend fun readLatestSpo2(client: HealthConnectClient): String = try {
    val resp: ReadRecordsResponse<OxygenSaturationRecord> = client.readRecords(
        ReadRecordsRequest(
            recordType = OxygenSaturationRecord::class,
            timeRangeFilter = TimeRangeFilter.between(
                Instant.now().minus(24, ChronoUnit.HOURS),
                Instant.now()
            ),
            ascendingOrder = false,
            pageSize = 1
        )
    )

    resp.records.firstOrNull()
        ?.let { "${it.percentage.value.toInt()}%" }
        ?: "No Data"
} catch (e: Exception) {
    Log.e("SPO2", "Health Connect error: ${e.message}", e)
    "Perm needed"
}

suspend fun readLatestSpo2AfterTime(
    client: HealthConnectClient,
    after: Instant
): String? = try {
    val resp: ReadRecordsResponse<OxygenSaturationRecord> = client.readRecords(
        ReadRecordsRequest(
            recordType = OxygenSaturationRecord::class,
            timeRangeFilter = TimeRangeFilter.between(after, Instant.now()),
            ascendingOrder = false,
            pageSize = 1
        )
    )

    resp.records.firstOrNull()
        ?.let { "${it.percentage.value.toInt()}%" }
} catch (e: Exception) {
    Log.e("SPO2", "Poll error: ${e.message}", e)
    null
}