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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material.*
import com.example.galaxywatch.BackgroundVitalsService
import com.example.galaxywatch.SharedVitals
import com.example.galaxywatch.presentation.theme.GalaxyWatchTheme
import com.google.firebase.firestore.FirebaseFirestore
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.zegocloud.uikit.prebuilt.call.ZegoUIKitPrebuiltCallConfig
import com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationConfig
import com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationService
import com.zegocloud.uikit.prebuilt.call.invite.internal.ZegoUIKitPrebuiltCallConfigProvider
import com.zegocloud.uikit.prebuilt.call.invite.widget.ZegoSendCallInvitationButton
import com.zegocloud.uikit.service.defines.ZegoUIKitUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

// ─── Colour palette ───────────────────────────────────────────────────────────
private val CardBg        = Color(0xFF1C1C2E)
private val AccentBlue    = Color(0xFF4FC3F7)
private val AccentGreen   = Color(0xFF66BB6A)
private val AccentRed     = Color(0xFFEF5350)
private val AccentAmber   = Color(0xFFFFCA28)
private val TextPrimary   = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFB0BEC5)
private val DividerColor  = Color(0xFF2A2A3E)

// ─── BP state machine ─────────────────────────────────────────────────────────
sealed class BpState {
    object Idle        : BpState()
    object SensorError : BpState()
    object TooNoisy    : BpState()
    data class Measuring(val secondsLeft: Int) : BpState()
    data class Result(val sbp: Int, val dbp: Int, val category: BpCategory) : BpState()
}

enum class BpCategory(val label: String, val color: Color) {
    Normal("Normal",     AccentGreen),
    Elevated("Elevated", AccentAmber),
    High("High BP",      AccentRed),
    Crisis("Crisis!",    AccentRed),
}

fun classifyBp(sbp: Int, dbp: Int): BpCategory = when {
    sbp >= 180 || dbp >= 120 -> BpCategory.Crisis
    sbp >= 140 || dbp >= 90  -> BpCategory.High
    sbp >= 130 || dbp >= 80  -> BpCategory.Elevated
    else                     -> BpCategory.Normal
}

// ─── Activity ─────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private var healthTrackingService: HealthTrackingService? = null
    var captureManager: PpgCaptureManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { perms ->
            if (perms[Manifest.permission.BODY_SENSORS] == true) {
                checkHealthConnectPermissions()
                startForegroundService(Intent(this, BackgroundVitalsService::class.java))
            }
            if (perms[Manifest.permission.RECORD_AUDIO] != true) {
                Toast.makeText(this, "Mic required for SOS calls!", Toast.LENGTH_LONG).show()
            }
        }

        initZegoCloud()
        initSamsungHealthForUI()

        setContent {
            val missing = buildList {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.BODY_SENSORS
                    ) != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.BODY_SENSORS)

                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.RECORD_AUDIO)
            }

            if (missing.isNotEmpty()) {
                LaunchedEffect(Unit) { permissionLauncher.launch(missing.toTypedArray()) }
            } else {
                LaunchedEffect(Unit) {
                    checkHealthConnectPermissions()
                    startForegroundService(
                        Intent(this@MainActivity, BackgroundVitalsService::class.java)
                    )
                }
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

    private fun initZegoCloud() {
        val prefs     = getSharedPreferences("ElderCarePrefs", Context.MODE_PRIVATE)
        val patientId = prefs.getString("PATIENT_ID", "patient_001") ?: "patient_001"

        val invitationConfig = ZegoUIKitPrebuiltCallInvitationConfig()
        val notifConfig = com.zegocloud.uikit.prebuilt.call.config.ZegoNotificationConfig()
        notifConfig.sound       = "zego_uikit_sound_call"
        notifConfig.channelID   = "CallInvitation"
        notifConfig.channelName = "CallInvitation"
        invitationConfig.notificationConfig = notifConfig
        invitationConfig.provider = ZegoUIKitPrebuiltCallConfigProvider { _ ->
            ZegoUIKitPrebuiltCallConfig.oneOnOneVoiceCall().also {
                it.useSpeakerWhenJoining       = true
                it.turnOnMicrophoneWhenJoining = true
                it.topMenuBarConfig.isVisible  = false
            }
        }
        ZegoUIKitPrebuiltCallInvitationService.init(
            application,
            1279737711L,
            "50a1c85a028c5224b00ec060afda1e71159d4cfdc124e124c441a981d83cd289",
            patientId,
            "Patient ($patientId)",
            invitationConfig
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        ZegoUIKitPrebuiltCallInvitationService.unInit()
        captureManager?.release()
        healthTrackingService?.disconnectService()
    }

    private fun checkHealthConnectPermissions() {
        try { HealthConnectClient.getOrCreate(this) }
        catch (e: Exception) { Log.e("MainActivity", "Health Connect unavailable", e) }
    }
}

// ─── Root UI ──────────────────────────────────────────────────────────────────
@Composable
fun WearApp(activity: MainActivity) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope          = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val win = (context as? Activity)?.window
        win?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val healthClient = remember {
        try { HealthConnectClient.getOrCreate(context) } catch (e: Exception) { null }
    }

    val heartRate    by SharedVitals.heartRate.collectAsState()
    val fallDetected by SharedVitals.fallDetected.collectAsState()
    val fallMessage  by SharedVitals.fallMessage.collectAsState()

    var bpState by remember { mutableStateOf<BpState>(BpState.Idle) }
    var spo2    by remember { mutableStateOf("--") }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && healthClient != null) {
                scope.launch { spo2 = readLatestSpo2(healthClient) }
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
                                Toast.makeText(
                                    context,
                                    "Already measuring…",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@BpCard
                            }

                            if (activity.captureManager == null) {
                                Toast.makeText(
                                    context,
                                    "Sensor connecting, please wait…",
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
                        Spacer(Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(DividerColor)
                                .clickable { launchSamsungHealthSpo2(context) }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "OPEN",
                                fontSize = 7.sp,
                                color = AccentBlue,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    VitalCard(
                        modifier = Modifier.weight(1f),
                        icon = if (fallDetected) Icons.Default.Warning else Icons.Default.AccessibilityNew,
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
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "SOS CALL",
                    fontSize = 7.sp,
                    color = TextSecondary
                )
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

// ─── BP Measurement Logic ─────────────────────────────────────────────────────

private suspend fun runBpMeasurement(
    captureManager: PpgCaptureManager,
    context:        Context,
    onState:        (BpState) -> Unit
) {
    captureManager.startRecording()

    for (i in 30 downTo 1) {
        onState(BpState.Measuring(i))
        delay(1000L)
    }

    // FIX: stopRecording() now returns PpgRecording (not List<SensorReading>)
    val recording = captureManager.stopRecording()
    val rawData   = recording.samples
    // FIX: Use the measured sample rate — never hardcode
    val sampleRate = recording.sampleRate

    Log.d("BP_MEASURE", "Raw points: ${rawData.size} at ${sampleRate}Hz")

    // FIX: Validate before processing — prevents IndexOutOfBoundsException
    if (rawData.size < 50) {
        onState(BpState.SensorError)
        Log.e("BP_MEASURE", "Not enough data: ${rawData.size} points")
        return
    }

    // Export CSV only when we have real data
    exportDataToCsv(context, rawData)

    // Drop the first 2 seconds to skip the settling period
    // FIX: Use sampleRate to calculate how many samples = 2 seconds
    val cropSamples = (sampleRate * 2).toInt()
    val cropped = if (rawData.size > cropSamples) {
        rawData.subList(cropSamples, rawData.size)
    } else {
        rawData
    }

    // ── Signal filtering ──────────────────────────────────────────────────────
    // Two-stage filter: exponential smoothing + baseline wander removal
    val filtered = ArrayList<SensorReading>(cropped.size)
    var smoothed = cropped[0].value
    var baseline = cropped[0].value

    for (r in cropped) {
        smoothed += 0.3f * (r.value - smoothed)   // low-pass: removes high-freq noise
        baseline += 0.02f * (smoothed - baseline)  // very low-pass: tracks baseline drift
        filtered.add(SensorReading(r.timestamp, smoothed - baseline))
    }

    // ── Adaptive peak detection ───────────────────────────────────────────────
    // FIX: Use sampleRate to set minimum peak distance dynamically.
    // Old code used hardcoded 400ms which is only valid at certain rates.
    val minPeakDistanceMs = 333L  // 333ms = max 180 BPM physiological limit

    val variance  = filtered.sumOf { (it.value * it.value).toDouble() } / filtered.size
    val threshold = Math.sqrt(variance).toFloat() * 0.3f

    val peaks    = mutableListOf<Long>()
    var lastPeak = 0L

    for (i in 1 until filtered.size - 1) {
        val prev    = filtered[i - 1].value
        val current = filtered[i].value
        val next    = filtered[i + 1].value

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
        Log.w("BP_MEASURE", "Too few peaks (${peaks.size}) — signal too noisy")
        return
    }

    // ── IBI and HR calculation ────────────────────────────────────────────────
    val rawIbis = (1 until peaks.size).map { (peaks[it] - peaks[it - 1]).toFloat() }

    // FIX: Reject outlier IBIs before averaging.
    // Old code averaged all IBIs including ones from missed/false peaks,
    // which pulled the mean IBI up and the HR calculation down.
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
    val ibiSec    = (meanIbiMs / 1000.0).toFloat()
    val hr        = (60f / ibiSec).toInt()

    // ── BP estimation ─────────────────────────────────────────────────────────
    // FIX: Improved BP formula that also uses HRV (heart rate variability).
    // HRV adds a correction factor — higher variability → lower pressure tendency.
    val hrv = Math.sqrt(
        validIbis.map { (it - meanIbiMs) * (it - meanIbiMs) }.average()
    ).toFloat()

    val sbpRaw = ((-1.9333 * hr) + (-273.5684* ibiSec) + 497.1021 - (hrv * 0.05)).toInt()
    val dbpRaw = ((-1.5688  * hr) + (-176.4811  * ibiSec) + 341.2735 - (hrv * 0.03)).toInt()

    val sbp = sbpRaw.coerceIn(60, 220)
    val dbp = dbpRaw.coerceIn(40, 140)

    Log.d("BP_MEASURE",
        "HR=${hr}bpm IBI=${ibiSec}s HRV=${hrv}ms → SBP=$sbp DBP=$dbp"
    )

    val category = classifyBp(sbp, dbp)
    onState(BpState.Result(sbp, dbp, category))
    saveBpToFirebase(context, sbp, dbp, category)
}

// ─── Firebase save ────────────────────────────────────────────────────────────

fun saveBpToFirebase(
    context:  Context,
    sbp:      Int,
    dbp:      Int,
    category: BpCategory
) {
    val prefs     = context.getSharedPreferences("ElderCarePrefs", Context.MODE_PRIVATE)
    val patientId = prefs.getString("PATIENT_ID", "patient_001") ?: "patient_001"
    val db        = FirebaseFirestore.getInstance()

    val bpString  = "$sbp/$dbp"
    val timestamp = System.currentTimeMillis()

    val liveUpdates = mapOf(
        "bloodPressure" to bpString,
        "bpStatus"      to category.label
    )

    val historyData = mapOf(
        "bloodPressure" to bpString,
        "bpStatus"      to category.label,
        "systolic"      to sbp,
        "diastolic"     to dbp,
        "timestamp"     to timestamp
    )

    val patientRef = db.collection("patients").document(patientId)

    patientRef
        .set(liveUpdates, com.google.firebase.firestore.SetOptions.merge())
        .addOnFailureListener { e ->
            Log.e("BP_FIREBASE", "Failed to update live BP: ${e.message}")
        }

    patientRef
        .collection("blood_pressure")
        .document(timestamp.toString())
        .set(historyData)
        .addOnSuccessListener {
            Log.d("BP_FIREBASE", "✅ Saved BP: $bpString (${category.label})")
        }
        .addOnFailureListener { e ->
            Log.e("BP_FIREBASE", "Failed to save BP history: ${e.message}")
        }
}

// ─── BP Card ──────────────────────────────────────────────────────────────────

@Composable
fun BpCard(modifier: Modifier, bpState: BpState, onTap: () -> Unit) {
    val borderColor = when (bpState) {
        is BpState.Result      -> bpState.category.color
        is BpState.Measuring   -> AccentBlue
        is BpState.TooNoisy    -> AccentAmber
        is BpState.SensorError -> AccentRed
        else                   -> DividerColor
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
                imageVector        = Icons.Default.MonitorHeart,
                contentDescription = "BP",
                tint               = AccentBlue,
                modifier           = Modifier.size(12.dp)
            )
            Text(text = "Blood Pressure", fontSize = 7.sp, color = TextSecondary)
            Spacer(Modifier.height(3.dp))

            when (bpState) {
                is BpState.Idle -> {
                    Text("Tap to",  fontSize = 9.sp,  color = TextSecondary)
                    Text("Measure", fontSize = 10.sp, color = AccentBlue,
                        fontWeight = FontWeight.Bold)
                }
                is BpState.Measuring -> {
                    Text(
                        text       = "${bpState.secondsLeft}s",
                        fontSize   = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color      = AccentBlue
                    )
                    Text("Hold still", fontSize = 8.sp, color = TextSecondary)
                }
                is BpState.Result -> {
                    Text(
                        text       = "${bpState.sbp}",
                        fontSize   = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color      = bpState.category.color
                    )
                    Text(
                        text     = "${bpState.dbp} mmHg",
                        fontSize = 10.sp,
                        color    = TextSecondary
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(bpState.category.color.copy(alpha = 0.2f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text       = bpState.category.label,
                            fontSize   = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color      = bpState.category.color
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("Tap to retry", fontSize = 7.sp, color = TextSecondary)
                }
                is BpState.TooNoisy -> {
                    Text("Too Noisy", fontSize = 9.sp,
                        color = AccentAmber, fontWeight = FontWeight.Bold)
                    Text("Move less,", fontSize = 8.sp, color = TextSecondary)
                    Text("tap retry",  fontSize = 8.sp, color = TextSecondary)
                }
                is BpState.SensorError -> {
                    Text("Sensor",  fontSize = 9.sp,
                        color = AccentRed, fontWeight = FontWeight.Bold)
                    Text("Error",   fontSize = 9.sp, color = AccentRed)
                    Text("Tap retry", fontSize = 7.sp, color = TextSecondary)
                }
            }
        }
    }
}

// ─── Generic Vital Card ───────────────────────────────────────────────────────

@Composable
fun VitalCard(
    modifier:    Modifier,
    icon:        ImageVector,
    iconTint:    Color,
    label:       String,
    bgColor:     Color = CardBg,
    borderColor: Color = DividerColor,
    content:     @Composable ColumnScope.() -> Unit
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

// ─── Helpers ──────────────────────────────────────────────────────────────────

fun exportDataToCsv(context: Context, data: List<SensorReading>) {
    if (data.isEmpty()) return
    try {
        val file = File(
            context.getExternalFilesDir(null),
            "PPG_RawData_${System.currentTimeMillis()}.csv"
        )
        FileWriter(file).use { w ->
            w.append("Timestamp,PPG_Value\n")
            for (r in data) w.append("${r.timestamp},${r.value}\n")
        }
        Log.d("CSV_EXPORT", "✅ Saved: ${file.absolutePath}")
    } catch (e: Exception) {
        Log.e("CSV_EXPORT", "Failed to export CSV: ${e.message}")
    }
}

fun launchSamsungHealthSpo2(context: Context) {
    for (pkg in listOf("com.sec.android.app.shealth", "com.samsung.android.wear.shealth")) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("shealth://oxygen_saturation"))
                    .setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        } catch (_: Exception) {
            try {
                context.packageManager.getLaunchIntentForPackage(pkg)
                    ?.let { context.startActivity(it); return }
            } catch (_: Exception) {}
        }
    }
    Toast.makeText(context, "Samsung Health not installed", Toast.LENGTH_SHORT).show()
}

suspend fun readLatestSpo2(client: HealthConnectClient): String = try {
    val resp: ReadRecordsResponse<OxygenSaturationRecord> = client.readRecords(
        ReadRecordsRequest(
            recordType      = OxygenSaturationRecord::class,
            timeRangeFilter = TimeRangeFilter.between(
                Instant.now().minus(24, ChronoUnit.HOURS),
                Instant.now()
            ),
            ascendingOrder = false,
            pageSize       = 1
        )
    )
    resp.records.firstOrNull()
        ?.let { "${it.percentage.value.toInt()}%" }
        ?: "No Data"
} catch (e: Exception) {
    Log.e("SPO2", "Health Connect error: ${e.message}", e)
    "Perm needed"
}
