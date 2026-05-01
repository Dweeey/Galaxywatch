package com.example.galaxywatch.presentation

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.galaxywatch.BackgroundVitalsService
import kotlinx.coroutines.delay

class FallConfirmationActivity : ComponentActivity() {

    companion object {
        private const val PREFS_SYSTEM_STATE = "SYSTEM_STATE"
        private const val KEY_SOS_ACTIVE = "SOS_ACTIVE"
    }

    private var vibrator: Vibrator? = null
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isSosActive = getSharedPreferences(PREFS_SYSTEM_STATE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SOS_ACTIVE, false)

        if (isSosActive) {
            Log.d("FALL_UI", "Blocked FallConfirmationActivity due to SOS")
            finish()
            return
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        triggerRepeatingVibration()

        setContent {
            MaterialTheme {
                FallConfirmationScreen(
                    onImOk = {
                        if (handled) return@FallConfirmationScreen
                        handled = true
                        stopVibration()

                        startService(
                            Intent(this, BackgroundVitalsService::class.java).apply {
                                action = BackgroundVitalsService.ACTION_CANCEL_FALL
                            }
                        )
                        finish()
                    },
                    onTimeout = {
                        if (handled) return@FallConfirmationScreen
                        handled = true
                        stopVibration()

                        // Confirm the fall first so UI stays on FALL DETECTED for 1 minute
                        startService(
                            Intent(this, BackgroundVitalsService::class.java).apply {
                                action = BackgroundVitalsService.ACTION_CONFIRM_FALL
                            }
                        )

                        // Go to SOS screen, but do NOT clear the fall state here
                        startActivity(
                            Intent(this, SosActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                            }
                        )
                        finish()
                    }
                )
            }
        }
    }

    private fun triggerRepeatingVibration() {
        val pattern = longArrayOf(0, 700, 300, 700, 300, 700)

        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, 0)
                it.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(pattern, 0)
            }
        }
    }

    private fun stopVibration() {
        vibrator?.cancel()
    }

    override fun onDestroy() {
        stopVibration()
        super.onDestroy()
    }
}

@Composable
fun FallConfirmationScreen(
    onImOk: () -> Unit,
    onTimeout: () -> Unit
) {
    var secondsLeft by remember { mutableStateOf(10) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0 && !finished) {
            delay(1000L)
            secondsLeft--
        }

        if (!finished) {
            finished = true
            onTimeout()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "FALL DETECTED",
            color = Color.Red,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Are you okay?",
            color = Color.White,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "$secondsLeft",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = {
                if (!finished) {
                    finished = true
                    onImOk()
                }
            }
        ) {
            Text("I'M OK")
        }
    }
}
