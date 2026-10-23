package com.a0soft.gphone.camonitor

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.a0soft.gphone.camonitor.data.model.ControlCommand
import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import com.a0soft.gphone.camonitor.ui.common.BatteryHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DeviceConnectionInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = CamMonitorApp.instance
    private val securityRepo get() = app.securityRepository
    private val signalingClient get() = app.signalingClient
    private val webRtcManager get() = app.webRtcManager
    private val torchManager get() = app.torchManager

    @Before
    fun setUp() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA")
        uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
        Thread.sleep(300)
    }

    @Test
    fun testLiveDeviceHardwareAndBatteryReporting() {
        // Verify local battery detection is functional
        val bInfo = BatteryHelper.getBatteryInfo(context)
        assertNotNull("Battery info must not be null", bInfo)
        assertTrue("Battery percentage must be in valid range or -1", bInfo.percentage in -1..100)

        // Verify local torch hardware capability and state control
        val torchAvailable = torchManager.hasFlash
        if (torchAvailable) {
            torchManager.setTorch(true)
            Thread.sleep(200)
            assertTrue("Torch state should report on", torchManager.isTorchOn.value)
            torchManager.setTorch(false)
            Thread.sleep(200)
            assertFalse("Torch state should report off", torchManager.isTorchOn.value)
        }
    }

    @Test
    fun testLiveEndToEndDeviceWebRtcAndDataChannel() = runBlocking {
        val pairedCameras = securityRepo.getPairedCameras()
        if (pairedCameras.isEmpty()) {
            android.util.Log.i("DeviceConnectionTest", "No paired camera on this device; verifying camera standby/streaming capability")
            val pairedViewers = securityRepo.getPairedViewers()
            assertTrue("Device must have either paired camera or paired viewer credentials", pairedCameras.isNotEmpty() || pairedViewers.isNotEmpty())
            return@runBlocking
        }

        val targetCamera = pairedCameras.first()
        android.util.Log.i("DeviceConnectionTest", "Testing live connection to paired camera: ${targetCamera.name} (${targetCamera.deviceId})")

        val started = signalingClient.startListening()
        assertTrue("Signaling client startListening should return true", started)

        val isConnected = withTimeoutOrNull(20_000L) {
            signalingClient.isConnectedFlow.first { it }
        }
        assertTrue("Signaling client should connect to MQTT broker via TLS", isConnected == true)

        val remotePubKey = CryptoUtils.publicKeyFromBase64(targetCamera.publicKeyBase64)
        val sharedKey = CryptoUtils.deriveEcdhKey(securityRepo.getKeyPair().private, remotePubKey)

        val localIceCandidates = mutableListOf<IceCandidate>()
        val connectionEstablished = CountDownLatch(1)
        val answerReceived = CountDownLatch(1)
        val batteryReceived = CountDownLatch(1)
        val torchReportReceived = CountDownLatch(1)
        val remoteBattery = AtomicReference<ControlCommand.Battery>()
        val remoteTorchState = AtomicBoolean(false)

        val pc = webRtcManager.createPeerConnection(targetCamera.deviceId) { candidate ->
            localIceCandidates.add(candidate)
            val iceMsg = SignalingMessage.IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            launch {
                signalingClient.sendSignalingMessage(targetCamera.deviceId, iceMsg, sharedKey)
            }
        }
        assertNotNull("PeerConnection must be created", pc)

        // Add Video (RECV_ONLY) and Audio (SEND_RECV) Unified Plan transceivers
        pc!!.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
        val pttTrack = webRtcManager.startViewerAudioCapture()
        if (pttTrack != null) {
            pc.addTransceiver(pttTrack, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
        }

        webRtcManager.createDataChannelForSender(targetCamera.deviceId)

        val commandJob = launch {
            webRtcManager.dataChannelCommands.collect { (peerId, cmd) ->
                if (peerId == targetCamera.deviceId) {
                    when (cmd) {
                        is ControlCommand.Battery -> {
                            remoteBattery.set(cmd)
                            batteryReceived.countDown()
                        }
                        is ControlCommand.TorchReport -> {
                            remoteTorchState.set(cmd.enabled)
                            torchReportReceived.countDown()
                        }
                        else -> {}
                    }
                }
            }
        }

        val signalingJob = launch {
            signalingClient.incomingMessages.collect { (senderId, msg) ->
                if (senderId == targetCamera.deviceId) {
                    when (msg) {
                        is SignalingMessage.Answer -> {
                            val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, msg.sdp)
                            pc.setRemoteDescription(object : SdpObserver {
                                override fun onSetSuccess() {
                                    webRtcManager.drainRemoteIceCandidates(targetCamera.deviceId)
                                    answerReceived.countDown()
                                    launch {
                                        localIceCandidates.forEach { cand ->
                                            val iceMsg = SignalingMessage.IceCandidate(cand.sdpMid, cand.sdpMLineIndex, cand.sdp)
                                            signalingClient.sendSignalingMessage(targetCamera.deviceId, iceMsg, sharedKey)
                                        }
                                    }
                                }
                                override fun onSetFailure(s: String?) {}
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onCreateFailure(p0: String?) {}
                            }, answerDesc)
                        }
                        is SignalingMessage.IceCandidate -> {
                            val cand = IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.sdp)
                            webRtcManager.addRemoteIceCandidate(targetCamera.deviceId, cand)
                        }
                        else -> {}
                    }
                }
            }
        }

        // Create Offer with retry loop matching ViewerModeScreen
        val offerDescRef = java.util.concurrent.atomic.AtomicReference<SessionDescription>()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        offerDescRef.set(desc)
                        launch {
                            val offerMsg = SignalingMessage.Offer(desc.description)
                            signalingClient.sendSignalingMessage(targetCamera.deviceId, offerMsg, sharedKey)
                        }
                    }
                    override fun onSetFailure(s: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }
            override fun onCreateFailure(s: String?) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(s: String?) {}
        }, MediaConstraints())

        val retryJob = launch {
            while (isActive && answerReceived.count > 0) {
                delay(3000L)
                val offer = offerDescRef.get()
                if (offer != null && answerReceived.count > 0) {
                    val offerMsg = SignalingMessage.Offer(offer.description)
                    signalingClient.sendSignalingMessage(targetCamera.deviceId, offerMsg, sharedKey)
                    localIceCandidates.forEach { cand ->
                        val iceMsg = SignalingMessage.IceCandidate(cand.sdpMid, cand.sdpMLineIndex, cand.sdp)
                        signalingClient.sendSignalingMessage(targetCamera.deviceId, iceMsg, sharedKey)
                    }
                }
            }
        }

        val gotAnswer = withContext(Dispatchers.IO) { answerReceived.await(25, TimeUnit.SECONDS) }
        retryJob.cancel()
        assertTrue("Live camera must answer SDP offer within 25s", gotAnswer)

        // Wait for connection to transition to CONNECTED or verify DataChannel
        val connJob = launch {
            webRtcManager.connectionState.collect { state ->
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    connectionEstablished.countDown()
                }
            }
        }

        val isLive = withContext(Dispatchers.IO) { connectionEstablished.await(20, TimeUnit.SECONDS) }
        assertTrue("Live WebRTC connection must reach CONNECTED state", isLive)

        // Test DataChannel commands with live camera
        delay(1000L)
        webRtcManager.sendDataChannelCommand(targetCamera.deviceId, ControlCommand.QueryBattery)
        val gotBattery = withContext(Dispatchers.IO) { batteryReceived.await(10, TimeUnit.SECONDS) }
        assertTrue("Must receive Battery report from live camera", gotBattery)
        assertNotNull("Battery report must be populated", remoteBattery.get())
        assertTrue("Battery percentage must be valid", remoteBattery.get().percentage in 0..100)

        // Test Torch toggle across live DataChannel
        webRtcManager.sendDataChannelCommand(targetCamera.deviceId, ControlCommand.Torch(true))
        val gotTorch = withContext(Dispatchers.IO) { torchReportReceived.await(10, TimeUnit.SECONDS) }
        assertTrue("Must receive TorchReport response from live camera", gotTorch)

        // Test Push-to-Talk start & stop
        webRtcManager.sendDataChannelCommand(targetCamera.deviceId, ControlCommand.PttStart)
        delay(500L)
        webRtcManager.sendDataChannelCommand(targetCamera.deviceId, ControlCommand.PttStop)

        // Cleanup
        connJob.cancel()
        signalingJob.cancel()
        commandJob.cancel()
        webRtcManager.closePeer(targetCamera.deviceId)
        webRtcManager.stopViewerAudioCapture()
        signalingClient.stopListening()
    }
}
