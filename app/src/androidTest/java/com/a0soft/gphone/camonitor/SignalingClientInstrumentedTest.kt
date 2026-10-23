package com.a0soft.gphone.camonitor

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import com.a0soft.gphone.camonitor.data.signaling.MqttSignalingClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SignalingClientInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = CamMonitorApp.instance
    private val securityRepo get() = app.securityRepository

    @Before
    fun setUp() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.INTERNET")
    }

    @Test
    fun testSignalingClientConnectsAndDisconnectsOverTls() = runBlocking {
        val testClient = MqttSignalingClient(securityRepo, context)
        val started = testClient.startListening()
        assertTrue("startListening should initiate connection", started)

        val connected = withTimeoutOrNull(20_000L) {
            testClient.isConnectedFlow.first { it }
        }
        assertTrue("Signaling client should connect to HiveMQ broker via TLS", connected == true)

        testClient.stopListening()
        val disconnected = withTimeoutOrNull(10_000L) {
            testClient.isConnectedFlow.first { !it }
        }
        org.junit.Assert.assertNotNull("Signaling client should transition to disconnected", disconnected)
        org.junit.Assert.assertFalse("Signaling client isConnectedFlow should be false", testClient.isConnectedFlow.value)
    }

    @Test
    fun testSignalingClientSendEncryptedOffer() = runBlocking {
        val testClient = MqttSignalingClient(securityRepo, context)
        testClient.startListening()
        val isConnected = withTimeoutOrNull(20_000L) {
            testClient.isConnectedFlow.first { it }
        }
        assertTrue("Signaling client should be connected before sending", isConnected == true)

        // Generate an ephemeral test peer key pair and derive ECDH shared key
        val peerKeyPair = CryptoUtils.generateSoftwareEcKeyPair()
        val peerDeviceId = "test-peer-" + UUID.randomUUID().toString().take(8)
        val sharedKey = CryptoUtils.deriveEcdhKey(securityRepo.getKeyPair().private, peerKeyPair.public)

        val offer = SignalingMessage.Offer(sdp = "v=0\r\no=- 123456 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n")
        val sent = testClient.sendSignalingMessage(peerDeviceId, offer, sharedKey)
        assertTrue("sendSignalingMessage should succeed over TLS to MQTT broker", sent)

        testClient.stopListening()
    }
}
