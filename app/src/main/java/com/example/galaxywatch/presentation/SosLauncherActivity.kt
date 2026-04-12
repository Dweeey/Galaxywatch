package com.example.galaxywatch.presentation

import android.content.Intent
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import com.example.galaxywatch.BackgroundVitalsService

class SosLauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ⚡ OPTIONAL: remove UI flash
        window.setBackgroundDrawable(null)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        // 🔥 Tell service SOS is triggered
        val intent = Intent(this, BackgroundVitalsService::class.java).apply {
            action = BackgroundVitalsService.ACTION_TRIGGER_SOS
        }
        startService(intent)

        // 🚀 Launch actual SOS call activity
        startActivity(Intent(this, SosActivity::class.java))

        // Close immediately
        finish()
    }
}