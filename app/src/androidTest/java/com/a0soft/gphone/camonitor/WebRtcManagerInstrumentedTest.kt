package com.a0soft.gphone.camonitor

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.a0soft.gphone.camonitor.data.model.ControlCommand
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WebRtcManagerInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = CamMonitorApp.instance
    private val webRtcManager get() = app.webRtcManager

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
    fun testWebRtcInitializationAndHardwareCapture() {
        assertNotNull("EglBase should be initialized", webRtcManager.eglBase)
        assertNotNull("PeerConnectionFactory should be initialized", webRtcManager.peerConnectionFactory)

        // Test viewer audio capture (microphone track)
        val audioTrack = webRtcManager.startViewerAudioCapture()
        assertNotNull("Viewer audio track should be initialized", audioTrack)
        assertTrue("Viewer audio track active flag should be true", webRtcManager.isViewerAudioTrackActive)

        webRtcManager.stopViewerAudioCapture()
        assertFalse("Viewer audio track active flag should be false after stop", webRtcManager.isViewerAudioTrackActive)

        // Test camera capture initialization
        webRtcManager.startCameraCapture(1280, 720, 20)
        assertTrue("Camera capturer should be capturing", webRtcManager.isCameraCaptureActive)
        assertNotNull("Local video track should be created", webRtcManager.activeLocalVideoTrack)

        Thread.sleep(1000)
        webRtcManager.stopCameraCapture()
        assertFalse("Camera capturer should not be capturing after stop", webRtcManager.isCameraCaptureActive)
    }

    @Test
    fun testWebRtcPeerConnectionAndDataChannelLoopback() {
        val factory = webRtcManager.peerConnectionFactory
        assertNotNull("Factory should not be null", factory)
        val validFactory = factory!!

        val pc1Candidates = mutableListOf<IceCandidate>()
        val pc2Candidates = mutableListOf<IceCandidate>()
        val pc1Connected = CountDownLatch(1)
        val pc2Connected = CountDownLatch(1)
        val dataChannelReceived = CountDownLatch(1)
        val messageReceived = CountDownLatch(1)
        val receivedMessageText = AtomicReference<String>()

        val rtcConfig = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        var pc1: PeerConnection? = null
        var pc2: PeerConnection? = null

        pc1 = validFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                pc1Candidates.add(candidate)
                pc2?.addIceCandidate(candidate)
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) pc1Connected.countDown()
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
        })

        pc2 = validFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                pc2Candidates.add(candidate)
                pc1?.addIceCandidate(candidate)
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) pc2Connected.countDown()
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {
                dataChannelReceived.countDown()
                dc.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        receivedMessageText.set(String(bytes, Charsets.UTF_8))
                        messageReceived.countDown()
                    }
                })
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
        })

        assertNotNull("PC1 must be created", pc1)
        assertNotNull("PC2 must be created", pc2)

        // Create DataChannel on caller (pc1)
        val dcInit = DataChannel.Init().apply {
            ordered = true
        }
        val dc1 = pc1!!.createDataChannel("cmd", dcInit)
        val dc1Open = CountDownLatch(1)
        dc1.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (dc1.state() == DataChannel.State.OPEN) dc1Open.countDown()
            }
            override fun onMessage(buffer: DataChannel.Buffer) {}
        })

        // Offer / Answer handshake
        val offerLatch = CountDownLatch(1)

        pc1.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc1.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        pc2!!.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                pc2.createAnswer(object : SdpObserver {
                                    override fun onCreateSuccess(answerDesc: SessionDescription) {
                                        pc2.setLocalDescription(object : SdpObserver {
                                            override fun onSetSuccess() {
                                                pc1.setRemoteDescription(object : SdpObserver {
                                                    override fun onSetSuccess() {
                                                        offerLatch.countDown()
                                                    }
                                                    override fun onSetFailure(s: String?) {}
                                                    override fun onCreateSuccess(desc: SessionDescription?) {}
                                                    override fun onCreateFailure(s: String?) {}
                                                }, answerDesc)
                                            }
                                            override fun onSetFailure(s: String?) {}
                                            override fun onCreateSuccess(desc: SessionDescription?) {}
                                            override fun onCreateFailure(s: String?) {}
                                        }, answerDesc)
                                    }
                                    override fun onCreateFailure(s: String?) {}
                                    override fun onSetSuccess() {}
                                    override fun onSetFailure(s: String?) {}
                                }, MediaConstraints())
                            }
                            override fun onSetFailure(s: String?) {}
                            override fun onCreateSuccess(desc: SessionDescription?) {}
                            override fun onCreateFailure(s: String?) {}
                        }, desc)
                    }
                    override fun onSetFailure(s: String?) {}
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onCreateFailure(s: String?) {}
                }, desc)
            }
            override fun onCreateFailure(s: String?) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(s: String?) {}
        }, MediaConstraints())

        assertTrue("SDP Offer/Answer handshake should complete within 5s", offerLatch.await(5, TimeUnit.SECONDS))
        assertTrue("DataChannel should open within 5s", dc1Open.await(5, TimeUnit.SECONDS))
        assertTrue("DataChannel should be received on PC2", dataChannelReceived.await(5, TimeUnit.SECONDS))

        // Send a ControlCommand across the DataChannel
        val testCommand = ControlCommand.TorchReport(enabled = true)
        val json = testCommand.toJson()
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)), false)
        dc1.send(buffer)

        assertTrue("Message should be delivered across DataChannel", messageReceived.await(5, TimeUnit.SECONDS))
        val parsed = ControlCommand.fromJson(receivedMessageText.get())
        assertTrue("Parsed command should be TorchReport", parsed is ControlCommand.TorchReport)
        assertTrue("TorchReport enabled should be true", (parsed as ControlCommand.TorchReport).enabled)

        dc1.close()
        pc1.dispose()
        pc2!!.dispose()
    }
}
