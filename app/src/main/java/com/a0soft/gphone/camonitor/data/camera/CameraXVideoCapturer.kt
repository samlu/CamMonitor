package com.a0soft.gphone.camonitor.data.camera

import android.content.Context
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.a0soft.gphone.camonitor.data.model.NightModeSetting
import com.a0soft.gphone.camonitor.log.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.TextureBufferImpl
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CameraXVideoCapturer(
    private val context: Context,
    private val preferNightMode: Boolean = true
) : VideoCapturer {

    companion object {
        const val LUX_THRESHOLD_NIGHT_ENTER = 10.0f
        const val LUX_THRESHOLD_NIGHT_EXIT = 25.0f
        const val LIGHT_DEBOUNCE_DELAY_MS = 1500L

        fun selectClosestFps(
            availablePairs: List<Pair<Int, Int>>,
            targetFps: Int = 20
        ): Pair<Int, Int> {
            if (availablePairs.isEmpty()) return Pair(targetFps, targetFps)
            val valid = availablePairs.filter { it.first > 0 && it.second >= it.first }
            if (valid.isEmpty()) return availablePairs[0]

            return valid.minWith(
                compareBy<Pair<Int, Int>> { kotlin.math.abs(it.second - targetFps) }
                    .thenBy { it.first }
            )
        }

        fun selectClosestSupportedFpsRange(
            availableRanges: Array<Range<Int>>?,
            targetFps: Int = 20
        ): Range<Int> {
            if (availableRanges.isNullOrEmpty()) {
                return Range(targetFps, targetFps)
            }
            val validRanges = availableRanges.filter {
                val l = it.lower
                val u = it.upper
                l != null && u != null && l > 0 && u >= l
            }
            if (validRanges.isEmpty()) return availableRanges[0]

            return validRanges.minWith(
                compareBy<Range<Int>> { kotlin.math.abs(it.upper - targetFps) }
                    .thenBy { it.lower }
            )
        }
    }

    private val tag = "CameraXVideoCapturer"
    private val mainHandler = Handler(Looper.getMainLooper())

    var finalFps: Int = 20
        private set

    private val _streamFpsFlow = MutableStateFlow(20)
    val streamFpsFlow: StateFlow<Int> = _streamFpsFlow.asStateFlow()

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var surface: Surface? = null

    var currentTargetRotation: Int = Surface.ROTATION_0
        private set

    @Volatile
    var currentRotationDegrees: Int = 0
        private set

    private val capturerLifecycleOwner = CapturerLifecycleOwner()
    private val isCapturing = AtomicBoolean(false)

    private var lastWidth: Int = 1280
    private var lastHeight: Int = 720
    private var lastFramerate: Int = 20
    private var isTorchActive: Boolean = false
    var nightModeSetting: NightModeSetting = NightModeSetting.AUTO
        private set

    fun setNightModeSetting(setting: NightModeSetting) {
        if (nightModeSetting == setting) return
        nightModeSetting = setting
        AppLogger.i(tag, "NightModeSetting changed to: $setting")
        mainHandler.post {
            if (!isCapturing.get()) return@post
            when (setting) {
                NightModeSetting.AUTO -> {
                    startLightMonitoring()
                    val shouldBeNight = currentLux < LUX_THRESHOLD_NIGHT_ENTER
                    bindCameraSession(enableNight = shouldBeNight)
                }
                NightModeSetting.NIGHT -> {
                    stopLightMonitoring()
                    bindCameraSession(enableNight = true)
                }
                NightModeSetting.NORMAL -> {
                    stopLightMonitoring()
                    bindCameraSession(enableNight = false)
                }
            }
        }
    }

    private val _isNightModeActiveFlow = MutableStateFlow(false)
    val isNightModeActiveFlow: StateFlow<Boolean> = _isNightModeActiveFlow.asStateFlow()
    val isNightModeActive: Boolean get() = _isNightModeActiveFlow.value

    var isNightExtensionSupported: Boolean = false
        private set

    var onNightModeChanged: ((Boolean) -> Unit)? = null
    var onCameraError: ((String) -> Unit)? = null
    var onFpsDetermined: ((Int) -> Unit)? = null

    private val sensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    private var lightSensorListener: SensorEventListener? = null
    private var currentLux: Float = Float.MAX_VALUE
    private var pendingNightMode: Boolean? = null
    private var nightSwitchRunnable: Runnable? = null
    private var orientationEventListener: OrientationEventListener? = null

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        capturerObserver: CapturerObserver
    ) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
        AppLogger.i(tag, "CameraXVideoCapturer initialized with SurfaceTextureHelper")
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val helper = surfaceTextureHelper ?: run {
            AppLogger.e(tag, "startCapture failed: SurfaceTextureHelper is null")
            capturerObserver?.onCapturerStarted(false)
            return
        }

        if (isCapturing.get()) {
            AppLogger.w(tag, "startCapture called but already capturing")
            return
        }

        lastWidth = width
        lastHeight = height
        lastFramerate = framerate

        mainHandler.post {
            try {
                val initialRotation = getDeviceRotation()
                currentTargetRotation = initialRotation

                // Start WebRTC listening to incoming OpenGL texture frames
                helper.startListening { videoFrame ->
                    val texBuffer = videoFrame.buffer as? TextureBufferImpl
                    if (texBuffer != null) {
                        val (texRot, frameRot) = when (currentTargetRotation) {
                            Surface.ROTATION_90 -> Pair(-90, 0)
                            Surface.ROTATION_270 -> Pair(90, 0)
                            Surface.ROTATION_180 -> Pair(90, 90)
                            else -> Pair(-90, 90) // Surface.ROTATION_0 (Portrait)
                        }
                        val modifiedBuffer = if (texRot != 0) {
                            createTextureBufferWithModifiedTransformMatrix(
                                texBuffer,
                                textureRotationDegrees = texRot,
                                newWidth = texBuffer.width,
                                newHeight = texBuffer.height
                            )
                        } else {
                            texBuffer.retain()
                            texBuffer
                        }
                        val frame = VideoFrame(
                            modifiedBuffer,
                            frameRot,
                            videoFrame.timestampNs
                        )
                        capturerObserver?.onFrameCaptured(frame)
                        frame.release()
                    } else {
                        capturerObserver?.onFrameCaptured(videoFrame)
                    }
                }

                val providerFuture = ProcessCameraProvider.getInstance(context)
                val provider = providerFuture.get(3, TimeUnit.SECONDS)
                this.cameraProvider = provider

                val extManagerFuture = ExtensionsManager.getInstanceAsync(context, provider)
                val extManager = extManagerFuture.get(3, TimeUnit.SECONDS)
                this.extensionsManager = extManager

                val baseSelector = CameraSelector.DEFAULT_BACK_CAMERA
                val nightSupported = extManager.isExtensionAvailable(baseSelector, ExtensionMode.NIGHT)
                this.isNightExtensionSupported = nightSupported

                capturerLifecycleOwner.start()

                when (nightModeSetting) {
                    NightModeSetting.AUTO -> {
                        if (preferNightMode) {
                            startLightMonitoring()
                        }
                        val initialNight = preferNightMode && currentLux < LUX_THRESHOLD_NIGHT_ENTER
                        bindCameraSession(enableNight = initialNight)
                    }
                    NightModeSetting.NIGHT -> {
                        bindCameraSession(enableNight = true)
                    }
                    NightModeSetting.NORMAL -> {
                        bindCameraSession(enableNight = false)
                    }
                }

                isCapturing.set(true)
                startOrientationMonitoring()
                capturerObserver?.onCapturerStarted(true)
                AppLogger.i(tag, "CameraX capture started: ${lastWidth}x${lastHeight}@${lastFramerate}fps, rotation=$initialRotation (nightActive=$isNightModeActive, currentLux=$currentLux)")
            } catch (e: Exception) {
                AppLogger.e(tag, "Error starting CameraX capture", e)
                isCapturing.set(false)
                stopOrientationMonitoring()
                stopLightMonitoring()
                capturerObserver?.onCapturerStarted(false)
                onCameraError?.invoke(e.message ?: "CameraX startCapture failed")
            }
        }
    }

    private fun bindCameraSession(enableNight: Boolean) {
        val provider = cameraProvider ?: return
        val extManager = extensionsManager ?: return
        val helper = surfaceTextureHelper ?: return

        val baseSelector = CameraSelector.DEFAULT_BACK_CAMERA
        val targetSelector: CameraSelector
        val actuallyNight: Boolean

        if (enableNight) {
            if (isNightExtensionSupported) {
                targetSelector = extManager.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.NIGHT)
                AppLogger.i(tag, "Binding CameraX with ExtensionMode.NIGHT")
            } else {
                targetSelector = baseSelector
                AppLogger.i(tag, "Binding CameraX with Standard CameraSelector (applying Camera2 low-light fallback)")
            }
            actuallyNight = true
        } else {
            targetSelector = baseSelector
            actuallyNight = false
            AppLogger.i(tag, "Binding CameraX with Standard CameraSelector (Normal mode)")
        }

        val isLandscape = currentTargetRotation == Surface.ROTATION_90 || currentTargetRotation == Surface.ROTATION_270
        val boundWidth = if (isLandscape) maxOf(lastWidth, lastHeight) else minOf(lastWidth, lastHeight)
        val boundHeight = if (isLandscape) minOf(lastWidth, lastHeight) else maxOf(lastWidth, lastHeight)

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(boundWidth, boundHeight),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()

        val previewBuilder = Preview.Builder()
            .setTargetRotation(currentTargetRotation)
            .setResolutionSelector(resolutionSelector)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val backCameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: "0"
        val chars = cameraManager?.getCameraCharacteristics(backCameraId)
        val availableFpsRanges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val selectedFpsRange = selectClosestSupportedFpsRange(availableFpsRanges, lastFramerate)
        finalFps = selectedFpsRange.upper
        _streamFpsFlow.value = finalFps
        onFpsDetermined?.invoke(finalFps)
        AppLogger.i(tag, "Selected hardware AE FPS range: $selectedFpsRange, finalFps=$finalFps (target=$lastFramerate)")

        if (enableNight && !isNightExtensionSupported) {
            AppLogger.i(tag, "Applying Camera2 low-light fallback")
            configureLowLightFallback(previewBuilder, chars, selectedFpsRange)
        } else {
            previewBuilder.setTargetFpsRange(selectedFpsRange)
        }

        val newPreview = previewBuilder.build()
        this.preview = newPreview

        val mainExecutor = ContextCompat.getMainExecutor(context)
        newPreview.setSurfaceProvider(mainExecutor) { request ->
            val res = request.resolution
            AppLogger.i(tag, "CameraX Surface requested: ${res.width}x${res.height} (night=$enableNight)")
            helper.setTextureSize(res.width, res.height)
            val surfaceTexture = helper.surfaceTexture
            surfaceTexture.setDefaultBufferSize(res.width, res.height)

            this.surface?.release()
            val newSurface = Surface(surfaceTexture)
            this.surface = newSurface

            request.setTransformationInfoListener(mainExecutor) { transformationInfo ->
                currentRotationDegrees = transformationInfo.rotationDegrees
                AppLogger.i(tag, "TransformationInfo: rotationDegrees=${transformationInfo.rotationDegrees}, cropRect=${transformationInfo.cropRect}, targetRot=$currentTargetRotation")
            }
            request.provideSurface(newSurface, mainExecutor) { result ->
                AppLogger.d(tag, "CameraX Surface closed/invalidated: ${result.resultCode}")
            }
        }

        provider.unbindAll()
        camera = provider.bindToLifecycle(capturerLifecycleOwner, targetSelector, newPreview)
        _isNightModeActiveFlow.value = actuallyNight
        onNightModeChanged?.invoke(actuallyNight)

        if (isTorchActive) {
            camera?.cameraControl?.enableTorch(true)
        }
        AppLogger.i(tag, "Camera session bound: nightActive=$actuallyNight (supported=$isNightExtensionSupported, bound=${boundWidth}x${boundHeight})")
    }

    private fun startLightMonitoring() {
        val sm = sensorManager ?: run {
            AppLogger.w(tag, "SensorManager not available")
            return
        }
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: run {
            AppLogger.w(tag, "Ambient Light Sensor (Sensor.TYPE_LIGHT) not available on this device")
            return
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val lux = event?.values?.getOrNull(0) ?: return
                currentLux = lux
                handleLuxChanged(lux)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // No-op
            }
        }
        lightSensorListener = listener
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        AppLogger.i(tag, "Ambient Light monitoring started with sensor: ${sensor.name}")
    }

    private fun stopLightMonitoring() {
        lightSensorListener?.let {
            sensorManager?.unregisterListener(it)
            lightSensorListener = null
        }
        nightSwitchRunnable?.let {
            mainHandler.removeCallbacks(it)
            nightSwitchRunnable = null
        }
        pendingNightMode = null
    }

    private fun handleLuxChanged(lux: Float) {
        if (!isCapturing.get()) return
        val isCurrentlyNight = _isNightModeActiveFlow.value
        val shouldBeNight = when {
            lux < LUX_THRESHOLD_NIGHT_ENTER -> true
            lux > LUX_THRESHOLD_NIGHT_EXIT -> false
            else -> isCurrentlyNight // Within hysteresis deadband [10, 25] lux
        }

        if (shouldBeNight == isCurrentlyNight) {
            nightSwitchRunnable?.let { mainHandler.removeCallbacks(it) }
            nightSwitchRunnable = null
            pendingNightMode = null
            return
        }

        if (pendingNightMode == shouldBeNight) {
            return
        }

        nightSwitchRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingNightMode = shouldBeNight

        val runnable = Runnable {
            nightSwitchRunnable = null
            pendingNightMode = null
            if (!isCapturing.get()) return@Runnable
            AppLogger.i(tag, "Ambient light threshold crossed (lux=$lux): switching Night mode -> $shouldBeNight")
            try {
                bindCameraSession(enableNight = shouldBeNight)
            } catch (e: Exception) {
                AppLogger.e(tag, "Error switching camera night mode to $shouldBeNight", e)
            }
        }
        nightSwitchRunnable = runnable
        mainHandler.postDelayed(runnable, LIGHT_DEBOUNCE_DELAY_MS)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun Preview.Builder.setTargetFpsRange(fpsRange: Range<Int>) {
        val extender = Camera2Interop.Extender(this)
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            fpsRange
        )
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun configureLowLightFallback(builder: Preview.Builder, chars: CameraCharacteristics?, fpsRange: Range<Int>) {
        if (chars == null) return
        val extender = Camera2Interop.Extender(builder)

        // 1. Scene Mode Night
        val sceneModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
        if (sceneModes?.contains(CaptureRequest.CONTROL_SCENE_MODE_NIGHT) == true) {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
            AppLogger.i(tag, "Fallback: Applied CONTROL_SCENE_MODE_NIGHT")
        }

        // 2. Auto Exposure Priority for frame rate reduction in low light
        val aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
        if (aeModes?.contains(CaptureRequest.CONTROL_AE_MODE_ON) == true) {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
        }

        // 3. Hardware-validated AE FPS Range
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        AppLogger.i(tag, "Fallback: Applied hardware-validated AE target FPS range: $fpsRange")

        // 4. Optical/Video Stabilization
        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (oisModes?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
            extender.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
        }

        // 5. High-quality / Fast Noise Reduction
        val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        when {
            nrModes?.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) == true -> {
                extender.setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST
                )
            }
            nrModes?.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true -> {
                extender.setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                )
            }
        }
    }

    private fun getDeviceRotation(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display?.rotation
            } catch (e: Exception) {
                null
            } ?: windowManager?.defaultDisplay?.rotation
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.rotation
        }

        if (rotation == null || rotation == Surface.ROTATION_0) {
            val config = context.resources.configuration
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return Surface.ROTATION_90
            }
        }
        return rotation ?: Surface.ROTATION_0
    }

    fun setTargetRotation(rotation: Int) {
        updateTargetRotation(rotation)
    }

    private fun updateTargetRotation(newRotation: Int) {
        if (!isCapturing.get()) return
        if (newRotation == currentTargetRotation) return
        currentTargetRotation = newRotation
        preview?.targetRotation = newRotation
        AppLogger.i(tag, "Orientation changed: currentTargetRotation=$newRotation (targetRotation updated on preview)")
    }

    private fun createTextureBufferWithModifiedTransformMatrix(
        buffer: TextureBufferImpl,
        textureRotationDegrees: Int,
        newWidth: Int,
        newHeight: Int
    ): VideoFrame.TextureBuffer {
        val transformMatrix = android.graphics.Matrix().apply {
            preTranslate(0.5f, 0.5f)
            preRotate(textureRotationDegrees.toFloat())
            preTranslate(-0.5f, -0.5f)
        }
        return buffer.applyTransformMatrix(transformMatrix, newWidth, newHeight)
    }

    private fun startOrientationMonitoring() {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val newRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                updateTargetRotation(newRotation)
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
            orientationEventListener = listener
        }
    }

    private fun stopOrientationMonitoring() {
        orientationEventListener?.disable()
        orientationEventListener = null
    }

    override fun stopCapture() {
        if (!isCapturing.getAndSet(false)) {
            return
        }

        stopLightMonitoring()
        stopOrientationMonitoring()

        val stopRunnable = Runnable {
            try {
                capturerLifecycleOwner.stop()
                cameraProvider?.unbindAll()
                camera = null
                preview = null

                surfaceTextureHelper?.stopListening()
                surface?.release()
                surface = null

                _isNightModeActiveFlow.value = false
                onNightModeChanged?.invoke(false)

                capturerObserver?.onCapturerStopped()
                AppLogger.i(tag, "CameraX capture stopped cleanly")
            } catch (e: Exception) {
                AppLogger.e(tag, "Error stopping CameraX capture", e)
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopRunnable.run()
        } else {
            val latch = CountDownLatch(1)
            mainHandler.post {
                try {
                    stopRunnable.run()
                } finally {
                    latch.countDown()
                }
            }
            try {
                latch.await(2, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        if (isCapturing.get()) {
            stopCapture()
            startCapture(width, height, framerate)
        }
    }

    override fun dispose() {
        stopCapture()
        surfaceTextureHelper = null
        capturerObserver = null
        AppLogger.i(tag, "CameraXVideoCapturer disposed")
    }

    override fun isScreencast(): Boolean = false

    fun setTorch(enabled: Boolean): Boolean {
        isTorchActive = enabled
        val control = camera?.cameraControl ?: run {
            AppLogger.w(tag, "Cannot toggle torch: CameraControl is null")
            return false
        }
        control.enableTorch(enabled)
        AppLogger.i(tag, "Torch toggled via CameraControl: enabled=$enabled")
        return true
    }

    private class CapturerLifecycleOwner : LifecycleOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry

        fun start() {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
