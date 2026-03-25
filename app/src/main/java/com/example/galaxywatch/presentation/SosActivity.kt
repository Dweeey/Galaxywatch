package com.example.galaxywatch.presentation

import android.content.Context
import android.os.Bundle
import android.view.ContextThemeWrapper // <-- Missing import added!
import androidx.activity.ComponentActivity
import com.zegocloud.uikit.prebuilt.call.ZegoUIKitPrebuiltCallConfig
import com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationConfig
import com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationService
import com.zegocloud.uikit.prebuilt.call.invite.internal.ZegoUIKitPrebuiltCallConfigProvider
import com.zegocloud.uikit.prebuilt.call.invite.widget.ZegoSendCallInvitationButton // <-- Missing import added!
import com.zegocloud.uikit.service.defines.ZegoUIKitUser

class SosActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Initialize ZegoCloud first just in case the app was fully closed
        initZegoCloud()

        // 2. The Bulletproof Hack: Create an invisible button and "click" it programmatically
        val themedContext = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        val sosButton = ZegoSendCallInvitationButton(themedContext).apply {
            setIsVideoCall(false) // safely tells it to do a Voice Call
            setInvitees(listOf(ZegoUIKitUser("5yeapeXNTZcofATleG5ZHZ8siZt2", "Caregiver")))
        }

        // Force the click!
        sosButton.performClick()

        // 3. Immediately close this invisible activity so the Zego call screen takes over
        finish()
    }

    private fun initZegoCloud() {
        val sharedPrefs = getSharedPreferences("ElderCarePrefs", Context.MODE_PRIVATE)
        val patientId = sharedPrefs.getString("PATIENT_ID", "patient_001") ?: "patient_001"
        val appID: Long = 1279737711L
        val appSign = "50a1c85a028c5224b00ec060afda1e71159d4cfdc124e124c441a981d83cd289"

        val callInvitationConfig = ZegoUIKitPrebuiltCallInvitationConfig()

        // --- NEW: OFFLINE PUSH NOTIFICATION CONFIGURATION ---
        val notificationConfig = com.zegocloud.uikit.prebuilt.call.config.ZegoNotificationConfig()
        notificationConfig.sound = "zego_uikit_sound_call"
        notificationConfig.channelID = "CallInvitation"
        notificationConfig.channelName = "CallInvitation"

        // Attach the push config to your main config
        callInvitationConfig.notificationConfig = notificationConfig
        // ----------------------------------------------------

        callInvitationConfig.provider = ZegoUIKitPrebuiltCallConfigProvider { _ ->
            val config = ZegoUIKitPrebuiltCallConfig.oneOnOneVoiceCall()
            config.useSpeakerWhenJoining = true
            config.turnOnMicrophoneWhenJoining = true
            config.topMenuBarConfig.isVisible = false
            config
        }

        ZegoUIKitPrebuiltCallInvitationService.init(
            application, appID, appSign, patientId, "Patient ($patientId)", callInvitationConfig
        )
    }
}