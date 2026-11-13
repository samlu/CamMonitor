package com.a0soft.gphone.camonitor.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.data.model.ControlCommand
import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.ui.common.BatteryHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

enum class CameraStreamingState {
    IDLE,
    WAITING_FOR_VIEWERS,
    STREAMING,
    PAUSED_INACTIVITY // Paused after 15s of 0 viewers
}

class CameraMonitorService : Service() {
    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * How long the sensor keeps running after the last viewer leaves.
         *
         * Was 60s. The camera plus encoder is the hottest thing on the device, so
         * idling for a full minute after nobody is watching wastes real power for
         * no benefit: a returning viewer triggers CameraWaking and the sensor is
         * re-opened in ~1-2s anyway.
         */
        const val NO_VIEWER_PAUSE_MS = 15_000L
        const val MAX_CAMERA_RECOVERY_ATTEMPTS = 5

        fun computeRecoveryDelayMs(attempt: Int): Long =
            (1_000L shl attempt.coerceAtMost(5)).coerceAtMost(20_000L)
    }

    private val tag = "CameraMonitorService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val _streamingState = MutableStateFlow(CameraStreamingState.IDLE)
    @Suppress("unused")
    val streamingState: StateFlow<CameraStreamingState> = _streamingState.asStateFlow()

    @Suppress("unused")
    val isPttReceiving: StateFlow<Boolean>
        get() = CamMonitorApp.instance.webRtcManager.isPttReceiving

    private var isStreamingActive = false
    private var signalingJob: Job? = null
    private var signalingClientJob: Job? = null
    private var dataChannelJob: Job? = null
    private var viewersCountJob: Job? = null

    private var inactivityJob: Job? = null
    private var batteryBroadcastJob: Job? = null

    /**
     * Camera recovery bookkeeping.
     *
     * A camera that keeps dying (sensor grabbed by another app, HAL stuck) must not
     * be restarted in a tight loop — that heats the device and burns battery
     * without ever producing a picture. Back off exponentially and give up after a
     * few attempts; the next viewer connection resets the counter.
     */
    private var cameraRecoveryAttempts = 0
    private var cameraRecoveryJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): CameraMonitorService = this@CameraMonitorService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i(tag, "CameraMonitorService onStartCommand (intent=${intent != null}, isStreamingActive=$isStreamingActive)")
        val hasCam = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCam || !hasMic) {
            AppLogger.w(tag, "Missing CAMERA or RECORD_AUDIO permission in onStartCommand; stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        startStreaming()
        return START_STICKY
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.a0soft.gphone.camonitor.ui.common.LocaleHelper.getLocalizedContext(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        com.a0soft.gphone.camonitor.ui.common.LocaleHelper.applyCurrentLocale(this)
        AppLogger.i(tag, "CameraMonitorService created")
        acquireWakeLock()
        startForegroundServiceNotification()
        com.a0soft.gphone.camonitor.ui.common.AudioRoutingManager.routeToSpeaker(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.a0soft.gphone.camonitor.ui.common.LocaleHelper.applyCurrentLocale(this)
    }

    /**
     * Holds a CPU wake lock for the whole lifetime of the streaming service.
     *
     * Deliberately unbounded: the previous acquire(24h) silently expired after a
     * day, and an expired wake lock produces no callback and no log — the service
     * kept running while the CPU was free to suspend, which is indistinguishable
     * from "the camera app randomly stops responding". The lock now mirrors the
     * WifiLock (also unbounded) and is released in onDestroy; if the process is
     * killed the system reclaims it anyway.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamMonitor:StreamingWakeLock")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        AppLogger.i(tag, "Partial WakeLock acquired (unbounded, released on destroy)")
    }

    // =========================================================================
    // POWER ARCHITECTURE GOTCHA (Fix 2):
    // DO NOT hold WifiLock continuously during 0-viewer standby!
    //
    // Common Developer Mistake:
    // Developers may be tempted to call acquireWifiLock() in onCreate() for the service's
    // entire lifetime to "ensure connections never fail".
    //
    // The Cost:
    // Holding WIFI_MODE_FULL_LOW_LATENCY or WIFI_MODE_FULL_HIGH_PERF disables 802.11 DTIM
    // power-save polling. The Wi-Fi physical radio is forced to stay powered at high duty
    // cycles 100% of the time, consuming ~60-80 mA continuously (~629 mAh drained overnight).
    //
    // Why Standby Does NOT Need WifiLock:
    // When 0 viewers are connected, no WebRTC video/audio frames are moving. The only network
    // traffic is MQTT signaling over TLS (TCP port 8883). In standard 802.11 DTIM sleep, the
    // router buffers incoming packets for 100-300 ms until the next DTIM beacon, which TCP
    // handles easily without packet loss. HiveMQ's 30s keepalive ensures the socket and NAT
    // remain active.
    //
    // The Resolution:
    // WifiLock is managed DYNAMICALLY:
    // - Acquire WifiLock ONLY when activeViewersCount > 0 (active STREAMING with live video/audio).
    // - Release WifiLock whenever activeViewersCount == 0 (WAITING_FOR_VIEWERS or PAUSED_INACTIVITY).
    // =========================================================================
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(lockMode, "CamMonitor:StreamingWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            AppLogger.d(tag, "Streaming WifiLock acquired (mode=$lockMode)")
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed acquiring WifiLock: ${e.message}")
        }
    }

    private fun releaseWifiLock() {
        if (wifiLock?.isHeld == true) {
            try {
                wifiLock?.release()
                AppLogger.d(tag, "Streaming WifiLock released")
            } catch (e: Exception) {
                AppLogger.w(tag, "Failed releasing WifiLock: ${e.message}")
            }
        }
    }

    @Suppress("InlinedApi")
    private fun startForegroundServiceNotification() {
        val notification: Notification = NotificationCompat.Builder(this, CamMonitorApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(1001, notification, type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            startForeground(1001, notification, type)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun updateNotification(contentText: String) {
        val notification: Notification = NotificationCompat.Builder(this, CamMonitorApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1001, notification)
    }

    fun startStreaming() {
        val app = CamMonitorApp.instance
        if (isStreamingActive) {
            if (_streamingState.value == CameraStreamingState.PAUSED_INACTIVITY) {
                AppLogger.i(tag, "startStreaming called while in PAUSED_INACTIVITY; remaining in standby")
            } else {
                AppLogger.i(tag, "startStreaming called but streaming is already active; ensuring camera capture is running")
                app.webRtcManager.startCameraCapture()
            }
            return
        }
        isStreamingActive = true
        _streamingState.value = CameraStreamingState.WAITING_FOR_VIEWERS
        updateNotification(getString(R.string.notification_waiting_for_viewers))

        app.webRtcManager.onCameraUnexpectedClosed = {
            if (_streamingState.value == CameraStreamingState.STREAMING ||
                _streamingState.value == CameraStreamingState.WAITING_FOR_VIEWERS
            ) {
                scheduleCameraRecovery(app)
            }
        }

        app.webRtcManager.startCameraCapture()

        // Start listening to signaling
        signalingJob?.cancel()
        signalingJob = serviceScope.launch {
            observeSignaling(app)
        }
        signalingClientJob?.cancel()
        signalingClientJob = serviceScope.launch {
            app.signalingClient.startListening()
        }

        // Listen to DataChannel commands from viewers
        dataChannelJob?.cancel()
        dataChannelJob = serviceScope.launch {
            app.webRtcManager.dataChannelCommands.collect { (peerId, cmd) ->
                handleViewerCommand(peerId, cmd)
            }
        }

        // Track active viewers count for 15s timeout and battery broadcast
        viewersCountJob?.cancel()
        viewersCountJob = serviceScope.launch {
            app.webRtcManager.activeViewersCount.collect { count ->
                if (count > 0) {
                    acquireWifiLock()
                    _streamingState.value = CameraStreamingState.STREAMING
                    updateNotification(getString(R.string.notification_streaming_live))
                    cancelInactivityTimer()
                    startBatteryBroadcast()
                } else if (_streamingState.value == CameraStreamingState.STREAMING ||
                    _streamingState.value == CameraStreamingState.WAITING_FOR_VIEWERS) {
                    releaseWifiLock()
                    _streamingState.value = CameraStreamingState.WAITING_FOR_VIEWERS
                    updateNotification(getString(R.string.notification_waiting_for_viewers))
                    stopBatteryBroadcast()
                    startInactivityTimer()
                }
            }
        }

        startInactivityTimer()
    }

    fun stopStreaming() {
        if (!isStreamingActive) return
        isStreamingActive = false
        releaseWifiLock()
        signalingJob?.cancel()
        signalingJob = null
        signalingClientJob?.cancel()
        signalingClientJob = null
        dataChannelJob?.cancel()
        dataChannelJob = null
        viewersCountJob?.cancel()
        viewersCountJob = null
        cancelInactivityTimer()
        stopBatteryBroadcast()
        resetCameraRecovery()
        _streamingState.value = CameraStreamingState.IDLE
        CamMonitorApp.instance.webRtcManager.stopCameraCapture()
    }

    /**
     * Restarts the camera pipeline with exponential backoff.
     *
     * Attempt n waits 1s, 2s, 4s, 8s … capped at 20s, and the loop stops after 5
     * consecutive failures (≈30s of trying). A new viewer Offer resets the counter,
     * so a user who walks back in always gets a fresh set of attempts.
     */
    private fun scheduleCameraRecovery(app: CamMonitorApp) {
        if (cameraRecoveryJob?.isActive == true) {
            AppLogger.i(tag, "Camera recovery already scheduled; ignoring duplicate request")
            return
        }
        if (cameraRecoveryAttempts >= MAX_CAMERA_RECOVERY_ATTEMPTS) {
            AppLogger.w(tag, "Camera recovery aborted: $MAX_CAMERA_RECOVERY_ATTEMPTS consecutive failures (waiting for a new viewer)")
            return
        }
        val attempt = cameraRecoveryAttempts
        val delayMs = computeRecoveryDelayMs(attempt)
        cameraRecoveryAttempts = attempt + 1
        cameraRecoveryJob = serviceScope.launch {
            AppLogger.w(tag, "Camera lost — recovery attempt ${attempt + 1}/$MAX_CAMERA_RECOVERY_ATTEMPTS in ${delayMs}ms")
            delay(delayMs.milliseconds)
            if (_streamingState.value == CameraStreamingState.STREAMING ||
                _streamingState.value == CameraStreamingState.WAITING_FOR_VIEWERS
            ) {
                app.webRtcManager.startCameraCapture()
            } else {
                AppLogger.i(tag, "Camera recovery skipped: service state is ${_streamingState.value}")
            }
        }
    }

    private fun resetCameraRecovery() {
        if (cameraRecoveryAttempts != 0) {
            AppLogger.i(tag, "Camera recovery counter reset (was $cameraRecoveryAttempts)")
        }
        cameraRecoveryAttempts = 0
        cameraRecoveryJob?.cancel()
        cameraRecoveryJob = null
    }

    private fun startBatteryBroadcast() {
        if (batteryBroadcastJob != null) return
        batteryBroadcastJob = serviceScope.launch {
            while (isActive) {
                val bInfo = BatteryHelper.getBatteryInfo(this@CameraMonitorService)
                if (bInfo.percentage >= 0) {
                    CamMonitorApp.instance.webRtcManager.broadcastDataChannelCommand(
                        ControlCommand.Battery(bInfo.percentage, bInfo.isCharging)
                    )
                }
                delay(15.seconds)
            }
        }
    }

    private fun stopBatteryBroadcast() {
        batteryBroadcastJob?.cancel()
        batteryBroadcastJob = null
    }

    private fun startInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = serviceScope.launch {
            AppLogger.i(tag, "Inactivity timer started: ${NO_VIEWER_PAUSE_MS / 1000}s countdown until camera pause")
            delay(NO_VIEWER_PAUSE_MS.milliseconds)
            if (CamMonitorApp.instance.webRtcManager.activeViewersCount.value > 0) {
                AppLogger.i(tag, "Inactivity timer elapsed, but activeViewersCount > 0; keeping camera active")
                return@launch
            }
            AppLogger.i(tag, "0 viewers for ${NO_VIEWER_PAUSE_MS / 1000}s: Stopping camera and pausing")
            resetCameraRecovery()
            CamMonitorApp.instance.webRtcManager.stopCameraCapture()
            _streamingState.value = CameraStreamingState.PAUSED_INACTIVITY
            updateNotification(getString(R.string.notification_standby_waiting))
        }
    }

    private fun cancelInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = null
    }

    fun resumeStreamingFromPause() {
        AppLogger.i(tag, "Resuming camera streaming from pause state")
        resetCameraRecovery()
        CamMonitorApp.instance.webRtcManager.startCameraCapture()
        _streamingState.value = CameraStreamingState.WAITING_FOR_VIEWERS
        startInactivityTimer()
    }

    private suspend fun observeSignaling(app: CamMonitorApp) {
        AppLogger.i(tag, "Signaling observer started, listening for incoming WebRTC messages")
        app.signalingClient.incomingMessages.collect { (senderId, message) ->
            val isAuthorizedViewer = app.securityRepository.getPairedViewers().any { it.deviceId == senderId }
            if (!isAuthorizedViewer) {
                AppLogger.w(tag, "Ignoring signaling from unauthorized/non-viewer sender: $senderId (${message.javaClass.simpleName})")
                return@collect
            }

            when (message) {
                is SignalingMessage.Offer -> {
                    AppLogger.i(tag, "Received WebRTC Offer from $senderId (SDP size: ${message.sdp.length} chars)")
                    handleRemoteOffer(senderId, message.sdp)
                }
                is SignalingMessage.IceCandidate -> {
                    // No log here on purpose: WebRtcManager already records every
                    // candidate (DEBUG) and the negotiated pair (INFO). Logging it
                    // again in this layer duplicated 327 of 2859 lines in a
                    // 31-minute capture without adding any information.
                    val candidate = IceCandidate(message.sdpMid, message.sdpMLineIndex, message.sdp)
                    app.webRtcManager.addRemoteIceCandidate(senderId, candidate)
                }
                else -> {
                    AppLogger.i(tag, "Received signaling message from $senderId: ${message.javaClass.simpleName}")
                }
            }
        }
    }

    private fun handleRemoteOffer(senderId: String, sdpStr: String) {
        val app = CamMonitorApp.instance
        val startTime = System.currentTimeMillis()
        AppLogger.i(tag, "Processing WebRTC Offer from $senderId (activeViewers=${app.webRtcManager.activeViewersCount.value}, state=${_streamingState.value})")

        // Cap max 2 viewers (allow reconnecting existing peer)
        val isExistingPeer = app.webRtcManager.hasPeer(senderId)
        if (app.webRtcManager.activeViewersCount.value >= 2 && !isExistingPeer) {
            AppLogger.w(tag, "Rejecting connection from $senderId: Max 2 viewers reached")
            sendSignalingMessageToViewer(senderId, SignalingMessage.Reject("MAX_CAPACITY_REACHED"))
            return
        }

        // Cancel previous inactivity countdown so old timer does not kill camera mid-handshake
        cancelInactivityTimer()
        // A fresh connection is a fresh chance for the camera pipeline.
        resetCameraRecovery()

        // Fast pre-answer ACK when camera sensor is sleeping/inactive so viewer knows camera is waking up
        val isWakingSensor = _streamingState.value == CameraStreamingState.PAUSED_INACTIVITY ||
            !app.webRtcManager.isCameraCaptureActive
        if (isWakingSensor) {
            AppLogger.i(tag, "Sending immediate CameraWaking ACK to $senderId while sensor initializes")
            sendSignalingMessageToViewer(
                senderId,
                SignalingMessage.CameraWaking(cameraName = app.securityRepository.getDeviceName())
            )
        }

        // Auto-resume camera capture if paused due to inactivity
        if (_streamingState.value == CameraStreamingState.PAUSED_INACTIVITY) {
            AppLogger.i(tag, "Camera was in PAUSED_INACTIVITY; resuming capture before responding to Offer")
            resumeStreamingFromPause()
        } else {
            app.webRtcManager.startCameraCapture()
            if (app.webRtcManager.activeViewersCount.value == 0) {
                startInactivityTimer()
            }
        }

        val pc = app.webRtcManager.createPeerConnection(senderId) { candidate ->
            val typeMatch = Regex("typ\\s+(\\w+)").find(candidate.sdp)?.groupValues?.getOrNull(1) ?: "unknown"
            AppLogger.d(tag, "Generated local ICE candidate for $senderId: mid=${candidate.sdpMid}, type=$typeMatch. Transmitting...")
            val iceMsg = SignalingMessage.IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            sendSignalingMessageToViewer(senderId, iceMsg)
        } ?: run {
            AppLogger.e(tag, "Failed to create PeerConnection for $senderId")
            return
        }

        app.webRtcManager.createDataChannelForSender(senderId)

        val offerDesc = SessionDescription(SessionDescription.Type.OFFER, sdpStr)
        AppLogger.i(tag, "Applying remote Offer to PeerConnection for $senderId...")
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                try {
                    val elapsed = System.currentTimeMillis() - startTime
                    AppLogger.i(tag, "Remote Offer set successfully for $senderId (+${elapsed}ms). Draining buffered ICE candidates and creating Answer...")
                    app.webRtcManager.drainRemoteIceCandidates(senderId)
                    val constraints = MediaConstraints()
                    pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(answerDesc: SessionDescription) {
                            try {
                                val createElapsed = System.currentTimeMillis() - startTime
                                AppLogger.i(tag, "Local Answer created for $senderId (+${createElapsed}ms, SDP size=${answerDesc.description.length} chars). Setting local description...")
                                pc.setLocalDescription(object : SdpObserver {
                                    override fun onSetSuccess() {
                                        try {
                                            val setLocalElapsed = System.currentTimeMillis() - startTime
                                            AppLogger.i(tag, "Local Answer set successfully (+${setLocalElapsed}ms), transmitting Answer to $senderId")
                                            app.webRtcManager.configureVideoSenders(pc)
                                            val answerMsg = SignalingMessage.Answer(
                                                sdp = answerDesc.description,
                                                cameraName = app.securityRepository.getDeviceName(),
                                                fps = app.webRtcManager.streamFps.value
                                            )
                                            sendSignalingMessageToViewer(senderId, answerMsg)
                                        } catch (t: Throwable) {
                                            AppLogger.e(tag, "Error in setLocalDescription.onSetSuccess for $senderId", t)
                                        }
                                    }
                                    override fun onCreateSuccess(desc: SessionDescription?) {}
                                    override fun onSetFailure(err: String?) { AppLogger.e(tag, "Failed setting local answer for $senderId: $err") }
                                    override fun onCreateFailure(err: String?) {}
                                }, answerDesc)
                            } catch (t: Throwable) {
                                AppLogger.e(tag, "Error in createAnswer.onCreateSuccess for $senderId", t)
                            }
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(err: String?) { AppLogger.e(tag, "Failed creating answer for $senderId: $err") }
                        override fun onSetFailure(err: String?) {}
                    }, constraints)
                } catch (t: Throwable) {
                    AppLogger.e(tag, "Error in setRemoteDescription.onSetSuccess for $senderId", t)
                }
            }
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetFailure(err: String?) { AppLogger.e(tag, "Failed setting remote offer from $senderId: $err") }
            override fun onCreateFailure(err: String?) {}
        }, offerDesc)
    }

    private fun sendSignalingMessageToViewer(viewerId: String, msg: SignalingMessage) {
        val app = CamMonitorApp.instance
        val viewer = app.securityRepository.getPairedViewers().firstOrNull { it.deviceId == viewerId }
        if (viewer == null) {
            AppLogger.w(tag, "Cannot send ${msg.javaClass.simpleName} to $viewerId: Viewer not found in paired viewers list")
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val remotePubKey = com.a0soft.gphone.camonitor.data.security.CryptoUtils.publicKeyFromBase64(viewer.publicKeyBase64)
                val key = com.a0soft.gphone.camonitor.data.security.CryptoUtils.deriveEcdhKey(app.securityRepository.getKeyPair().private, remotePubKey)
                val sent = app.signalingClient.sendSignalingMessage(viewerId, msg, key)
                AppLogger.d(tag, "Sent ${msg.javaClass.simpleName} to $viewerId (success=$sent)")
            } catch (e: Exception) {
                AppLogger.e(tag, "Failed sending ${msg.javaClass.simpleName} to $viewerId", e)
            }
        }
    }

    private fun handleViewerCommand(peerId: String, cmd: ControlCommand) {
        val app = CamMonitorApp.instance
        when (cmd) {
            is ControlCommand.Torch -> {
                app.torchManager.setTorch(cmd.enabled)
                app.webRtcManager.broadcastDataChannelCommand(
                    ControlCommand.TorchReport(app.torchManager.isTorchOn.value)
                )
            }
            is ControlCommand.TorchReport -> {
                // Not handled by camera sender
            }
            is ControlCommand.PttStart -> {
                AppLogger.i(tag, "Viewer $peerId started Push-to-Talk")
                com.a0soft.gphone.camonitor.ui.common.AudioRoutingManager.routeToSpeaker(this)
                app.webRtcManager.onPttCommand(peerId, true)
            }
            is ControlCommand.PttStop -> {
                AppLogger.i(tag, "Viewer $peerId stopped Push-to-Talk")
                app.webRtcManager.onPttCommand(peerId, false)
            }
            is ControlCommand.SetNightMode -> {
                val setting = try {
                    com.a0soft.gphone.camonitor.data.model.NightModeSetting.valueOf(cmd.mode)
                } catch (_: Exception) {
                    com.a0soft.gphone.camonitor.data.model.NightModeSetting.AUTO
                }
                app.webRtcManager.setNightModeSetting(setting)
                app.webRtcManager.broadcastDataChannelCommand(
                    ControlCommand.NightModeReport(
                        mode = setting.name,
                        isNightActive = app.webRtcManager.isNightModeActive.value
                    )
                )
            }
            is ControlCommand.NightModeReport -> {
                // Not handled by camera sender
            }
            is ControlCommand.Rename -> {
                // Not handled by sender
            }
            is ControlCommand.Battery -> {
                // Not handled by sender
            }
            is ControlCommand.QueryBattery -> {
                val bInfo = BatteryHelper.getBatteryInfo(this)
                if (bInfo.percentage >= 0) {
                    app.webRtcManager.broadcastDataChannelCommand(
                        ControlCommand.Battery(bInfo.percentage, bInfo.isCharging)
                    )
                }
                val currentName = app.securityRepository.getDeviceName()
                app.webRtcManager.broadcastDataChannelCommand(
                    ControlCommand.Rename(currentName)
                )
                app.webRtcManager.broadcastDataChannelCommand(
                    ControlCommand.NightModeReport(
                        mode = app.webRtcManager.nightModeSetting.value.name,
                        isNightActive = app.webRtcManager.isNightModeActive.value
                    )
                )
                app.webRtcManager.broadcastDataChannelCommand(
                    ControlCommand.FpsReport(app.webRtcManager.streamFps.value)
                )
                app.webRtcManager.broadcastDataChannelCommand(
                    ControlCommand.TorchReport(app.torchManager.isTorchOn.value)
                )
            }
            is ControlCommand.FpsReport -> {
                // Not handled by sender
            }
        }
    }

    @Suppress("unused")
    fun broadcastRename(newName: String) {
        CamMonitorApp.instance.webRtcManager.broadcastDataChannelCommand(ControlCommand.Rename(newName))
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        AppLogger.i(tag, "CameraMonitorService destroyed")
        stopStreaming()
        CamMonitorApp.instance.webRtcManager.onCameraUnexpectedClosed = null
        com.a0soft.gphone.camonitor.ui.common.AudioRoutingManager.restoreNormalMode(this)
        serviceScope.cancel()
        CamMonitorApp.instance.webRtcManager.closeAll()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null
        }
        releaseWifiLock()
        wifiLock = null
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CamMonitorApp.instance.signalingClient.stopListening()
            } catch (_: Exception) {}
        }
        // Tells the next boot this run ended on purpose. Without a marker the
        // logger reports "previous session did not end normally", which is how a
        // silent kill gets mistaken for a crash.
        AppLogger.markCleanShutdown("service destroyed")
    }
}
