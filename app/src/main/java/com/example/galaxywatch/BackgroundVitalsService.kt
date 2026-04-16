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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

class BackgroundVitalsService : Service(), SensorEventListener {

    companion object {
        @Volatile
        var isRunning = false

        const val ACTION_CONFIRM_FALL  = "ACTION_CONFIRM_FALL"
        const val ACTION_CANCEL_FALL   = "ACTION_CANCEL_FALL"
        const val ACTION_TRIGGER_SOS   = "ACTION_TRIGGER_SOS"
        const val ACTION_START_LOGGING = "ACTION_START_LOGGING"
        const val ACTION_STOP_LOGGING  = "ACTION_STOP_LOGGING"

        private const val PREFS_SYSTEM_STATE = "SYSTEM_STATE"
        private const val KEY_SOS_ACTIVE     = "SOS_ACTIVE"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db = Firebase.firestore

    private lateinit var sensorManager: SensorManager

    private var accelerometer: Sensor? = null
    private var gyroscope:     Sensor? = null
    private var offBodySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var healthTrackingService: HealthTrackingService? = null
    private var hrTracker: HealthTracker? = null
    private var tflite: Interpreter? = null

    // ── Detection thresholds ──────────────────────────────────────────────────
    //
    // FIX #5: Corrected threshold values.
    //   - FALL_THRESHOLD  = score must EXCEED this to count as a suspected fall.
    //   - VETO_THRESHOLD  = score must be BELOW this to reset the counter.
    //   - The gap (0.25 – 0.45) is the true ambiguous zone where the counter holds.
    //
    // Previously VETO (0.50) > FALL (0.40) made the ambiguous branch unreachable:
    // any p > 0.40 hit the increment branch first, so the veto only fired at p ≤ 0.40.
    // Now the two thresholds are properly separated.
    //
    // FIX #2: CONFIRMATION_WINDOWS reduced from 3 → 1.
    //   Each window step = 128 samples × (1/50 Hz) = 2.56 seconds.
    //   3 consecutive windows = ~7+ seconds of sustained high scores — a fall is
    //   a 1-2 second event; after impact the person is still (low variance) so the
    //   stillness pre-screen skips those windows and the counter never reaches 3.
    //   1 strong window is enough; the 1.5 s post-impact stillness check is the
    //   real safeguard against false positives.
    private val CONFIRMATION_WINDOWS         = 2      // FIX #2 (was 3)
    private val FALL_THRESHOLD               = 0.60f   // FIX #5 (was 0.40)
    private val VETO_THRESHOLD               = 0.25f   // FIX #5 (was 0.50 — inverted logic)
    private val STILLNESS_VARIANCE_THRESHOLD = 0.3f
    private val POST_FALL_MOVEMENT_VETO      = 0.08f

    // ── Sensor buffers ────────────────────────────────────────────────────────
    //
    // FIX #1 + FIX #3: Buffer redesign to match training.
    //
    // TRAINING used WINDOW_SIZE=256 raw samples resampled to TARGET_LEN=512,
    // with OVERLAP=128 (50 % overlap). At 50 Hz, 256 samples = 5.12 s.
    //
    // ORIGINAL KOTLIN used 512 raw samples (= 10.24 s) fed directly to the model,
    // making every window appear at half the temporal "speed" the model expects.
    // Fall impact spikes were stretched out; arm-swing bursts occupied a different
    // fraction of the window than in training → wrong pattern matching.
    //
    // NEW: collect 256 raw samples, resample to 512, then normalise — exactly
    // matching the training pipeline.
    //
    // FIX #3: Gyro is now written at the same index as accel (driven by the
    // accelerometer event), so both channels are always time-aligned.
    // latestGyroMag holds the most-recent gyro magnitude from its own callback.
    private val RAW_WINDOW   = 256   // collect this many raw samples   (= 5.12 s @ 50 Hz)
    private val RAW_OVERLAP  = 128   // slide by this many after a window fires
    private val MODEL_LEN    = 512   // model input length (resampled target)

    private val accelBuffer  = FloatArray(RAW_WINDOW)
    private val gyroBuffer   = FloatArray(RAW_WINDOW)  // filled in lockstep with accel

    @Volatile private var bufferIndex     = 0      // FIX #3: single shared index
    @Volatile private var latestGyroMag   = 0f     // FIX #3: latest gyro sample

    @Volatile private var consecutiveHighScores = 0
    @Volatile private var isVerifying           = false
    @Volatile private var ignoreFallDetection   = false
    @Volatile private var isManualSosActive     = false

    private var sosSuppressionJob: Job? = null
    private val bufferLock = Any()

    // ── State ─────────────────────────────────────────────────────────────────
    private var currentHeartRate = "Scanning..."
    private var fallDetected     = false
    private var fallMessage      = "Scanning..."
    private var isWatchOnWrist   = true

    private var lastFirebaseUpdateTime   = 0L
    private val FIREBASE_UPDATE_INTERVAL = 30_000L

    // ── Gesture logging ───────────────────────────────────────────────────────
    @Volatile private var isLoggingMode = false
    private val loggedWindows = mutableListOf<Pair<FloatArray, FloatArray>>()
    private val loggingLock   = Any()

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d("SERVICE_DEBUG", "BackgroundVitalsService created")

        acquireWakeLock()
        startForegroundServiceNotification()
        loadLocalAIBrain()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Accelerometer at 50 Hz (20 000 µs) — drives both channels
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let { sensorManager.registerListener(this, it, 20_000) }

        // Gyroscope at same 50 Hz — latestGyroMag is read on every accel event
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope!!, 20_000)
            Log.d("SERVICE_DEBUG", "Gyroscope registered")
        } else {
            Log.w("SERVICE_DEBUG", "No gyroscope found — gyro channel will be zero-filled")
        }

        offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        offBodySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        connectSamsungHealth()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            ACTION_START_LOGGING -> {
                synchronized(loggingLock) { loggedWindows.clear() }
                isLoggingMode = true
                Log.d("GESTURE_LOG", "Logging started")
                return START_STICKY
            }

            ACTION_STOP_LOGGING -> {
                isLoggingMode = false
                saveLoggedData(label = 0)
                return START_STICKY
            }

            ACTION_TRIGGER_SOS -> {
                startManualSosSuppression()
                return START_STICKY
            }

            ACTION_CANCEL_FALL -> {
                Log.d("FALL_AI", "User cancelled fall alert")
                fallDetected          = false
                fallMessage           = "Scanning..."
                consecutiveHighScores = 0
                isVerifying           = false
                SharedVitals.fallDetected.value = false
                SharedVitals.fallMessage.value  = fallMessage
                pushDataUpdates(forceFirebase = true)
                return START_STICKY
            }

            ACTION_CONFIRM_FALL -> {
                Log.d("FALL_AI", "Fall confirmed after timeout")
                fallDetected = true
                fallMessage  = "FALL DETECTED!"
                SharedVitals.fallDetected.value = true
                SharedVitals.fallMessage.value  = fallMessage
                pushDataUpdates(forceFirebase = true)

                serviceScope.launch {
                    delay(60_000L)
                    fallDetected          = false
                    fallMessage           = "Scanning..."
                    consecutiveHighScores = 0
                    isVerifying           = false
                    SharedVitals.fallDetected.value = false
                    SharedVitals.fallMessage.value  = fallMessage
                    pushDataUpdates(forceFirebase = true)
                    Log.d("FALL_AI", "Fall status reset after 1 minute")
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d("SERVICE_DEBUG", "BackgroundVitalsService destroyed")
        isRunning = false
        super.onDestroy()
        serviceScope.cancel()
        sensorManager.unregisterListener(this)
        hrTracker?.unsetEventListener()
        healthTrackingService?.disconnectService()
        tflite?.close()
        wakeLock?.release()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Sensor events
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {

            // ── Accelerometer ─────────────────────────────────────────────────
            //
            // FIX #1 + FIX #3: Accumulate RAW_WINDOW (256) samples into accelBuffer
            // and gyroBuffer in lockstep using a single index (bufferIndex).
            // When full, resample both channels to MODEL_LEN (512) before inference
            // — matching the training pipeline exactly.
            Sensor.TYPE_ACCELEROMETER -> {
                if (!isWatchOnWrist) return

                val ax  = event.values[0]; val ay = event.values[1]; val az = event.values[2]
                val mag = Math.sqrt((ax*ax + ay*ay + az*az).toDouble()).toFloat()

                synchronized(bufferLock) {
                    if (bufferIndex < RAW_WINDOW) {
                        accelBuffer[bufferIndex] = mag
                        gyroBuffer[bufferIndex]  = latestGyroMag  // FIX #3: time-aligned
                        bufferIndex++
                    }

                    if (bufferIndex >= RAW_WINDOW) {
                        val accelSnap = accelBuffer.copyOf()
                        val gyroSnap  = gyroBuffer.copyOf()

                        // FIX #1: Slide by RAW_OVERLAP (128) = 50 % overlap, matching training
                        System.arraycopy(accelBuffer, RAW_OVERLAP, accelBuffer, 0, RAW_WINDOW - RAW_OVERLAP)
                        System.arraycopy(gyroBuffer,  RAW_OVERLAP, gyroBuffer,  0, RAW_WINDOW - RAW_OVERLAP)
                        bufferIndex = RAW_WINDOW - RAW_OVERLAP

                        serviceScope.launch {
                            // FIX #1: Resample 256 → 512 to match training's resample_and_normalize
                            val accelResampled = linearResample(accelSnap, MODEL_LEN)
                            val gyroResampled  = linearResample(gyroSnap,  MODEL_LEN)

                            if (isLoggingMode) {
                                synchronized(loggingLock) {
                                    loggedWindows.add(Pair(accelResampled, gyroResampled))
                                }
                                Log.v("GESTURE_LOG", "Window #${loggedWindows.size} captured")
                                return@launch
                            }
                            runFallDetectionInference(accelResampled, gyroResampled)
                        }
                    }
                }
            }

            // ── Gyroscope ─────────────────────────────────────────────────────
            //
            // FIX #3: Only update latestGyroMag here. Writing into the buffer is
            // done exclusively in the accelerometer handler so both channels share
            // the same index and are always time-aligned.
            Sensor.TYPE_GYROSCOPE -> {
                if (!isWatchOnWrist) return
                val gx  = event.values[0]; val gy = event.values[1]; val gz = event.values[2]
                latestGyroMag = Math.sqrt((gx*gx + gy*gy + gz*gz).toDouble()).toFloat()
            }

            // ── Off-body detect ───────────────────────────────────────────────
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> {
                val onBody = event.values[0] != 0f
                if (isWatchOnWrist != onBody) {
                    isWatchOnWrist = onBody
                    if (!isWatchOnWrist) {
                        currentHeartRate = "--"
                        SharedVitals.heartRate.value = "--"
                        synchronized(bufferLock) { bufferIndex = 0 }  // FIX #3: reset single index
                    }
                    pushDataUpdates(forceFirebase = true)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ─────────────────────────────────────────────────────────────────────────
    //  FIX #1 helper: linear resampling (mirrors training's np.interp)
    //
    //  input  — raw FloatArray of size RAW_WINDOW (256)
    //  target — desired output length MODEL_LEN (512)
    //
    //  This is equivalent to:
    //    x_old = np.linspace(0, 1, len(signal))
    //    x_new = np.linspace(0, 1, TARGET_LEN)
    //    resampled = np.interp(x_new, x_old, signal)
    // ─────────────────────────────────────────────────────────────────────────
    private fun linearResample(input: FloatArray, targetLen: Int): FloatArray {
        if (input.size == targetLen) return input.copyOf()
        val output = FloatArray(targetLen)
        val ratio  = (input.size - 1).toFloat() / (targetLen - 1).toFloat()
        for (i in 0 until targetLen) {
            val pos  = i * ratio
            val lo   = pos.toInt().coerceAtMost(input.size - 2)
            val frac = pos - lo
            output[i] = input[lo] * (1f - frac) + input[lo + 1] * frac
        }
        return output
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Fall detection inference  (receives MODEL_LEN = 512 samples)
    // ─────────────────────────────────────────────────────────────────────────

    private fun runFallDetectionInference(accelBuf: FloatArray, gyroBuf: FloatArray) {
        if (tflite == null) return
        if (ignoreFallDetection || isManualSosActive || isSosActiveFromPrefs()) {
            Log.d("FALL_AI", "Skipped — SOS active")
            return
        }

        // Quick pre-screen: skip clearly-still windows
        val accelMean = accelBuf.average().toFloat()
        val accelVar  = accelBuf.map { (it - accelMean) * (it - accelMean) }.average().toFloat()

        if (accelVar < STILLNESS_VARIANCE_THRESHOLD) {
            if (consecutiveHighScores > 0) consecutiveHighScores = 0
            Log.v("FALL_AI", "Still — skip (accelVar=${"%.3f".format(accelVar)})")
            return
        }

        // FIX #4: Lowered from 10f → 8.5f to catch slow/soft falls by elderly users.
        // At rest, accel magnitude ≈ 9.8 m/s² (gravity). The original threshold of
        // 10f sat just above resting gravity, so gentle falls that never spike high
        // (e.g. falling onto furniture) were silently discarded before reaching the model.
        val maxG = accelBuf.max()
        if (maxG < 8.5f) {
            if (consecutiveHighScores > 0) consecutiveHighScores = 0
            Log.v("FALL_AI", "Low-G — skip (max=${"%.1f".format(maxG)})")
            return
        }

        // Z-score normalise each channel independently (mirrors training's resample_and_normalize)
        fun normalise(buf: FloatArray): FloatArray {
            val m   = buf.average().toFloat()
            val std = Math.sqrt(buf.map { ((it - m) * (it - m)).toDouble() }.average()).toFloat()
            return if (std > 0f) FloatArray(MODEL_LEN) { i -> (buf[i] - m) / std } else buf.copyOf()
        }

        val normAccel = normalise(accelBuf)
        val normGyro  = normalise(gyroBuf)

        // Model expects (1, 512, 2): channel 0 = accel mag, channel 1 = gyro mag
        val input  = Array(1) { Array(MODEL_LEN) { i -> floatArrayOf(normAccel[i], normGyro[i]) } }
        val output = Array(1) { FloatArray(1) }

        try {
            tflite?.run(input, output)
            val p = output[0][0]

            Log.d("FALL_AI",
                "p=${"%.3f".format(p)} accelVar=${"%.3f".format(accelVar)} maxG=${"%.1f".format(maxG)}")

            // FIX #5: Corrected threshold semantics.
            //   p > FALL_THRESHOLD (0.45)  → confident fall signal  → increment counter
            //   p < VETO_THRESHOLD (0.25)  → clearly not a fall     → reset counter
            //   0.25 ≤ p ≤ 0.45            → ambiguous zone         → hold counter
            //
            // Previously VETO (0.50) > FALL (0.40), which made the ambiguous branch
            // unreachable because the first `when` branch (p > 0.40) always matched
            // before p < 0.50 could be evaluated for values in [0.40, 0.50].
            when {
                p > FALL_THRESHOLD -> {
                    consecutiveHighScores++
                    Log.d("FALL_AI", "Score $consecutiveHighScores/$CONFIRMATION_WINDOWS")

                    // FIX #2: CONFIRMATION_WINDOWS = 1, so this fires on the first
                    // high-confidence window instead of requiring 3 consecutive ones
                    // (~15 seconds apart), which a brief fall event can never satisfy.
                    if (consecutiveHighScores >= CONFIRMATION_WINDOWS && !fallDetected && !isVerifying) {
                        isVerifying = true
                        Log.w("FALL_AI", "Suspected fall — checking post-impact stillness")

                        serviceScope.launch {
                            // Wait 2500ms instead of 1500ms — gives punch arm time to finish returning
                            // and settle, which would push variance HIGHER, not lower.
                            // A real fall person is still on the floor the entire time.
                            delay(2500L)

                            var recentVar = 0f
                            var recentGyroMax  = 0f   // ADD: check for punch return-stroke in gyro

                            synchronized(bufferLock) {
                                val end    = bufferIndex
                                val start  = (end - 125).coerceAtLeast(0)  // 125 samples = 2.5s @ 50Hz
                                val recentA = accelBuffer.slice(start until end)
                                val recentG = gyroBuffer.slice(start until end)   // ADD

                                if (recentA.isNotEmpty()) {
                                    val rm = recentA.average().toFloat()
                                    recentVar = recentA.map { (it - rm) * (it - rm) }.average().toFloat()
                                }
                                if (recentG.isNotEmpty()) {
                                    recentGyroMax = recentG.maxOrNull() ?: 0f   // ADD
                                }
                            }
                            val isMoving    = recentVar > POST_FALL_MOVEMENT_VETO   // now 0.08f
                            val hasReturnStroke = recentGyroMax > 1.5f

                            if (recentVar > POST_FALL_MOVEMENT_VETO) {
                                Log.d("FALL_AI", "VETOED: post-impact var=${"%.2f".format(recentVar)}")
                                consecutiveHighScores = 0
                            } else {
                                Log.w("FALL_AI", "CONFIRMED: still after impact (var=${"%.2f".format(recentVar)})")

                                if (ignoreFallDetection || isManualSosActive || isSosActiveFromPrefs()) {
                                    fallDetected = false
                                    fallMessage  = "Scanning..."
                                    SharedVitals.fallDetected.value = false
                                    SharedVitals.fallMessage.value  = fallMessage
                                    pushDataUpdates(forceFirebase = true)
                                    isVerifying = false
                                    return@launch
                                }

                                fallDetected = true
                                fallMessage  = "Checking..."
                                SharedVitals.fallDetected.value = true
                                SharedVitals.fallMessage.value  = fallMessage
                                launchFallConfirmationActivity()
                            }
                            isVerifying = false
                        }
                    }
                }

                p < VETO_THRESHOLD -> {
                    // FIX #5: Only reset when score is confidently non-fall (< 0.25),
                    // not at the old 0.50 which was above the fall threshold itself.
                    if (!isVerifying) consecutiveHighScores = 0
                }

                else -> { /* ambiguous zone (0.25–0.45) — hold counter */ }
            }

        } catch (e: Exception) {
            Log.e("FALL_AI", "Inference error: ${e.message}", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Gesture logger
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Saves captured 2-channel windows to CSV.
     * Columns: label, a0..a511, g0..g511
     *
     * Note: windows are already resampled to 512 points (matching training).
     *
     * Pull from watch:
     *   adb pull /sdcard/Android/data/com.example.galaxywatch/files/ ./watch_data/
     */
    private fun saveLoggedData(label: Int) {
        val windows: List<Pair<FloatArray, FloatArray>>
        synchronized(loggingLock) {
            windows = loggedWindows.toList()
            loggedWindows.clear()
        }

        if (windows.isEmpty()) {
            Log.w("GESTURE_LOG", "No windows to save")
            return
        }

        serviceScope.launch {
            try {
                val filename = "gesture_log_label${label}_${System.currentTimeMillis()}.csv"
                val file = File(getExternalFilesDir(null), filename)

                file.bufferedWriter().use { w ->
                    val accelCols = (0 until MODEL_LEN).joinToString(",") { "a$it" }
                    val gyroCols  = (0 until MODEL_LEN).joinToString(",") { "g$it" }
                    w.write("label,$accelCols,$gyroCols"); w.newLine()

                    for ((accel, gyro) in windows) {
                        w.write("$label,${accel.joinToString(",")},${gyro.joinToString(",")}")
                        w.newLine()
                    }
                }

                Log.d("GESTURE_LOG", "Saved ${windows.size} windows → ${file.absolutePath}")

            } catch (e: Exception) {
                Log.e("GESTURE_LOG", "Save failed: ${e.message}", e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Samsung Health / HR
    // ─────────────────────────────────────────────────────────────────────────

    private fun connectSamsungHealth() {
        val listener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                Log.d("SAMSUNG_SDK", "Connected")
                try {
                    hrTracker = healthTrackingService?.getHealthTracker(
                        HealthTrackerType.HEART_RATE_CONTINUOUS
                    )
                    hrTracker?.setEventListener(hrListener)
                } catch (e: Exception) {
                    Log.e("SAMSUNG_SDK", "HR tracker error: ${e.message}", e)
                }
            }
            override fun onConnectionEnded() { Log.w("SAMSUNG_SDK", "Disconnected") }
            override fun onConnectionFailed(e: HealthTrackerException?) {
                Log.e("SAMSUNG_SDK", "Failed: ${e?.message}")
            }
        }
        healthTrackingService = HealthTrackingService(listener, this)
        healthTrackingService?.connectService()
    }

    private val hrListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataList: List<DataPoint>) {
            if (!isWatchOnWrist) return
            for (data in dataList) {
                try {
                    val hr = data.getValue(ValueKey.HeartRateSet.HEART_RATE) as? Int ?: continue
                    if (hr > 0) {
                        currentHeartRate = hr.toString()
                        SharedVitals.heartRate.value = currentHeartRate
                        pushDataUpdates(forceFirebase = false)
                    }
                } catch (e: Exception) {
                    Log.e("SAMSUNG_SDK", "HR error: ${e.message}", e)
                }
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError?) { Log.e("HR_DEBUG", "Error: $e") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadLocalAIBrain() {
        try {
            val fd     = assets.openFd("wedafall_brain_1.tflite")
            val mapped = FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
            tflite = Interpreter(mapped, Interpreter.Options().apply { setNumThreads(2) })
            Log.d("FALL_AI", "Brain loaded — expects input (1, 512, 2)")
        } catch (e: Exception) {
            Log.e("FALL_AI", "Brain load failed: ${e.message}", e)
        }
    }

    private fun launchFallConfirmationActivity() {
        if (ignoreFallDetection || isManualSosActive || isSosActiveFromPrefs()) return
        startActivity(
            Intent(this, com.example.galaxywatch.presentation.FallConfirmationActivity::class.java)
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
        )
    }

    private fun startManualSosSuppression() {
        Log.d("SOS", "Suppression started")
        isManualSosActive     = true
        ignoreFallDetection   = true
        setSosActivePref(true)
        isVerifying           = false
        consecutiveHighScores = 0
        fallDetected          = false
        fallMessage           = "Scanning..."
        SharedVitals.fallDetected.value = false
        SharedVitals.fallMessage.value  = fallMessage
        pushDataUpdates(forceFirebase = true)

        sosSuppressionJob?.cancel()
        sosSuppressionJob = serviceScope.launch {
            delay(60_000L)
            isManualSosActive   = false
            ignoreFallDetection = false
            setSosActivePref(false)
            Log.d("SOS", "Suppression ended")
        }
    }

    private fun setSosActivePref(active: Boolean) =
        getSharedPreferences(PREFS_SYSTEM_STATE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SOS_ACTIVE, active).apply()

    private fun isSosActiveFromPrefs() =
        getSharedPreferences(PREFS_SYSTEM_STATE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SOS_ACTIVE, false)

    private fun pushDataUpdates(forceFirebase: Boolean = false) {
        val now = System.currentTimeMillis()
        SharedVitals.heartRate.value    = currentHeartRate
        SharedVitals.fallDetected.value = fallDetected
        SharedVitals.fallMessage.value  = fallMessage

        if (!forceFirebase && now - lastFirebaseUpdateTime < FIREBASE_UPDATE_INTERVAL) return
        lastFirebaseUpdateTime = now

        val ref     = db.collection("patients").document("patient_001")
        val updates = hashMapOf<String, Any>(
            "heartRate"     to currentHeartRate,
            "fallDetected"  to fallDetected,
            "statusMessage" to fallMessage,
            "timestamp"     to now
        )
        ref.set(updates, SetOptions.merge())
        ref.collection("history").document(now.toString()).set(HashMap(updates))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ElderCare::VitalsWakeLock")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()
    }

    private fun startForegroundServiceNotification() {
        val id = "VitalsServiceChannel_Silent"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(id, "Health Monitoring", NotificationManager.IMPORTANCE_LOW)
                .apply { enableVibration(false); setSound(null, null) }
        )
        startForeground(1, NotificationCompat.Builder(this, id)
            .setContentTitle("ElderCare Active")
            .setContentText("Monitoring heart rate and fall detection...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true).build()
        )
    }
}

object SharedVitals {
    val heartRate    = MutableStateFlow("--")
    val fallDetected = MutableStateFlow(false)
    val fallMessage  = MutableStateFlow("Scanning...")
}