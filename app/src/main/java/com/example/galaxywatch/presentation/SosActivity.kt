package com.example.galaxywatch.presentation

import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.galaxywatch.ZegoCallManager
import com.zegocloud.uikit.prebuilt.call.invite.widget.ZegoSendCallInvitationButton
import com.zegocloud.uikit.service.defines.ZegoUIKitUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SosActivity : ComponentActivity() {

    private var retryCount = 0
    private var sendSucceeded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ready = ZegoCallManager.ensureInitialized(applicationContext)
        if (!ready) {
            Toast.makeText(this, "SOS call service is not ready", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val themedContext =
            ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_NoActionBar)

        val sosButton = ZegoSendCallInvitationButton(themedContext).apply {
            setIsVideoCall(false)
            setInvitees(
                listOf(
                    ZegoUIKitUser("5yeapeXNTZcofATleG5ZHZ8siZt2", "Caregiver")
                )
            )

            showErrorToast(false)

            // If you already configured ZEGO offline push, uncomment this and use your real resource ID.
            // setResourceID("your_resource_id")

            setOnClickListener { errorCode, errorMessage, errorInvitees ->
                Log.d(
                    "ZEGO_CALL",
                    "SOS result code=$errorCode, message=$errorMessage, errorInvitees=$errorInvitees"
                )

                if (errorCode == 0) {
                    sendSucceeded = true
                    finish()
                    return@setOnClickListener
                }

                if (retryCount < 1) {
                    retryCount++
                    lifecycleScope.launch {
                        delay(1500L)
                        performClick()
                    }
                } else {
                    Toast.makeText(
                        this@SosActivity,
                        "SOS call failed: $errorCode $errorMessage",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }

        lifecycleScope.launch {
            delay(1500L)
            sosButton.performClick()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!sendSucceeded) {
            Log.d("ZEGO_CALL", "SosActivity closed before SOS invitation succeeded")
        }
    }
}
