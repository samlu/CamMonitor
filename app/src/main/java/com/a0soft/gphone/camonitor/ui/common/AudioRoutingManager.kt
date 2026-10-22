package com.a0soft.gphone.camonitor.ui.common

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.a0soft.gphone.camonitor.log.AppLogger

object AudioRoutingManager {
    private const val TAG = "AudioRoutingManager"
    private var previousVoiceCallVolume: Int? = null

    fun routeToSpeaker(context: Context, adjustVolume: Boolean = true) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            val isHeadsetConnected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.availableCommunicationDevices.any {
                    it.type in listOf(
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_HEARING_AID
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                am.isWiredHeadsetOn || am.isBluetoothScoOn
            }

            if (adjustVolume && !isHeadsetConnected) {
                // Ensure voice call volume is adequately high for talkback/intercom,
                // but avoid blasting user's ears if wearing headphones
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val currentVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                if (currentVol < maxVol * 0.7f) {
                    if (previousVoiceCallVolume == null) {
                        previousVoiceCallVolume = currentVol
                    }
                    val targetVol = (maxVol * 0.85f).toInt().coerceAtLeast(1)
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetVol, 0)
                    AppLogger.i(TAG, "Adjusted STREAM_VOICE_CALL volume: $currentVol -> $targetVol (max=$maxVol, saved previous=$previousVoiceCallVolume)")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = am.availableCommunicationDevices
                val headset = devices.firstOrNull {
                    it.type in listOf(
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_HEARING_AID
                    )
                }
                if (headset != null) {
                    val success = am.setCommunicationDevice(headset)
                    AppLogger.i(TAG, "Routed audio to connected headset: ${headset.productName} (type=${headset.type}), success=$success")
                } else {
                    val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) {
                        val success = am.setCommunicationDevice(speaker)
                        AppLogger.i(TAG, "setCommunicationDevice(TYPE_BUILTIN_SPEAKER): $success")
                    } else {
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = true
                        AppLogger.i(TAG, "isSpeakerphoneOn set to true (fallback on API ${Build.VERSION.SDK_INT})")
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                if (!isHeadsetConnected) {
                    am.isSpeakerphoneOn = true
                    AppLogger.i(TAG, "isSpeakerphoneOn set to true")
                } else {
                    am.isSpeakerphoneOn = false
                    AppLogger.i(TAG, "isSpeakerphoneOn kept false due to connected headset")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to route audio", e)
        }
    }

    fun restoreNormalMode(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            previousVoiceCallVolume?.let { savedVol ->
                try {
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVol, 0)
                    AppLogger.i(TAG, "Restored STREAM_VOICE_CALL volume to $savedVol")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Could not restore voice call volume: ${e.message}")
                }
                previousVoiceCallVolume = null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
            AppLogger.i(TAG, "Restored audio mode to NORMAL")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to restore audio mode", e)
        }
    }
}
