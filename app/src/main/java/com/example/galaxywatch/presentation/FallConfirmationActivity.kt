package com.example.galaxywatch.presentation

import android.content.Intent
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme {
                FallConfirmationScreen(
                    onImOk = {
                        val intent = Intent(this, BackgroundVitalsService::class.java).apply {
                            action = BackgroundVitalsService.ACTION_CANCEL_FALL
                        }
                        startService(intent)
                        finish()
                    },
                    onTimeout = {
                        val intent = Intent(this, BackgroundVitalsService::class.java).apply {
                            action = BackgroundVitalsService.ACTION_CONFIRM_FALL
                        }
                        startService(intent)
                        finish()
                    }
                )
            }
        }
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
            Text(
                text = "I'M OK",
                color = Color.White
            )
        }
    }
}