package com.example.galaxywatch

import android.app.Application
import android.content.Context
import android.util.Log
import com.zegocloud.uikit.prebuilt.call.ZegoUIKitPrebuiltCallConfig
import com.zegocloud.uikit.prebuilt.call.config.ZegoNotificationConfig
import com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationConfig
import com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationService
import com.zegocloud.uikit.prebuilt.call.invite.internal.ZegoUIKitPrebuiltCallConfigProvider

object ZegoCallManager {

    private const val TAG = "ZEGO_INIT"
    private const val APP_ID = 1279737711L
    private const val APP_SIGN = "50a1c85a028c5224b00ec060afda1e71159d4cfdc124e124c441a981d83cd289"

    @Volatile
    private var initializedUserId: String? = null

    fun ensureInitialized(context: Context): Boolean {
        val app = context.applicationContext as? Application ?: return false
        val sharedPrefs = context.getSharedPreferences("ElderCarePrefs", Context.MODE_PRIVATE)
        val patientId = sharedPrefs.getString("PATIENT_ID", "patient_001") ?: "patient_001"

        if (initializedUserId == patientId) {
            return true
        }

        synchronized(this) {
            if (initializedUserId == patientId) {
                return true
            }

            return try {
                val callInvitationConfig = ZegoUIKitPrebuiltCallInvitationConfig()

                val notificationConfig = ZegoNotificationConfig().apply {
                    sound = "zego_uikit_sound_call"
                    channelID = "CallInvitation"
                    channelName = "CallInvitation"
                }
                callInvitationConfig.notificationConfig = notificationConfig

                callInvitationConfig.provider = ZegoUIKitPrebuiltCallConfigProvider { _ ->
                    ZegoUIKitPrebuiltCallConfig.oneOnOneVoiceCall().also { config ->
                        config.useSpeakerWhenJoining = true
                        config.turnOnMicrophoneWhenJoining = true
                        config.topMenuBarConfig.isVisible = false
                    }
                }

                ZegoUIKitPrebuiltCallInvitationService.init(
                    app,
                    APP_ID,
                    APP_SIGN,
                    patientId,
                    "Patient ($patientId)",
                    callInvitationConfig
                )

                initializedUserId = patientId
                Log.d(TAG, "ZEGO initialized for $patientId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "ZEGO init failed: ${e.message}", e)
                false
            }
        }
    }

    fun logout() {
        synchronized(this) {
            if (initializedUserId == null) return

            try {
                ZegoUIKitPrebuiltCallInvitationService.unInit()
            } catch (e: Exception) {
                Log.e(TAG, "ZEGO unInit failed: ${e.message}", e)
            }

            initializedUserId = null
        }
    }
}
