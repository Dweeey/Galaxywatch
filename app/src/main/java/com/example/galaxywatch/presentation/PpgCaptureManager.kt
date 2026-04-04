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
    val ppgBuffer = mutableListOf<SensorReading>()
    var isRecording = false

    init {
        // FIX: Use PPG_GREEN — it's the raw green LED signal and is reliably
        // supported on Galaxy Watch 7. PPG_CONTINUOUS throws IllegalArgumentException
        // despite appearing in the capability list on some firmware versions.
        ppgTracker = healthTrackingService.getHealthTracker(HealthTrackerType.PPG_GREEN)
    }

    private val ppgListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            if (!isRecording) return

            for (point in dataPoints) {
                try {
                    // PPG_GREEN tracker uses ValueKey.PpgGreenSet
                    val rawValue = point.getValue(ValueKey.PpgGreenSet.PPG_GREEN)

                    when (rawValue) {
                        is Number -> {
                            val ppgValue = rawValue.toFloat()
                            Log.d("RAW_PPG", "Captured: $ppgValue @ ${point.timestamp}")
                            ppgBuffer.add(SensorReading(point.timestamp, ppgValue))
                        }
                        is IntArray -> {
                            for (v in rawValue) {
                                Log.d("RAW_PPG", "Captured (batch): ${v.toFloat()}")
                                ppgBuffer.add(SensorReading(point.timestamp, v.toFloat()))
                            }
                        }
                        is FloatArray -> {
                            for (v in rawValue) {
                                Log.d("RAW_PPG", "Captured (batch): $v")
                                ppgBuffer.add(SensorReading(point.timestamp, v))
                            }
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
            Log.d("RAW_PPG", "Flush completed")
        }

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e("RAW_PPG", "Tracker error: $error")
        }
    }

    fun startRecording() {
        ppgBuffer.clear()
        isRecording = true
        ppgTracker?.setEventListener(ppgListener)
        Log.d("RAW_PPG", "Recording started")
    }

    fun stopRecording(): List<SensorReading> {
        isRecording = false
        ppgTracker?.unsetEventListener()
        Log.d("RAW_PPG", "Recording stopped. Points collected: ${ppgBuffer.size}")
        return ppgBuffer.toList()
    }

    fun release() {
        ppgTracker?.unsetEventListener()
        ppgTracker = null
    }
}