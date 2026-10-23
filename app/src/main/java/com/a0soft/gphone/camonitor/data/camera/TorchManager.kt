package com.a0soft.gphone.camonitor.data.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.log.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TorchManager(private val context: Context) {
    private val tag = "TorchManager"
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

    private var backCameraId: String? = null

    init {
        findBackCamera()
    }

    private fun findBackCamera() {
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                if (facing == CameraCharacteristics.LENS_FACING_BACK && hasFlash) {
                    backCameraId = id
                    break
                }
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed querying camera characteristics", e)
        }
    }

    fun setTorch(enabled: Boolean) {
        _isTorchOn.value = enabled
        // 1. If WebRTC camera capture is active, update the repeating request on Camera2Session
        try {
            val app = CamMonitorApp.instance
            if (app.webRtcManager.setTorch(enabled)) {
                AppLogger.i(tag, "Torch mode set via WebRtcManager: $enabled")
                return
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed setting torch via WebRtcManager: ${e.message}")
        }

        // 2. Otherwise (camera not open by WebRTC), use standard CameraManager.setTorchMode
        val id = backCameraId ?: return
        try {
            cameraManager.setTorchMode(id, enabled)
            AppLogger.i(tag, "Torch mode set via CameraManager: $enabled")
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to toggle torch mode via CameraManager", e)
        }
    }

    fun resetState() {
        _isTorchOn.value = false
    }
}
