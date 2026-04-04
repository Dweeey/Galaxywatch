package com.example.galaxywatch.presentation

import android.util.Log
import kotlin.math.sqrt

/**
 * Personal PTT → BP calibration model.
 *
 * Theory: Pulse Transit Time is inversely related to blood pressure via the
 * Moens-Korteweg equation: PTT ∝ 1/√BP (arterial stiffness).
 *
 * This class fits two personal linear models (one for SBP, one for DBP)
 * from paired (PTT, cuff BP) calibration points:
 *
 *   SBP = sbpA * PTT + sbpB
 *   DBP = dbpA * PTT + dbpB
 *
 * Once enough points are collected the model can estimate BP from PTT alone.
 *
 * Accuracy improves significantly from 3 → 30 calibration points. The
 * confidence field reflects this so the UI can prompt the user to recalibrate.
 */
class BpCalibrationModel {

    companion object {
        private const val TAG = "BP_MODEL"

        // Minimum points before the model will produce estimates
        private const val MIN_CALIBRATION_POINTS = 3

        // Points at which confidence reaches 1.0
        private const val FULL_CONFIDENCE_POINTS = 30

        // Hard physiological clamps on output
        private const val SBP_MIN = 60f
        private const val SBP_MAX = 220f
        private const val DBP_MIN = 40f
        private const val DBP_MAX = 140f
    }

    // ─── Data types ───────────────────────────────────────────────────────────

    data class CalibrationPoint(
        val meanPttMs: Float,
        val refSbp: Int,
        val refDbp: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class BpEstimate(
        val sbp: Float,
        val dbp: Float,
        val pp: Float,              // pulse pressure = SBP - DBP
        val map: Float,             // mean arterial pressure = DBP + PP/3
        val confidence: Float,      // 0.0–1.0
        val pttMs: Float,
        val calibrationCount: Int
    )

    data class ModelStats(
        val sbpRmse: Float,         // root mean square error on calibration set
        val dbpRmse: Float,
        val sbpR2: Float,           // coefficient of determination
        val dbpR2: Float,
        val n: Int
    )

    // ─── State ────────────────────────────────────────────────────────────────

    private val calibrationPoints = mutableListOf<CalibrationPoint>()

    // Linear model coefficients
    private var sbpA = 0f
    private var sbpB = 0f
    private var dbpA = 0f
    private var dbpB = 0f
    private var isFitted = false

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Add a calibration data point from a simultaneous cuff + PTT measurement.
     * The model is automatically refitted after each addition once MIN is reached.
     */
    fun addCalibrationPoint(meanPttMs: Float, refSbp: Int, refDbp: Int) {
        require(meanPttMs > 0f)  { "PTT must be positive" }
        require(refSbp in 60..220) { "SBP $refSbp out of range" }
        require(refDbp in 40..140) { "DBP $refDbp out of range" }

        calibrationPoints.add(CalibrationPoint(meanPttMs, refSbp, refDbp))
        Log.d(TAG, "Added calibration point — PTT=${meanPttMs}ms SBP=$refSbp DBP=$refDbp (total: ${calibrationPoints.size})")

        if (calibrationPoints.size >= MIN_CALIBRATION_POINTS) {
            fitModel()
        }
    }

    /**
     * Estimate BP from a PTT measurement. Returns null if the model is not
     * yet fitted (fewer than MIN_CALIBRATION_POINTS added).
     */
    fun estimateBp(meanPttMs: Float): BpEstimate? {
        if (!isFitted) {
            Log.w(TAG, "Model not fitted yet — need ${MIN_CALIBRATION_POINTS - calibrationPoints.size} more calibration points")
            return null
        }

        val sbp = (sbpA * meanPttMs + sbpB).coerceIn(SBP_MIN, SBP_MAX)
        val dbp = (dbpA * meanPttMs + dbpB).coerceIn(DBP_MIN, DBP_MAX)
        val pp  = (sbp - dbp).coerceAtLeast(0f)
        val map = dbp + pp / 3f

        val confidence = ((calibrationPoints.size - MIN_CALIBRATION_POINTS).toFloat() /
                (FULL_CONFIDENCE_POINTS - MIN_CALIBRATION_POINTS)).coerceIn(0f, 1f)

        return BpEstimate(
            sbp              = sbp,
            dbp              = dbp,
            pp               = pp,
            map              = map,
            confidence       = confidence,
            pttMs            = meanPttMs,
            calibrationCount = calibrationPoints.size
        )
    }

    /**
     * Compute in-sample RMSE and R² for both SBP and DBP.
     * Useful to display model quality in the UI.
     */
    fun computeStats(): ModelStats? {
        if (!isFitted || calibrationPoints.size < MIN_CALIBRATION_POINTS) return null

        val ptts = calibrationPoints.map { it.meanPttMs }
        val sbps = calibrationPoints.map { it.refSbp.toFloat() }
        val dbps = calibrationPoints.map { it.refDbp.toFloat() }

        val predSbp = ptts.map { (sbpA * it + sbpB).coerceIn(SBP_MIN, SBP_MAX) }
        val predDbp = ptts.map { (dbpA * it + dbpB).coerceIn(DBP_MIN, DBP_MAX) }

        val sbpRmse = sqrt(sbps.zip(predSbp).map { (a, b) -> (a - b) * (a - b) }.average()).toFloat()
        val dbpRmse = sqrt(dbps.zip(predDbp).map { (a, b) -> (a - b) * (a - b) }.average()).toFloat()

        val sbpR2 = computeR2(sbps, predSbp)
        val dbpR2 = computeR2(dbps, predDbp)

        return ModelStats(sbpRmse, dbpRmse, sbpR2, dbpR2, calibrationPoints.size)
    }

    fun isReady()            = isFitted
    fun calibrationCount()   = calibrationPoints.size
    fun needsMoreData()      = calibrationPoints.size < 10
    fun getCalibrationPoints(): List<CalibrationPoint> = calibrationPoints.toList()

    // ─── Model fitting ────────────────────────────────────────────────────────

    private fun fitModel() {
        val ptts = calibrationPoints.map { it.meanPttMs }
        val sbps = calibrationPoints.map { it.refSbp.toFloat() }
        val dbps = calibrationPoints.map { it.refDbp.toFloat() }

        val meanPtt = ptts.average().toFloat()
        val meanSbp = sbps.average().toFloat()
        val meanDbp = dbps.average().toFloat()

        val sxx    = ptts.sumOf { ((it - meanPtt) * (it - meanPtt)).toDouble() }.toFloat()
        val sxySbp = ptts.zip(sbps).sumOf { ((it.first - meanPtt) * (it.second - meanSbp)).toDouble() }.toFloat()
        val sxyDbp = ptts.zip(dbps).sumOf { ((it.first - meanPtt) * (it.second - meanDbp)).toDouble() }.toFloat()

        if (sxx == 0f) {
            Log.w(TAG, "Zero variance in PTT — cannot fit model (all PTT values identical?)")
            return
        }

        sbpA = sxySbp / sxx
        sbpB = meanSbp - sbpA * meanPtt
        dbpA = sxyDbp / sxx
        dbpB = meanDbp - dbpA * meanPtt
        isFitted = true

        Log.d(TAG, "Model refitted (n=${calibrationPoints.size}): SBP = ${sbpA}×PTT + $sbpB")
        Log.d(TAG, "Model refitted (n=${calibrationPoints.size}): DBP = ${dbpA}×PTT + $dbpB")
    }

    private fun computeR2(actual: List<Float>, predicted: List<Float>): Float {
        val mean = actual.average().toFloat()
        val ssTot = actual.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat()
        val ssRes = actual.zip(predicted).sumOf { (a, p) -> ((a - p) * (a - p)).toDouble() }.toFloat()
        return if (ssTot == 0f) 0f else 1f - ssRes / ssTot
    }
}