package com.example.galaxywatch.presentation

data class BpEstimate(
    val sbp: Int,
    val dbp: Int,
    val hr: Int
)

object BpEstimator4 {

    // Paste Python output here
    private const val SBP_HR_COEF = -0.413412f
    private const val SBP_IBI_COEF = -102.305469f
    private const val SBP_SDNN_COEF = 55.120326f
    private const val SBP_RMSSD_COEF = -32.551395f
    private const val SBP_INTERCEPT = 252.500610f

    private const val DBP_HR_COEF = -2.255072f
    private const val DBP_IBI_COEF = -227.886581f
    private const val DBP_SDNN_COEF = 39.105752f
    private const val DBP_RMSSD_COEF = -25.502725f
    private const val DBP_INTERCEPT = 439.758891f

    private const val SBP_CAL_A = 0.575873f
    private const val SBP_CAL_B = 55.476458f
    private const val DBP_CAL_A = 0.189991f
    private const val DBP_CAL_B = 66.638044f

    fun estimate(hr: Float, ibiSec: Float, sdnnSec: Float, rmssdSec: Float): BpEstimate {
        val sbpRaw =
            (SBP_HR_COEF * hr) +
                    (SBP_IBI_COEF * ibiSec) +
                    (SBP_SDNN_COEF * sdnnSec) +
                    (SBP_RMSSD_COEF * rmssdSec) +
                    SBP_INTERCEPT

        val dbpRaw =
            (DBP_HR_COEF * hr) +
                    (DBP_IBI_COEF * ibiSec) +
                    (DBP_SDNN_COEF * sdnnSec) +
                    (DBP_RMSSD_COEF * rmssdSec) +
                    DBP_INTERCEPT

        val sbp = ((sbpRaw * SBP_CAL_A) + SBP_CAL_B).toInt().coerceIn(60, 220)
        val dbp = ((dbpRaw * DBP_CAL_A) + DBP_CAL_B).toInt().coerceIn(40, 140)

        return BpEstimate(sbp = sbp, dbp = dbp, hr = hr.toInt())
    }
}
