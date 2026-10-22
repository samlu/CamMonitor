package com.a0soft.gphone.camonitor.ui.viewer

import android.view.ViewGroup
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.data.model.ControlCommand
import com.a0soft.gphone.camonitor.data.model.NightModeSetting
import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.ui.common.AudioRoutingManager
import com.a0soft.gphone.camonitor.ui.common.BatteryHelper
import com.a0soft.gphone.camonitor.ui.common.BatteryInfo
import com.a0soft.gphone.camonitor.ui.theme.StatusLive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.*

sealed interface ViewerConnectionState {
    object Connecting : ViewerConnectionState
    object NoCamera : ViewerConnectionState
    object InitFailed : ViewerConnectionState
    object WaitingResponse : ViewerConnectionState
    object StartingStream : ViewerConnectionState
    data class Rejected(val reason: String) : ViewerConnectionState
    object Live : ViewerConnectionState
    object Failed : ViewerConnectionState
    object Disconnected : ViewerConnectionState
}

@Composable
fun ViewerModeScreen(
    onExitViewerMode: () -> Unit,
    selectedDeviceId: String? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val app = remember { CamMonitorApp.instance }
    val securityRepo = app.securityRepository
    val signalingClient = app.signalingClient
    val webRtcManager = app.webRtcManager

    val pairedDevices = remember { securityRepo.getPairedDevices() }
    val targetCamera = remember(selectedDeviceId) {
        if (selectedDeviceId != null) {
            securityRepo.getPairedDevice(selectedDeviceId) ?: pairedDevices.firstOrNull()
        } else {
            pairedDevices.firstOrNull()
        }
    }

    var cameraName by remember { mutableStateOf(targetCamera?.name ?: "") }
    var connectionState by remember { mutableStateOf<ViewerConnectionState>(ViewerConnectionState.Connecting) }
    var rotationDegrees by remember { mutableStateOf(0f) }
    var isAspectFit by remember { mutableStateOf(true) }
    var videoAspectRatio by remember { mutableStateOf<Float?>(null) }
    var isControlsVisible by remember { mutableStateOf(true) }
    val remoteFps by webRtcManager.remoteStreamFps.collectAsState()
    var streamFps by remember { mutableStateOf(20) }

    LaunchedEffect(remoteFps) {
        streamFps = remoteFps
    }

    val displayName = cameraName.ifEmpty { stringResource(R.string.camera_default_name) }
    val connectionStatus = when (val s = connectionState) {
        ViewerConnectionState.Connecting -> stringResource(R.string.viewer_status_connecting)
        ViewerConnectionState.NoCamera -> stringResource(R.string.viewer_status_no_camera)
        ViewerConnectionState.InitFailed -> stringResource(R.string.viewer_status_init_failed)
        ViewerConnectionState.WaitingResponse -> stringResource(R.string.viewer_status_waiting_response)
        ViewerConnectionState.StartingStream -> stringResource(R.string.viewer_status_starting_stream)
        is ViewerConnectionState.Rejected -> stringResource(R.string.viewer_status_camera_rejected, s.reason)
        ViewerConnectionState.Live -> stringResource(R.string.viewer_status_live, streamFps)
        ViewerConnectionState.Failed -> stringResource(R.string.viewer_status_connection_failed)
        ViewerConnectionState.Disconnected -> stringResource(R.string.viewer_status_disconnected)
    }
    val isLive = connectionState is ViewerConnectionState.Live
    val isConnecting = connectionState is ViewerConnectionState.Connecting ||
        connectionState is ViewerConnectionState.StartingStream ||
        connectionState is ViewerConnectionState.WaitingResponse
    var isAudioEnabled by remember { mutableStateOf(securityRepo.isViewerAudioPlaybackEnabled()) }
    var isTorchActive by remember { mutableStateOf(false) }
    var isPttPressed by remember { mutableStateOf(false) }
    var hasSymmetricNatError by remember { mutableStateOf(false) }
    var nightModeSetting by remember { mutableStateOf(securityRepo.getNightModeSetting()) }
    var isCameraNightActive by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = context as? Activity
    val prefs = remember { context.getSharedPreferences("cammonitor_prefs", Context.MODE_PRIVATE) }
    var showMicPermissionDialog by remember { mutableStateOf(false) }
    var isMicPermanentlyDenied by remember { mutableStateOf(false) }
    var reconnectTrigger by remember { mutableStateOf(0) }

    fun checkIsMicPermanentlyDenied(): Boolean {
        val hasRequested = prefs.getBoolean("has_requested_${Manifest.permission.RECORD_AUDIO}", false)
        return hasRequested && (activity?.let {
            !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
        } ?: false)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showMicPermissionDialog = false
            reconnectTrigger++
        } else {
            isMicPermanentlyDenied = checkIsMicPermanentlyDenied()
            showMicPermissionDialog = true
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (hasMic) {
                    showMicPermissionDialog = false
                    if (!webRtcManager.isViewerAudioTrackActive) {
                        reconnectTrigger++
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var paBatteryInfo by remember { mutableStateOf<BatteryInfo?>(null) }
    var pbBatteryInfo by remember { mutableStateOf(BatteryHelper.getBatteryInfo(context)) }

    var surfaceRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var isUserExited by remember { mutableStateOf(false) }

    fun exitViewer() {
        isUserExited = true
        webRtcManager.stopViewerAudioCapture()
        AudioRoutingManager.restoreNormalMode(context)
        webRtcManager.closeAll()
        coroutineScope.launch {
            try {
                signalingClient.stopListening()
            } catch (_: Exception) {}
        }
        onExitViewerMode()
    }

    BackHandler {
        exitViewer()
    }

    // Sync audio playback enabled state to WebRTC manager
    LaunchedEffect(isAudioEnabled) {
        webRtcManager.setRemoteAudioEnabled(isAudioEnabled)
        if (isAudioEnabled) {
            AudioRoutingManager.routeToSpeaker(context, adjustVolume = true)
        }
    }

    // Auto-hide controls timer
    LaunchedEffect(isControlsVisible) {
        if (isControlsVisible) {
            delay(5000L)
            isControlsVisible = false
        }
    }

    // Ensure remoteRenderer is always attached when surfaceRenderer is available
    LaunchedEffect(surfaceRenderer) {
        surfaceRenderer?.let {
            webRtcManager.attachRemoteRenderer(it)
        }
    }

    // Connect to camera on launch and on reconnectTrigger
    LaunchedEffect(targetCamera, reconnectTrigger) {
        val effectScope = this
        if (targetCamera == null) {
            connectionState = ViewerConnectionState.NoCamera
            AppLogger.w("ViewerMode", "No paired camera found in repository")
            return@LaunchedEffect
        }
        if (isUserExited) return@LaunchedEffect

        webRtcManager.closePeer(targetCamera.deviceId)
        surfaceRenderer?.let { webRtcManager.attachRemoteRenderer(it) }
        connectionState = ViewerConnectionState.Connecting

        val startTime = System.currentTimeMillis()
        AppLogger.i("ViewerMode", "Starting WebRTC connection to camera: ${targetCamera.name} (${targetCamera.deviceId}) (trigger=$reconnectTrigger)")
        val isMqttListening = signalingClient.startListening()
        AppLogger.i("ViewerMode", "Signaling client startListening completed (connected=$isMqttListening)")

        val remotePubKey = CryptoUtils.publicKeyFromBase64(targetCamera.publicKeyBase64)
        val sharedKey = CryptoUtils.deriveEcdhKey(securityRepo.getKeyPair().private, remotePubKey)

        val localIceCandidates = java.util.Collections.synchronizedList(mutableListOf<IceCandidate>())

        val pc = webRtcManager.createPeerConnection(targetCamera.deviceId) { candidate ->
            val typeMatch = Regex("typ\\s+(\\w+)").find(candidate.sdp)?.groupValues?.getOrNull(1) ?: "unknown"
            AppLogger.d("ViewerMode", "Forwarding local ICE candidate to camera: mid=${candidate.sdpMid}, type=$typeMatch")
            localIceCandidates.add(candidate)
            val iceMsg = SignalingMessage.IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            effectScope.launch {
                val sent = signalingClient.sendSignalingMessage(targetCamera.deviceId, iceMsg, sharedKey)
                AppLogger.d("ViewerMode", "Sent ICE candidate to camera (success=$sent, mid=${candidate.sdpMid})")
            }
        } ?: run {
            AppLogger.e("ViewerMode", "Failed creating PeerConnection for ${targetCamera.deviceId}")
            connectionState = ViewerConnectionState.InitFailed
            return@LaunchedEffect
        }

        // Explicit Unified Plan transceivers: Video (mid=0, RECV_ONLY), Audio (mid=1, SEND_RECV)
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
        val pttAudioTrack = webRtcManager.startViewerAudioCapture()
        if (pttAudioTrack != null) {
            pc.addTransceiver(
                pttAudioTrack,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            )
            AppLogger.i("ViewerMode", "Added SEND_RECV audio transceiver with viewer PTT track")
        } else {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            AppLogger.w("ViewerMode", "Viewer PTT track was null, falling back to RECV_ONLY audio")
        }

        webRtcManager.createDataChannelForSender(targetCamera.deviceId)

        var localOfferDesc: SessionDescription? = null

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                try {
                    val elapsed = System.currentTimeMillis() - startTime
                    AppLogger.i("ViewerMode", "Local Offer created (+${elapsed}ms, SDP size=${desc.description.length} chars). Setting local description...")
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            try {
                                val setElapsed = System.currentTimeMillis() - startTime
                                AppLogger.i("ViewerMode", "Local Offer set successfully (+${setElapsed}ms), transmitting to camera: ${targetCamera.deviceId}")
                                localOfferDesc = desc
                                val offerMsg = SignalingMessage.Offer(desc.description)
                                effectScope.launch {
                                    val sent = signalingClient.sendSignalingMessage(targetCamera.deviceId, offerMsg, sharedKey)
                                    AppLogger.i("ViewerMode", "Transmitted initial Offer to camera (success=$sent)")
                                }
                            } catch (t: Throwable) {
                                AppLogger.e("ViewerMode", "Error in setLocalDescription.onSetSuccess", t)
                            }
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetFailure(err: String?) { AppLogger.e("ViewerMode", "Failed setting local offer: $err") }
                        override fun onCreateFailure(err: String?) {}
                    }, desc)
                } catch (t: Throwable) {
                    AppLogger.e("ViewerMode", "Error in createOffer.onCreateSuccess", t)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) { AppLogger.e("ViewerMode", "Failed creating offer: $err") }
            override fun onSetFailure(err: String?) {}
        }, MediaConstraints())

        // Periodic Offer retry while waiting for camera to come online / answer
        val retryJob = effectScope.launch {
            var attempt = 1
            while (isActive && pc.remoteDescription == null) {
                delay(3000L)
                val offer = localOfferDesc
                if (pc.remoteDescription == null && offer != null) {
                    attempt++
                    val elapsed = System.currentTimeMillis() - startTime
                    val candidatesSnapshot = ArrayList(localIceCandidates)
                    AppLogger.i("ViewerMode", "Waiting for camera answer (+${elapsed}ms); re-transmitting Offer (attempt #$attempt) and ${candidatesSnapshot.size} local ICE candidates...")
                    val offerMsg = SignalingMessage.Offer(offer.description)
                    val sent = signalingClient.sendSignalingMessage(targetCamera.deviceId, offerMsg, sharedKey)
                    AppLogger.i("ViewerMode", "Re-transmitted Offer (attempt #$attempt, sent=$sent)")
                    candidatesSnapshot.forEach { cand ->
                        launch {
                            val iceMsg = SignalingMessage.IceCandidate(cand.sdpMid, cand.sdpMLineIndex, cand.sdp)
                            val candSent = signalingClient.sendSignalingMessage(targetCamera.deviceId, iceMsg, sharedKey)
                            AppLogger.d("ViewerMode", "Re-transmitted candidate to camera (success=$candSent, mid=${cand.sdpMid})")
                        }
                    }
                    if (attempt >= 15 && pc.connectionState() == PeerConnection.PeerConnectionState.NEW) {
                        connectionState = ViewerConnectionState.WaitingResponse
                    }
                }
            }
        }

        // Observe incoming messages
        signalingClient.incomingMessages.collect { (senderId, msg) ->
            if (senderId == targetCamera.deviceId) {
                when (msg) {
                    is SignalingMessage.Answer -> {
                        val elapsed = System.currentTimeMillis() - startTime
                        AppLogger.i("ViewerMode", "Received remote Answer from camera (+${elapsed}ms, SDP size=${msg.sdp.length} chars). Applying to PeerConnection...")
                        msg.cameraName?.let { newName ->
                            cameraName = newName
                            val existing = securityRepo.getPairedDevice(targetCamera.deviceId) ?: targetCamera
                            securityRepo.addPairedDevice(existing.copy(name = newName))
                            AppLogger.i("ViewerMode", "Updated paired camera name from Answer to: $newName")
                        }
                        msg.fps?.let { newFps ->
                            streamFps = newFps
                            webRtcManager.setRemoteFps(newFps)
                            AppLogger.i("ViewerMode", "Received stream FPS from Answer: $newFps fps")
                        }
                        retryJob.cancel()
                        val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, msg.sdp)
                        pc.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                try {
                                    val setElapsed = System.currentTimeMillis() - startTime
                                    AppLogger.i("ViewerMode", "Remote Answer applied successfully (+${setElapsed}ms). Draining buffered candidates...")
                                    webRtcManager.drainRemoteIceCandidates(targetCamera.deviceId)
                                    connectionState = ViewerConnectionState.StartingStream

                                    val candidatesSnapshot = ArrayList(localIceCandidates)
                                    AppLogger.i("ViewerMode", "Camera answered: transmitting ${candidatesSnapshot.size} local ICE candidates...")
                                    effectScope.launch {
                                        candidatesSnapshot.forEach { cand ->
                                            launch {
                                                val iceMsg = SignalingMessage.IceCandidate(cand.sdpMid, cand.sdpMLineIndex, cand.sdp)
                                                val candSent = signalingClient.sendSignalingMessage(targetCamera.deviceId, iceMsg, sharedKey)
                                                AppLogger.d("ViewerMode", "Sent candidate on answer to camera (success=$candSent, mid=${cand.sdpMid})")
                                            }
                                        }
                                    }
                                } catch (t: Throwable) {
                                    AppLogger.e("ViewerMode", "Error in setRemoteDescription.onSetSuccess", t)
                                }
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetFailure(err: String?) { AppLogger.e("ViewerMode", "Failed setting remote answer: $err") }
                            override fun onCreateFailure(err: String?) {}
                        }, answerDesc)
                    }
                    is SignalingMessage.IceCandidate -> {
                        val typeMatch = Regex("typ\\s+(\\w+)").find(msg.sdp)?.groupValues?.getOrNull(1) ?: "unknown"
                        AppLogger.d("ViewerMode", "Received remote ICE candidate from camera: mid=${msg.sdpMid}, index=${msg.sdpMLineIndex}, type=$typeMatch")
                        val candidate = IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.sdp)
                        webRtcManager.addRemoteIceCandidate(targetCamera.deviceId, candidate)
                    }
                    is SignalingMessage.Reject -> {
                        val elapsed = System.currentTimeMillis() - startTime
                        AppLogger.w("ViewerMode", "Camera rejected connection (+${elapsed}ms): ${msg.reason}")
                        retryJob.cancel()
                        connectionState = ViewerConnectionState.Rejected(msg.reason)
                    }
                    else -> {}
                }
            }
        }
    }

    // Observe DataChannel commands from PA (e.g. RENAME, BATTERY)
    LaunchedEffect(Unit) {
        webRtcManager.dataChannelCommands.collect { (peerId, cmd) ->
            when (cmd) {
                is ControlCommand.Rename -> {
                    cameraName = cmd.name
                    // Update local storage
                    val existing = securityRepo.getPairedDevice(peerId) ?: targetCamera
                    if (existing != null) {
                        securityRepo.addPairedDevice(existing.copy(name = cmd.name))
                        AppLogger.i("ViewerMode", "Updated paired camera name from DataChannel to: ${cmd.name}")
                    }
                }
                is ControlCommand.Battery -> {
                    paBatteryInfo = BatteryInfo(cmd.percentage, cmd.isCharging)
                }
                is ControlCommand.NightModeReport -> {
                    try {
                        val mode = NightModeSetting.valueOf(cmd.mode)
                        nightModeSetting = mode
                        securityRepo.setNightModeSetting(mode)
                    } catch (_: Exception) {}
                    isCameraNightActive = cmd.isNightActive
                    AppLogger.i("ViewerMode", "Received NightModeReport from camera: mode=${cmd.mode}, active=${cmd.isNightActive}")
                }
                is ControlCommand.FpsReport -> {
                    streamFps = cmd.fps
                    webRtcManager.setRemoteFps(cmd.fps)
                    AppLogger.i("ViewerMode", "Received FpsReport from camera: ${cmd.fps} fps")
                }
                else -> {}
            }
        }
    }

    // Periodically poll local battery and query PA battery
    LaunchedEffect(targetCamera) {
        while (isActive) {
            pbBatteryInfo = BatteryHelper.getBatteryInfo(context)
            if (targetCamera != null) {
                webRtcManager.sendDataChannelCommand(targetCamera.deviceId, ControlCommand.QueryBattery)
            }
            delay(15_000L)
        }
    }

    // Observe WebRTC connection state
    LaunchedEffect(Unit) {
        webRtcManager.connectionState.collect { state ->
            AppLogger.i("ViewerMode", "WebRTC PeerConnectionState changed to: $state")
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    connectionState = ViewerConnectionState.Live
                    hasSymmetricNatError = false
                    targetCamera?.let { cam ->
                        coroutineScope.launch {
                            delay(500L)
                            webRtcManager.sendDataChannelCommand(cam.deviceId, ControlCommand.QueryBattery)
                            delay(1500L)
                            webRtcManager.sendDataChannelCommand(cam.deviceId, ControlCommand.QueryBattery)
                        }
                    }
                }
                PeerConnection.PeerConnectionState.CONNECTING -> connectionState = ViewerConnectionState.Connecting
                PeerConnection.PeerConnectionState.FAILED -> {
                    connectionState = ViewerConnectionState.Failed
                    hasSymmetricNatError = true
                    AppLogger.e("ViewerMode", "WebRTC connection failed (potential symmetric NAT traversal failure)")
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    connectionState = ViewerConnectionState.Disconnected
                    AppLogger.w("ViewerMode", "WebRTC connection disconnected, scheduling auto-reconnect in 3s")
                    if (!isUserExited) {
                        coroutineScope.launch {
                            delay(3000L)
                            if (!isUserExited && connectionState is ViewerConnectionState.Disconnected) {
                                AppLogger.i("ViewerMode", "Auto-reconnecting after disconnect...")
                                reconnectTrigger++
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }

    // Auto-reconnect when signaling reconnects after a network drop
    LaunchedEffect(Unit) {
        signalingClient.isConnectedFlow.collect { connected ->
            if (connected && !isUserExited && (connectionState is ViewerConnectionState.Disconnected || connectionState is ViewerConnectionState.Failed)) {
                AppLogger.i("ViewerMode", "Signaling restored, triggering camera reconnect")
                reconnectTrigger++
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { isControlsVisible = !isControlsVisible },
                    onDoubleTap = { isAspectFit = !isAspectFit }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight
        val aspect = videoAspectRatio ?: if (rotationDegrees.toInt() % 180 != 0) (9f / 16f) else (16f / 9f)
        val containerAspect = containerWidth / containerHeight

        val (viewWidth, viewHeight) = if (isAspectFit) {
            if (aspect > containerAspect) {
                Pair(containerWidth, containerWidth / aspect)
            } else {
                Pair(containerHeight * aspect, containerHeight)
            }
        } else {
            if (aspect > containerAspect) {
                Pair(containerHeight * aspect, containerHeight)
            } else {
                Pair(containerWidth, containerWidth / aspect)
            }
        }

        // Video Viewport with dynamic aspect-ratio and rotation support
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    init(webRtcManager.eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() {
                            AppLogger.d("ViewerMode", "First remote frame rendered")
                        }

                        override fun onFrameResolutionChanged(vw: Int, vh: Int, rot: Int) {
                            AppLogger.i("ViewerMode", "Remote frame resolution changed: ${vw}x${vh}, rot=$rot")
                            val rw = if (rot == 90 || rot == 270) vh else vw
                            val rh = if (rot == 90 || rot == 270) vw else vh
                            if (rw > 0 && rh > 0) {
                                val newAspect = rw.toFloat() / rh.toFloat()
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    videoAspectRatio = newAspect
                                }
                            }
                        }
                    })
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(false)
                    surfaceRenderer = this
                    webRtcManager.setRemoteVideoRotation(rotationDegrees.toInt())
                    webRtcManager.attachRemoteRenderer(this)
                }
            },
            update = { renderer ->
                surfaceRenderer = renderer
                webRtcManager.setRemoteVideoRotation(rotationDegrees.toInt())
                webRtcManager.attachRemoteRenderer(renderer)
                renderer.setScalingType(
                    if (isAspectFit) RendererCommon.ScalingType.SCALE_ASPECT_FIT
                    else RendererCommon.ScalingType.SCALE_ASPECT_FILL
                )
            },
            onRelease = { renderer ->
                webRtcManager.detachRemoteRenderer(renderer)
                renderer.release()
                surfaceRenderer = null
            },
            modifier = Modifier.size(viewWidth, viewHeight)
        )

        // Overlay Controls
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Overlay
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = {
                            exitViewer()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.exit_viewer_mode))
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (isLive) StatusLive else MaterialTheme.colorScheme.error, CircleShape)
                                )
                                Text(
                                    text = connectionStatus,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isLive) StatusLive else MaterialTheme.colorScheme.error
                                )
                                if (!isLive && !isConnecting) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    FilledTonalButton(
                                        onClick = { reconnectTrigger++ },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        modifier = Modifier.height(20.dp)
                                    ) {
                                        Text(stringResource(R.string.action_retry), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Videocam,
                                        contentDescription = stringResource(R.string.cd_camera_battery),
                                        modifier = Modifier.size(13.dp),
                                        tint = Color.LightGray
                                    )
                                    Text(
                                        text = stringResource(R.string.viewer_camera_battery_prefix, BatteryHelper.formatPercentage(paBatteryInfo)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = getBatteryColor(paBatteryInfo)
                                    )
                                }

                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Smartphone,
                                        contentDescription = stringResource(R.string.cd_viewer_battery),
                                        modifier = Modifier.size(13.dp),
                                        tint = Color.LightGray
                                    )
                                    Text(
                                        text = stringResource(R.string.viewer_device_battery_prefix, BatteryHelper.formatPercentage(pbBatteryInfo)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = getBatteryColor(pbBatteryInfo)
                                    )
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Night Mode Toggle Button: Auto -> Night -> Normal -> Auto
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = when (nightModeSetting) {
                                    NightModeSetting.NIGHT -> MaterialTheme.colorScheme.primaryContainer
                                    NightModeSetting.AUTO -> if (isCameraNightActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                                    NightModeSetting.NORMAL -> MaterialTheme.colorScheme.surfaceContainer
                                },
                                modifier = Modifier.clickable {
                                    val nextSetting = when (nightModeSetting) {
                                        NightModeSetting.AUTO -> NightModeSetting.NIGHT
                                        NightModeSetting.NIGHT -> NightModeSetting.NORMAL
                                        NightModeSetting.NORMAL -> NightModeSetting.AUTO
                                    }
                                    nightModeSetting = nextSetting
                                    securityRepo.setNightModeSetting(nextSetting)
                                    targetCamera?.let { cam ->
                                        webRtcManager.sendDataChannelCommand(
                                            cam.deviceId,
                                            ControlCommand.SetNightMode(nextSetting.name)
                                        )
                                    }
                                    AppLogger.i("ViewerMode", "Toggled night mode to: $nextSetting")
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = when (nightModeSetting) {
                                            NightModeSetting.AUTO -> Icons.Default.BrightnessAuto
                                            NightModeSetting.NIGHT -> Icons.Default.Nightlight
                                            NightModeSetting.NORMAL -> Icons.Default.WbSunny
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = when (nightModeSetting) {
                                            NightModeSetting.NIGHT -> MaterialTheme.colorScheme.primary
                                            NightModeSetting.AUTO -> if (isCameraNightActive) MaterialTheme.colorScheme.primary else Color.White
                                            NightModeSetting.NORMAL -> Color.White
                                        }
                                    )
                                    Text(
                                        text = when (nightModeSetting) {
                                            NightModeSetting.AUTO -> stringResource(R.string.night_mode_auto)
                                            NightModeSetting.NIGHT -> stringResource(R.string.night_mode_night)
                                            NightModeSetting.NORMAL -> stringResource(R.string.night_mode_normal)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = when (nightModeSetting) {
                                            NightModeSetting.NIGHT -> MaterialTheme.colorScheme.onPrimaryContainer
                                            NightModeSetting.AUTO -> if (isCameraNightActive) MaterialTheme.colorScheme.onPrimaryContainer else Color.White
                                            NightModeSetting.NORMAL -> Color.White
                                        }
                                    )
                                }
                            }

                            // Torch Button
                            IconButton(onClick = {
                                if (targetCamera != null) {
                                    isTorchActive = !isTorchActive
                                    webRtcManager.sendDataChannelCommand(
                                        targetCamera.deviceId,
                                        ControlCommand.Torch(isTorchActive)
                                    )
                                }
                            }) {
                                Icon(
                                    imageVector = if (isTorchActive) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                    contentDescription = stringResource(R.string.cd_torch),
                                    tint = if (isTorchActive) MaterialTheme.colorScheme.primary else Color.White
                                )
                            }
                        }
                    }
                }

                // Bottom Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Rotate 90 Degrees Button
                    FilledTonalIconButton(
                        onClick = {
                            val newRotation = (rotationDegrees + 90f) % 360f
                            rotationDegrees = newRotation
                            videoAspectRatio?.let { currentAspect ->
                                if (currentAspect > 0f) {
                                    videoAspectRatio = 1f / currentAspect
                                }
                            }
                            webRtcManager.setRemoteVideoRotation(newRotation.toInt())
                            AppLogger.i("ViewerMode", "Rotate button clicked: new rotationDegrees=$newRotation")
                        }
                    ) {
                        Icon(Icons.Default.Rotate90DegreesCw, contentDescription = stringResource(R.string.rotate_90))
                    }

                    // Aspect Ratio (Fit / Fill) Toggle Button
                    FilledTonalIconButton(
                        onClick = {
                            isAspectFit = !isAspectFit
                            AppLogger.i("ViewerMode", "Aspect Fit/Fill toggle clicked: isAspectFit=$isAspectFit")
                        }
                    ) {
                        Icon(
                            imageVector = if (isAspectFit) Icons.Default.Fullscreen else Icons.Default.FitScreen,
                            contentDescription = if (isAspectFit) stringResource(R.string.cd_fill_screen) else stringResource(R.string.cd_fit_screen)
                        )
                    }

                    // Push-to-Talk Button
                    Surface(
                        shape = CircleShape,
                        color = if (isPttPressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .height(56.dp)
                            .pointerInput(targetCamera?.deviceId) {
                                detectTapGestures(
                                    onPress = {
                                        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                        if (!hasMic) {
                                            val hasRequested = prefs.getBoolean("has_requested_${Manifest.permission.RECORD_AUDIO}", false)
                                            if (!hasRequested) {
                                                prefs.edit().putBoolean("has_requested_${Manifest.permission.RECORD_AUDIO}", true).apply()
                                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            } else {
                                                isMicPermanentlyDenied = checkIsMicPermanentlyDenied()
                                                showMicPermissionDialog = true
                                            }
                                            return@detectTapGestures
                                        }

                                        isPttPressed = true
                                        val wasAudioEnabled = isAudioEnabled
                                        if (wasAudioEnabled) {
                                            webRtcManager.setRemoteAudioEnabled(false)
                                        }
                                        webRtcManager.setPttActive(true)
                                        targetCamera?.let {
                                            webRtcManager.sendDataChannelCommand(it.deviceId, ControlCommand.PttStart)
                                        }
                                        AppLogger.i("ViewerMode", "PTT pressed: transmitting voice")
                                        try {
                                            awaitRelease()
                                        } finally {
                                             isPttPressed = false
                                            webRtcManager.setPttActive(false)
                                            targetCamera?.let {
                                                webRtcManager.sendDataChannelCommand(it.deviceId, ControlCommand.PttStop)
                                            }
                                            if (wasAudioEnabled) {
                                                webRtcManager.setRemoteAudioEnabled(true)
                                            }
                                            AppLogger.i("ViewerMode", "PTT released: audio stopped")
                                        }
                                    }
                                )
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null)
                            Text(
                                text = if (isPttPressed) stringResource(R.string.viewer_ptt_transmitting) else stringResource(R.string.viewer_ptt_hold_to_talk),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    // Audio Playback Button (Mute/Unmute)
                    FilledTonalIconToggleButton(
                        checked = isAudioEnabled,
                        onCheckedChange = { enabled ->
                            isAudioEnabled = enabled
                            securityRepo.setViewerAudioPlaybackEnabled(enabled)
                            webRtcManager.setRemoteAudioEnabled(enabled)
                        }
                    ) {
                        Icon(
                            imageVector = if (isAudioEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = if (isAudioEnabled) stringResource(R.string.cd_audio_playback_on) else stringResource(R.string.cd_audio_playback_off)
                        )
                    }
                }
            }
        }

        // Persistent compact battery badge when controls are auto-hidden
        if (!isControlsVisible) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = stringResource(R.string.cd_camera_battery),
                            modifier = Modifier.size(12.dp),
                            tint = Color.LightGray
                        )
                        Text(
                            text = BatteryHelper.formatPercentage(paBatteryInfo),
                            style = MaterialTheme.typography.labelSmall,
                            color = getBatteryColor(paBatteryInfo)
                        )
                    }

                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Smartphone,
                            contentDescription = stringResource(R.string.cd_viewer_battery),
                            modifier = Modifier.size(12.dp),
                            tint = Color.LightGray
                        )
                        Text(
                            text = BatteryHelper.formatPercentage(pbBatteryInfo),
                            style = MaterialTheme.typography.labelSmall,
                            color = getBatteryColor(pbBatteryInfo)
                        )
                    }
                }
            }
        }

        // Center Reconnect Overlay when Disconnected or Failed
        if (connectionState is ViewerConnectionState.Disconnected || connectionState is ViewerConnectionState.Failed) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = connectionStatus,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.viewer_reconnect_prompt),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { reconnectTrigger++ },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.viewer_reconnect_now))
                    }
                }
            }
        }

        // Symmetric NAT Error Dialog
        if (hasSymmetricNatError) {
            AlertDialog(
                onDismissRequest = { hasSymmetricNatError = false },
                title = { Text(stringResource(R.string.viewer_status_connection_failed)) },
                text = {
                    Text(stringResource(R.string.viewer_symmetric_nat_desc))
                },
                confirmButton = {
                    TextButton(onClick = {
                        hasSymmetricNatError = false
                        exitViewer()
                    }) {
                        Text(stringResource(R.string.action_exit))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        hasSymmetricNatError = false
                        reconnectTrigger++
                    }) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            )
        }

        if (showMicPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showMicPermissionDialog = false },
                title = { Text(stringResource(R.string.permission_dialog_title)) },
                text = { Text(stringResource(R.string.permission_audio_reason)) },
                confirmButton = {
                    Button(
                        onClick = {
                            if (isMicPermanentlyDenied) {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } else {
                                prefs.edit().putBoolean("has_requested_${Manifest.permission.RECORD_AUDIO}", true).apply()
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    ) {
                        Text(
                            if (isMicPermanentlyDenied) stringResource(R.string.permission_open_settings)
                            else stringResource(R.string.permission_grant)
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showMicPermissionDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }

    DisposableEffect(Unit) {
        AudioRoutingManager.routeToSpeaker(context, adjustVolume = isAudioEnabled)
        onDispose {
            webRtcManager.stopViewerAudioCapture()
            AudioRoutingManager.restoreNormalMode(context)
            surfaceRenderer?.let { webRtcManager.detachRemoteRenderer(it) }
            surfaceRenderer?.release()
        }
    }
}

private fun getBatteryColor(info: BatteryInfo?): Color {
    return if (info != null && info.percentage in 0..15 && !info.isCharging) {
        Color(0xFFFF6E6E)
    } else {
        Color.White
    }
}
