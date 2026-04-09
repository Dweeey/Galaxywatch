package com.example.galaxywatch.presentation

import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey

data class SensorReading(val timestamp: Long, val value: Float)

class PpgCaptureManager(private val healthTrackingService: HealthTrackingService) {

    private var ppgTracker: HealthTracker? = null

    // Buffer capped at 1500 samples (~60s at 25Hz) to prevent OOM
    private val MAX_BUFFER_SIZE = 1500
    val ppgBuffer = mutableListOf<SensorReading>()
    var isRecording = false

    // ── Sample Rate Detection ──
    // We measure the actual SDK delivery rate instead of assuming it.
    // Samsung Galaxy Watch 7 PPG_GREEN typically delivers at 25Hz,
    // but this varies by firmware — always measure, never assume.
    private var firstTimestampMs = 0L
    private var totalSamplesReceived = 0
    var detectedSampleRate = 25f   // default fallback until measured
        private set

    init {
        // PPG_GREEN: raw green LED signal, reliably supported on Galaxy Watch 7.
        // PPG_CONTINUOUS throws IllegalArgumentException on some firmware versions
        // despite appearing in the capability list.
        ppgTracker = healthTrackingService.getHealthTracker(HealthTrackerType.PPG_GREEN)
    }

    // ─────────────────────────────────────────────
    // TRACKER EVENT LISTENER
    // ─────────────────────────────────────────────

    private val ppgListener = object : HealthTracker.TrackerEventListener {

        override fun onDataReceived(dataPoints: List<DataPoint>) {
            if (!isRecording) return

            for (point in dataPoints) {
                try {
                    val rawValue = point.getValue(ValueKey.PpgGreenSet.PPG_GREEN)

                    when (rawValue) {

                        // Single sample — use timestamp directly
                        is Number -> {
                            val ppgValue = rawValue.toFloat()
                            addSample(point.timestamp, ppgValue)
                        }

                        // Batch of Int samples — interpolate timestamps between them.
                        // FIX: Previously all batch samples got the same timestamp,
                        // making IBI calculations completely wrong for batch delivery.
                        is IntArray -> {
                            addBatchSamples(
                                batchTimestampMs = point.timestamp,
                                values = rawValue.map { it.toFloat() }
                            )
                        }

                        // Batch of Float samples — same interpolation fix
                        is FloatArray -> {
                            addBatchSamples(
                                batchTimestampMs = point.timestamp,
                                values = rawValue.toList()
                            )
                        }

                        else -> {
                            Log.w("RAW_PPG", "Unexpected value type: ${rawValue?.javaClass}")
                        }
                    }

                } catch (e: Exception) {
                    Log.w("RAW_PPG", "Skipped a point: ${e.message}")
                }
            }
        }

        override fun onFlushCompleted() {
            Log.d("RAW_PPG", "Flush completed. Buffer size: ${ppgBuffer.size}")
        }

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e("RAW_PPG", "Tracker error: $error")
        }
    }

    // ─────────────────────────────────────────────
    // SAMPLE INGESTION
    // ─────────────────────────────────────────────

    // Adds a single sample and updates the sample rate estimate
    private fun addSample(timestampMs: Long, value: Float) {
        // Guard: don't add duplicate timestamps
        if (ppgBuffer.isNotEmpty() && ppgBuffer.last().timestamp >= timestampMs) {
            val lastTs = ppgBuffer.last().timestamp
            ppgBuffer.add(SensorReading(lastTs + 1, value))  // nudge by 1ms
            return
        }
        if (ppgBuffer.size >= MAX_BUFFER_SIZE) ppgBuffer.removeAt(0)
        ppgBuffer.add(SensorReading(timestampMs, value))
        updateSampleRate(timestampMs, count = 1)
        Log.v("RAW_PPG", "Sample: $value @ $timestampMs")
    }

    // FIX: For batch delivery, interpolate timestamps so each sample
    // gets its own unique timestamp rather than all sharing the batch timestamp.
    // This makes IBI calculation accurate because peak positions translate
    // to real time via their individual timestamps.
    private fun addBatchSamples(batchTimestampMs: Long, values: List<Float>) {
        val count = values.size
        if (count == 0) return

        // Estimate the interval between samples in this batch using
        // the detected sample rate (updated incrementally as data arrives)
        val intervalMs = (1000f / detectedSampleRate).toLong()

        // Assign timestamps backwards from the batch timestamp.
        // The batch timestamp is the time of the LAST sample in the batch.
        for (i in values.indices) {
            val sampleTimestamp = batchTimestampMs - ((count - 1 - i) * intervalMs)
            addSample(sampleTimestamp, values[i])
        }

        updateSampleRate(batchTimestampMs, count)
    }

    // Incrementally measures actual sample rate from timestamps
    private fun updateSampleRate(latestTimestampMs: Long, count: Int) {
        if (firstTimestampMs == 0L) {
            firstTimestampMs = latestTimestampMs
        }

        totalSamplesReceived += count

        val elapsedSeconds = (latestTimestampMs - firstTimestampMs) / 1000f

        // Only update after 3 seconds of data to get a stable estimate
        if (elapsedSeconds >= 3f && totalSamplesReceived > 10) {
            val measuredRate = totalSamplesReceived / elapsedSeconds
            detectedSampleRate = measuredRate

            // Log every 5 seconds so you can confirm the actual rate
            if (totalSamplesReceived % (detectedSampleRate * 5).toInt() == 0) {
                Log.d("PPG_RATE",
                    "Actual sample rate: ${"%.1f".format(measuredRate)} Hz " +
                            "(${totalSamplesReceived} samples in ${"%.1f".format(elapsedSeconds)}s)"
                )
            }
        }
    }

    // ─────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────

    fun startRecording() {
        ppgBuffer.clear()
        firstTimestampMs = 0L
        totalSamplesReceived = 0
        detectedSampleRate = 25f   // reset to fallback until measured
        isRecording = true
        ppgTracker?.setEventListener(ppgListener)
        Log.d("RAW_PPG", "✅ Recording started")
    }

    fun stopRecording(): PpgRecording {
        isRecording = false
        ppgTracker?.unsetEventListener()

        Log.d("RAW_PPG",
            "⏹ Recording stopped. " +
                    "Points: ${ppgBuffer.size} | " +
                    "Sample rate: ${"%.1f".format(detectedSampleRate)} Hz"
        )

        return PpgRecording(
            samples    = ppgBuffer.toList(),
            sampleRate = detectedSampleRate
        )
    }

    // Returns a snapshot of the buffer + current sample rate
    // without stopping the recording — useful for live HR updates
    fun getSnapshot(): PpgRecording {
        return PpgRecording(
            samples    = ppgBuffer.takeLast(500).toList(),  // last 500 samples
            sampleRate = detectedSampleRate
        )
    }

    fun release() {
        ppgTracker?.unsetEventListener()
        ppgTracker = null
        ppgBuffer.clear()
    }
}

// ─────────────────────────────────────────────
// DATA MODELS
// ─────────────────────────────────────────────

// Bundles the raw samples with the measured sample rate so the
// PPGProcessor always uses the correct rate for this recording session.
data class PpgRecording(
    val samples: List<SensorReading>,
    val sampleRate: Float
) {
    // Convenience: extract just the float values for signal processing
    val signal: FloatArray
        get() = FloatArray(samples.size) { i -> samples[i].value }

    val durationSeconds: Float
        get() = samples.size / sampleRate

    val isLongEnough: Boolean
        get() = durationSeconds >= 15f  // minimum 15s for reliable HR
}