package com.a0soft.gphone.camonitor.data.webrtc

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.util.Range
import android.view.Surface
import com.a0soft.gphone.camonitor.data.model.ControlCommand
import com.a0soft.gphone.camonitor.log.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.*
import java.nio.ByteBuffer

class WebRtcManager(private val context: Context) {
    private val tag = "WebRtcManager"

    val eglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null

    // Sender state (PA)
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localRenderer: SurfaceViewRenderer? = null

    // Active PeerConnections mapped by peerId (max 2 for PA)
    private val peerConnections = java.util.concurrent.ConcurrentHashMap<String, PeerConnection>()
    private val peerConnectionStates = java.util.concurrent.ConcurrentHashMap<String, PeerConnection.PeerConnectionState>()
    private val dataChannels = java.util.concurrent.ConcurrentHashMap<String, DataChannel>()

    // Flows
    private val _incomingIceCandidates = MutableSharedFlow<Pair<String, IceCandidate>>(extraBufferCapacity = 32)
    val incomingIceCandidates: SharedFlow<Pair<String, IceCandidate>> = _incomingIceCandidates.asSharedFlow()

    private val _dataChannelCommands = MutableSharedFlow<Pair<String, ControlCommand>>(extraBufferCapacity = 32)
    val dataChannelCommands: SharedFlow<Pair<String, ControlCommand>> = _dataChannelCommands.asSharedFlow()

    private val _connectionState = MutableStateFlow<PeerConnection.PeerConnectionState>(PeerConnection.PeerConnectionState.NEW)
    val connectionState: StateFlow<PeerConnection.PeerConnectionState> = _connectionState.asStateFlow()

    private val _activeViewersCount = MutableStateFlow(0)
    val activeViewersCount: StateFlow<Int> = _activeViewersCount.asStateFlow()

    private val _streamFps = MutableStateFlow(20)
    val streamFps: StateFlow<Int> = _streamFps.asStateFlow()

    private val _remoteStreamFps = MutableStateFlow(20)
    val remoteStreamFps: StateFlow<Int> = _remoteStreamFps.asStateFlow()

    fun setRemoteFps(fps: Int) {
        _remoteStreamFps.value = fps
    }

    // Receiver state (PB & PA)
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private val remoteAudioTracks = java.util.concurrent.ConcurrentHashMap<String, AudioTrack>()
    private var isRemoteAudioEnabled = false
    private var remoteRenderer: SurfaceViewRenderer? = null
    @Volatile
    private var remoteVideoRotationDegrees: Int = 0

    private val remoteVideoSink = VideoSink { frame ->
        val renderer = remoteRenderer ?: return@VideoSink
        val userRot = remoteVideoRotationDegrees
        if (userRot == 0) {
            renderer.onFrame(frame)
        } else {
            val adjustedRotation = ((frame.rotation + userRot) % 360 + 360) % 360
            val rotatedFrame = VideoFrame(frame.buffer, adjustedRotation, frame.timestampNs)
            renderer.onFrame(rotatedFrame)
        }
    }

    // Viewer PTT audio capture state (PB)
    private var viewerAudioSource: AudioSource? = null
    private var viewerAudioTrack: AudioTrack? = null
    private var isPttActive = false

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        AppLogger.i(tag, "WebRTC PeerConnectionFactory initialized")
    }

    // ----------------------------------------------------
    // SENDER (PA) SETUP
    // ----------------------------------------------------

    @Volatile
    private var isCameraCapturing = false
    var onCameraUnexpectedClosed: (() -> Unit)? = null
    private var cameraXCapturer: com.a0soft.gphone.camonitor.data.camera.CameraXVideoCapturer? = null

    private val _isNightModeActive = MutableStateFlow(false)
    val isNightModeActive: StateFlow<Boolean> = _isNightModeActive.asStateFlow()

    private val _nightModeSetting = MutableStateFlow(
        try {
            com.a0soft.gphone.camonitor.CamMonitorApp.instance.securityRepository.getNightModeSetting()
        } catch (_: Exception) {
            com.a0soft.gphone.camonitor.data.model.NightModeSetting.AUTO
        }
    )
    val nightModeSetting: StateFlow<com.a0soft.gphone.camonitor.data.model.NightModeSetting> = _nightModeSetting.asStateFlow()

    val isNightExtensionSupported: Boolean
        get() = cameraXCapturer?.isNightExtensionSupported ?: false

    fun setNightModeSetting(setting: com.a0soft.gphone.camonitor.data.model.NightModeSetting) {
        _nightModeSetting.value = setting
        cameraXCapturer?.setNightModeSetting(setting)
        try {
            com.a0soft.gphone.camonitor.CamMonitorApp.instance.securityRepository.setNightModeSetting(setting)
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to persist night mode setting: ${e.message}")
        }
    }

    private fun releaseCameraHardwareOnly() {
        try {
            isCameraCapturing = false
            _isNightModeActive.value = false
            cameraXCapturer = null
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            localRenderer?.let { localVideoTrack?.removeSink(it) }
            localVideoTrack?.dispose()
            localVideoTrack = null
            videoSource?.dispose()
            videoSource = null
        } catch (e: Exception) {
            AppLogger.e(tag, "Error releasing camera hardware only", e)
        }
    }

    fun startCameraCapture(width: Int = 1280, height: Int = 720, fps: Int = 20) {
        val factory = peerConnectionFactory ?: return

        if (localVideoTrack != null && isCameraCapturing) return // Already running healthy

        if (localVideoTrack != null && !isCameraCapturing) {
            AppLogger.w(tag, "localVideoTrack existed but capturer was not active. Releasing and restarting camera hardware.")
            releaseCameraHardwareOnly()
        }

        val capturer = com.a0soft.gphone.camonitor.data.camera.CameraXVideoCapturer(context, preferNightMode = true).apply {
            onCameraError = { errorDescription ->
                AppLogger.e(tag, "Camera error: $errorDescription")
                val wasCapturing = isCameraCapturing
                isCameraCapturing = false
                if (wasCapturing) {
                    onCameraUnexpectedClosed?.invoke()
                }
            }
            onNightModeChanged = { active ->
                _isNightModeActive.value = active
                broadcastDataChannelCommand(
                    ControlCommand.NightModeReport(
                        mode = _nightModeSetting.value.name,
                        isNightActive = active
                    )
                )
            }
            onFpsDetermined = { fps ->
                _streamFps.value = fps
                videoSource?.adaptOutputFormat(width, height, fps)
                peerConnections.values.forEach { pc ->
                    configureVideoSenders(pc)
                }
                broadcastDataChannelCommand(ControlCommand.FpsReport(fps))
            }
        }
        capturer.setNightModeSetting(_nightModeSetting.value)
        cameraXCapturer = capturer
        _isNightModeActive.value = capturer.isNightModeActive
        videoCapturer = capturer

        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        surfaceTextureHelper = helper
        val vSource = factory.createVideoSource(capturer.isScreencast)
        videoSource = vSource
        vSource.adaptOutputFormat(width, height, capturer.finalFps)

        capturer.initialize(helper, context, vSource.capturerObserver)
        capturer.startCapture(width, height, fps)
        isCameraCapturing = true
        _streamFps.value = capturer.finalFps

        val track = factory.createVideoTrack("CAM_VIDEO_TRACK", vSource).apply {
            setEnabled(true)
        }
        localVideoTrack = track
        localRenderer?.let { track.addSink(it) }

        // Setup audio
        if (localAudioTrack == null) {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }
            val aSource = factory.createAudioSource(audioConstraints)
            audioSource = aSource
            localAudioTrack = factory.createAudioTrack("CAM_AUDIO_TRACK", aSource).apply {
                setEnabled(true)
            }
        }

        // Re-attach to any active PeerConnections if they exist
        peerConnections.values.forEach { pc ->
            localVideoTrack?.let { vTrack ->
                try {
                    val videoSender = pc.senders?.firstOrNull {
                        try {
                            it.track()?.kind() == "video"
                        } catch (e: Throwable) {
                            false
                        }
                    }
                    if (videoSender != null) {
                        videoSender.setTrack(vTrack, false)
                        configureVideoSender(videoSender)
                    } else {
                        val sender = pc.addTrack(vTrack, listOf("CAM_STREAM"))
                        configureVideoSender(sender)
                    }
                } catch (e: Throwable) {
                    AppLogger.w(tag, "Failed to re-attach video track to peer connection: ${e.message}")
                }
            }
        }

        AppLogger.i(tag, "Camera capture started: ${width}x${height}@${fps}fps (back camera)")
    }

    fun configureVideoSender(sender: RtpSender?) {
        sender ?: return
        try {
            val parameters = sender.parameters ?: return
            parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            if (parameters.encodings != null) {
                parameters.encodings.forEach { encoding ->
                    encoding.scaleResolutionDownBy = 1.0
                    encoding.maxFramerate = _streamFps.value
                    encoding.minBitrateBps = 800_000
                    encoding.maxBitrateBps = 2_500_000
                }
            }
            val result = sender.setParameters(parameters)
            AppLogger.i(tag, "Configured video sender parameters: degradationPreference=MAINTAIN_RESOLUTION, maxFramerate=${_streamFps.value}, result=$result")
        } catch (e: Throwable) {
            AppLogger.w(tag, "Failed to configure video sender parameters: ${e.message}")
        }
    }

    fun configureVideoSenders(pc: PeerConnection?) {
        pc ?: return
        try {
            pc.senders?.forEach { sender ->
                try {
                    if (sender.track()?.kind() == "video") {
                        configureVideoSender(sender)
                    }
                } catch (e: Throwable) {
                    AppLogger.w(tag, "Failed checking sender track: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            AppLogger.w(tag, "Failed to configure video senders: ${e.message}")
        }
    }

    fun stopCameraCapture() {
        try {
            isCameraCapturing = false
            peerConnections.values.forEach { it.dispose() }
            peerConnections.clear()
            dataChannels.values.forEach { it.dispose() }
            dataChannels.clear()
            remoteDescriptionSetPeers.clear()
            _activeViewersCount.value = 0

            releaseCameraHardwareOnly()

            localAudioTrack?.dispose()
            localAudioTrack = null
            audioSource?.dispose()
            audioSource = null

            AppLogger.i(tag, "Camera capture stopped and hardware released")
        } catch (e: Exception) {
            AppLogger.e(tag, "Error stopping camera capture", e)
        }
    }

    fun setTorch(enabled: Boolean): Boolean {
        cameraXCapturer?.let {
            return it.setTorch(enabled)
        }
        val capturer = videoCapturer ?: return false
        return try {
            val cameraCapturerClass = capturer.javaClass.superclass ?: Class.forName("org.webrtc.CameraCapturer")
            val sessionField = cameraCapturerClass.getDeclaredField("currentSession").apply {
                isAccessible = true
            }
            val session = sessionField.get(capturer) ?: return false
            val sessionClass = session.javaClass

            val deviceField = sessionClass.getDeclaredField("cameraDevice").apply { isAccessible = true }
            val sessionCaptureField = sessionClass.getDeclaredField("captureSession").apply { isAccessible = true }
            val surfaceField = sessionClass.getDeclaredField("surface").apply { isAccessible = true }
            val handlerField = sessionClass.getDeclaredField("cameraThreadHandler").apply { isAccessible = true }
            val captureFormatField = sessionClass.getDeclaredField("captureFormat").apply { isAccessible = true }
            val fpsUnitFactorField = sessionClass.getDeclaredField("fpsUnitFactor").apply { isAccessible = true }

            val cameraDevice = deviceField.get(session) as? CameraDevice ?: return false
            val captureSession = sessionCaptureField.get(session) as? CameraCaptureSession ?: return false
            val surface = surfaceField.get(session) as? Surface ?: return false
            val handler = handlerField.get(session) as? Handler ?: return false
            val captureFormat = captureFormatField.get(session) as? CameraEnumerationAndroid.CaptureFormat
            val fpsUnitFactor = fpsUnitFactorField.getInt(session)

            handler.post {
                try {
                    val builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    if (captureFormat != null && fpsUnitFactor > 0) {
                        val fpsRange = Range(
                            captureFormat.framerate.min / fpsUnitFactor,
                            captureFormat.framerate.max / fpsUnitFactor
                        )
                        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                    }
                    builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    if (enabled) {
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    } else {
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }
                    builder.addTarget(surface)
                    captureSession.setRepeatingRequest(builder.build(), null, handler)
                    AppLogger.i(tag, "Torch mode successfully updated on active Camera2Session: enabled=$enabled")
                } catch (e: Exception) {
                    AppLogger.e(tag, "Failed to update torch on active Camera2Session", e)
                }
            }
            true
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed accessing Camera2Session for torch: ${e.message}")
            false
        }
    }

    // ----------------------------------------------------
    // PEER CONNECTION MANAGEMENT
    // ----------------------------------------------------

    fun createPeerConnection(peerId: String, onIceCandidateGenerated: (IceCandidate) -> Unit): PeerConnection? {
        val factory = peerConnectionFactory ?: run {
            AppLogger.e(tag, "Cannot create PeerConnection: PeerConnectionFactory is null")
            return null
        }
        closePeer(peerId, keepPendingCandidates = true)
        AppLogger.i(tag, "Creating new PeerConnection for $peerId with ${iceServers.size} ICE servers")

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val typeMatch = Regex("typ\\s+(\\w+)").find(candidate.sdp)?.groupValues?.getOrNull(1) ?: "unknown"
                AppLogger.i(tag, "Local ICE Candidate generated for $peerId: type=$typeMatch, mid=${candidate.sdpMid}, index=${candidate.sdpMLineIndex}")
                onIceCandidateGenerated(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                AppLogger.i(tag, "IceCandidates removed for $peerId: count=${candidates?.size ?: 0}")
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                AppLogger.i(tag, "SignalingState for $peerId: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                AppLogger.i(tag, "IceConnectionState for $peerId: $state")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                AppLogger.i(tag, "ConnectionChange for $peerId: $newState")
                peerConnectionStates[peerId] = newState
                _connectionState.value = newState
                updateActiveViewersCount()
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                AppLogger.i(tag, "IceGatheringState for $peerId: $state")
            }

            override fun onAddStream(stream: MediaStream?) {}

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    AppLogger.i(tag, "Remote VideoTrack received: id=${track.id()}, state=${track.state()}, enabled=${track.enabled()}")
                    track.setEnabled(true)
                    remoteVideoTrack?.let { oldTrack ->
                        oldTrack.removeSink(remoteVideoSink)
                    }
                    remoteVideoTrack = track
                    track.removeSink(remoteVideoSink)
                    track.addSink(remoteVideoSink)
                    AppLogger.i(tag, "Attached remote VideoTrack to remoteVideoSink (renderer attached=${remoteRenderer != null})")
                } else if (track is AudioTrack) {
                    AppLogger.i(tag, "Remote AudioTrack received from $peerId: id=${track.id()}, state=${track.state()}")
                    remoteAudioTracks[peerId] = track
                    remoteAudioTrack = track
                    val isCamera = localVideoTrack != null
                    if (isCamera) {
                        track.setEnabled(true)
                        com.a0soft.gphone.camonitor.ui.common.AudioRoutingManager.routeToSpeaker(context)
                        AppLogger.i(tag, "Camera (PA) enabled remote AudioTrack from viewer $peerId and routed to speaker")
                    } else {
                        track.setEnabled(isRemoteAudioEnabled)
                        AppLogger.i(tag, "Viewer (PB) remote AudioTrack enabled: $isRemoteAudioEnabled")
                    }
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dataChannel: DataChannel) {
                AppLogger.i(tag, "DataChannel received from $peerId: label=${dataChannel.label()}, id=${dataChannel.id()}")
                setupDataChannel(peerId, dataChannel)
            }

            override fun onRenegotiationNeeded() {
                AppLogger.d(tag, "Renegotiation needed for $peerId")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                AppLogger.i(tag, "IceConnection receiving for $peerId: receiving=$receiving")
            }
        })

        if (pc != null) {
            // Attach local tracks if streaming as sender (PA)
            localVideoTrack?.let { vTrack ->
                val sender = pc.addTrack(vTrack, listOf("CAM_STREAM"))
                configureVideoSender(sender)
            }
            localAudioTrack?.let { pc.addTrack(it, listOf("CAM_STREAM")) }

            peerConnections[peerId] = pc
            peerConnectionStates[peerId] = PeerConnection.PeerConnectionState.NEW
            updateActiveViewersCount()
        }

        return pc
    }

    private fun setupDataChannel(peerId: String, dc: DataChannel) {
        dataChannels[peerId] = dc
        fun sendInitialStatusIfOpen() {
            if (dc.state() == DataChannel.State.OPEN) {
                val isCamera = localVideoTrack != null
                if (isCamera) {
                    try {
                        val currentName = com.a0soft.gphone.camonitor.CamMonitorApp.instance.securityRepository.getDeviceName()
                        val renameCmd = ControlCommand.Rename(currentName)
                        val renameBytes = renameCmd.toJson().toByteArray(Charsets.UTF_8)
                        dc.send(DataChannel.Buffer(ByteBuffer.wrap(renameBytes), false))
                        AppLogger.i(tag, "Sent initial Rename command on DataChannel open to $peerId: $currentName")
                    } catch (e: Exception) {
                        AppLogger.e(tag, "Failed to send initial Rename command", e)
                    }

                    try {
                        val bInfo = com.a0soft.gphone.camonitor.ui.common.BatteryHelper.getBatteryInfo(context)
                        if (bInfo.percentage >= 0) {
                            val cmd = ControlCommand.Battery(bInfo.percentage, bInfo.isCharging)
                            val bytes = cmd.toJson().toByteArray(Charsets.UTF_8)
                            dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
                        }
                    } catch (e: Exception) {
                        AppLogger.e(tag, "Failed to send initial Battery command", e)
                    }

                    try {
                        val fps = _streamFps.value
                        val cmd = ControlCommand.FpsReport(fps)
                        val bytes = cmd.toJson().toByteArray(Charsets.UTF_8)
                        dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
                        AppLogger.i(tag, "Sent initial FpsReport on DataChannel open to $peerId: $fps fps")
                    } catch (e: Exception) {
                        AppLogger.e(tag, "Failed to send initial FpsReport command", e)
                    }

                    try {
                        val modeCmd = ControlCommand.NightModeReport(
                            mode = _nightModeSetting.value.name,
                            isNightActive = _isNightModeActive.value
                        )
                        val bytes = modeCmd.toJson().toByteArray(Charsets.UTF_8)
                        dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
                        AppLogger.i(tag, "Sent initial NightModeReport on DataChannel open to $peerId: mode=${modeCmd.mode}, active=${modeCmd.isNightActive}")
                    } catch (e: Exception) {
                        AppLogger.e(tag, "Failed to send initial NightModeReport command", e)
                    }
                }
            }
        }

        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                AppLogger.i(tag, "DataChannel state for $peerId: ${dc.state()}")
                sendInitialStatusIfOpen()
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                val jsonStr = String(data, Charsets.UTF_8)
                val cmd = ControlCommand.fromJson(jsonStr)
                if (cmd != null) {
                    AppLogger.i(tag, "Received DataChannel command from $peerId: $jsonStr")
                    if (cmd is ControlCommand.FpsReport) {
                        _remoteStreamFps.value = cmd.fps
                    }
                    _dataChannelCommands.tryEmit(Pair(peerId, cmd))
                }
            }
        })
        sendInitialStatusIfOpen()
    }

    fun createDataChannelForSender(peerId: String): DataChannel? {
        val pc = peerConnections[peerId] ?: return null
        val init = DataChannel.Init().apply {
            ordered = true
        }
        val dc = pc.createDataChannel("control", init)
        if (dc != null) {
            setupDataChannel(peerId, dc)
        }
        return dc
    }

    fun sendDataChannelCommand(peerId: String, cmd: ControlCommand): Boolean {
        val dc = dataChannels[peerId] ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val bytes = cmd.toJson().toByteArray(Charsets.UTF_8)
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(bytes), false)
        return dc.send(buffer)
    }

    fun broadcastDataChannelCommand(cmd: ControlCommand) {
        dataChannels.keys.forEach { peerId ->
            sendDataChannelCommand(peerId, cmd)
        }
    }

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        localRenderer = renderer
        localVideoTrack?.let { track ->
            track.removeSink(renderer)
            track.addSink(renderer)
        }
    }

    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        if (localRenderer == renderer) {
            localRenderer = null
        }
        localVideoTrack?.removeSink(renderer)
    }

    fun setRemoteVideoRotation(degrees: Int) {
        val normalized = ((degrees % 360) + 360) % 360
        remoteVideoRotationDegrees = normalized
        AppLogger.i(tag, "setRemoteVideoRotation: degrees=$degrees, normalized=$normalized")
    }

    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        remoteVideoTrack?.let { track ->
            track.removeSink(remoteVideoSink)
            track.addSink(remoteVideoSink)
            AppLogger.i(tag, "attachRemoteRenderer: Connected remoteVideoTrack to remoteVideoSink")
        }
    }

    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        if (remoteRenderer == renderer) {
            remoteRenderer = null
        }
        remoteVideoTrack?.removeSink(remoteVideoSink)
    }

    fun setRemoteAudioEnabled(enabled: Boolean) {
        isRemoteAudioEnabled = enabled
        remoteAudioTrack?.setEnabled(enabled)
        AppLogger.i(tag, "Remote audio track enabled: $enabled")
    }

    val isViewerAudioTrackActive: Boolean get() = viewerAudioTrack != null

    fun startViewerAudioCapture(): AudioTrack? {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AppLogger.w(tag, "Cannot start viewer audio capture: RECORD_AUDIO permission not granted")
            return null
        }
        val factory = peerConnectionFactory ?: run {
            AppLogger.e(tag, "Cannot start viewer audio capture: PeerConnectionFactory is null")
            return null
        }
        if (viewerAudioTrack != null) return viewerAudioTrack

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val aSource = factory.createAudioSource(audioConstraints)
        viewerAudioSource = aSource
        val track = factory.createAudioTrack("VIEWER_PTT_AUDIO_TRACK", aSource).apply {
            setEnabled(false) // Initially muted until PTT is pressed
        }
        viewerAudioTrack = track
        AppLogger.i(tag, "Viewer PTT audio capture started (initially muted)")
        return track
    }

    fun stopViewerAudioCapture() {
        try {
            viewerAudioTrack?.setEnabled(false)
            viewerAudioTrack?.dispose()
            viewerAudioTrack = null
            viewerAudioSource?.dispose()
            viewerAudioSource = null
            isPttActive = false
            AppLogger.i(tag, "Viewer PTT audio capture stopped")
        } catch (e: Exception) {
            AppLogger.e(tag, "Error stopping viewer audio capture", e)
        }
    }

    fun setPttActive(active: Boolean) {
        isPttActive = active
        viewerAudioTrack?.setEnabled(active)
        AppLogger.i(tag, "Viewer PTT active set to: $active (track enabled=${viewerAudioTrack?.enabled()})")
    }

    fun onPttCommand(peerId: String, isStart: Boolean) {
        val track = remoteAudioTracks[peerId] ?: remoteAudioTrack
        track?.setEnabled(isStart)
        if (isStart) {
            com.a0soft.gphone.camonitor.ui.common.AudioRoutingManager.routeToSpeaker(context)
        }
        AppLogger.i(tag, "onPttCommand: peerId=$peerId, isStart=$isStart, trackId=${track?.id()}")
    }

    private val remoteDescriptionSetPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pendingRemoteIceCandidates = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<IceCandidate>>()
    private val addedRemoteIceCandidates = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    internal fun getAddedRemoteIceCandidates(peerId: String): Set<String>? = addedRemoteIceCandidates[peerId]?.toSet()
    internal fun getPendingRemoteIceCandidates(peerId: String): List<IceCandidate>? = pendingRemoteIceCandidates[peerId]?.toList()

    fun addRemoteIceCandidate(peerId: String, candidate: IceCandidate) {
        val seen = addedRemoteIceCandidates.getOrPut(peerId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
        val candidateKey = "${candidate.sdpMid}:${candidate.sdpMLineIndex}:${candidate.sdp}"
        if (!seen.add(candidateKey)) {
            // Duplicate candidate ignored
            return
        }

        val pc = peerConnections[peerId]
        val typeMatch = Regex("typ\\s+(\\w+)").find(candidate.sdp)?.groupValues?.getOrNull(1) ?: "unknown"
        val isRemoteDescSet = remoteDescriptionSetPeers.contains(peerId)

        if (pc != null && isRemoteDescSet) {
            try {
                val added = pc.addIceCandidate(candidate)
                AppLogger.i(tag, "Added remote ICE candidate directly for $peerId: type=$typeMatch, mid=${candidate.sdpMid}, added=$added")
            } catch (e: Throwable) {
                AppLogger.w(tag, "Failed adding candidate directly to pc for $peerId: ${e.message}")
            }
        } else {
            val list = pendingRemoteIceCandidates.getOrPut(peerId) { java.util.concurrent.CopyOnWriteArrayList() }
            list.add(candidate)
            AppLogger.i(tag, "Buffered remote ICE candidate for $peerId: type=$typeMatch, mid=${candidate.sdpMid} (pending count=${list.size}, pc=${if (pc != null) "exists(waiting remoteDesc)" else "null"})")
            if (remoteDescriptionSetPeers.contains(peerId)) {
                drainRemoteIceCandidates(peerId)
            }
        }
    }

    fun drainRemoteIceCandidates(peerId: String) {
        remoteDescriptionSetPeers.add(peerId)
        val pc = peerConnections[peerId] ?: run {
            AppLogger.w(tag, "Cannot drain ICE candidates: PeerConnection not found for $peerId")
            return
        }
        val pending = pendingRemoteIceCandidates.remove(peerId)
        if (pending.isNullOrEmpty()) {
            AppLogger.i(tag, "No buffered ICE candidates to drain for $peerId")
            return
        }
        AppLogger.i(tag, "Draining ${pending.size} buffered ICE candidates for $peerId into PeerConnection")
        pending.forEach { cand ->
            try {
                val added = pc.addIceCandidate(cand)
                AppLogger.d(tag, "Drained candidate mid=${cand.sdpMid} (added=$added)")
            } catch (e: Throwable) {
                AppLogger.w(tag, "Failed adding drained candidate for $peerId: ${e.message}")
            }
        }
    }

    private fun updateActiveViewersCount() {
        val count = peerConnectionStates.values.count {
            it == PeerConnection.PeerConnectionState.CONNECTED
        }
        _activeViewersCount.value = count
    }

    fun hasPeer(peerId: String): Boolean = peerConnections.containsKey(peerId)

    fun getPeerConnectionState(peerId: String): PeerConnection.PeerConnectionState? = peerConnectionStates[peerId]

    fun closePeer(peerId: String, keepPendingCandidates: Boolean = false) {
        peerConnectionStates.remove(peerId)
        remoteDescriptionSetPeers.remove(peerId)
        if (!keepPendingCandidates) {
            pendingRemoteIceCandidates.remove(peerId)
            addedRemoteIceCandidates.remove(peerId)
        } else {
            val pending = pendingRemoteIceCandidates[peerId]
            if (pending.isNullOrEmpty()) {
                addedRemoteIceCandidates.remove(peerId)
            } else {
                val freshSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                freshSet.addAll(pending.map { "${it.sdpMid}:${it.sdpMLineIndex}:${it.sdp}" })
                addedRemoteIceCandidates[peerId] = freshSet
            }
        }
        dataChannels.remove(peerId)?.dispose()
        peerConnections.remove(peerId)?.dispose()
        remoteVideoTrack?.removeSink(remoteVideoSink)
        remoteVideoTrack = null
        remoteAudioTracks.remove(peerId)
        if (remoteAudioTrack == null || remoteAudioTracks.isEmpty()) {
            remoteAudioTrack = null
        }
        updateActiveViewersCount()
        AppLogger.i(tag, "Closed WebRTC connection with $peerId")
    }

    fun closeAll() {
        peerConnectionStates.clear()
        remoteDescriptionSetPeers.clear()
        stopCameraCapture()
        stopViewerAudioCapture()
        pendingRemoteIceCandidates.clear()
        addedRemoteIceCandidates.clear()
        dataChannels.values.forEach { it.dispose() }
        dataChannels.clear()
        peerConnections.values.forEach { it.dispose() }
        peerConnections.clear()
        _activeViewersCount.value = 0
        localRenderer?.let { r -> localVideoTrack?.removeSink(r) }
        remoteVideoTrack?.removeSink(remoteVideoSink)
        remoteVideoTrack = null
        remoteRenderer = null
        remoteVideoRotationDegrees = 0
        remoteAudioTrack = null
        remoteAudioTracks.clear()
        isRemoteAudioEnabled = false
        AppLogger.i(tag, "All WebRTC connections closed")
    }
}
