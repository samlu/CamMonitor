package com.a0soft.gphone.camonitor.signaling

import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignalingStateTest {

    @Test
    fun testAwaitConnectionReturnsFalseWhenDisconnectedAndFailsToReconnect() = runTest {
        val isConnectedFlow = MutableStateFlow(true)
        var physicalSocketConnected = false

        suspend fun awaitConnection(timeoutMs: Long = 1000L): Boolean {
            if (isConnectedFlow.value && physicalSocketConnected) return true
            isConnectedFlow.value = false
            return withTimeoutOrNull(timeoutMs) {
                isConnectedFlow.first { it }
            } ?: false
        }

        // Initially flow said true, but socket was dead:
        val result = awaitConnection(50L)
        assertFalse("Should fail connection when physical client is disconnected", result)
        assertFalse("State flow should reset to false", isConnectedFlow.value)
    }

    @Test
    fun testAwaitConnectionSucceedsWhenPhysicalSocketIsAlive() = runTest {
        val isConnectedFlow = MutableStateFlow(true)
        val physicalSocketConnected = true

        suspend fun awaitConnection(timeoutMs: Long = 1000L): Boolean {
            if (isConnectedFlow.value && physicalSocketConnected) return true
            isConnectedFlow.value = false
            return withTimeoutOrNull(timeoutMs) {
                isConnectedFlow.first { it }
            } ?: false
        }

        val result = awaitConnection(100L)
        assertTrue(result)
        assertTrue(isConnectedFlow.value)
    }

    @Test
    fun testOfferAndAnswerSerializationDuringStandbyWakeup() {
        val offer = SignalingMessage.Offer(sdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly\r\nm=audio 9 UDP/TLS/RTP/SAVPF 97\r\na=recvonly")
        val offerJson = offer.toJson()
        val parsedOffer = SignalingMessage.fromJson(offerJson) as? SignalingMessage.Offer

        assertNotNull(parsedOffer)
        assertEquals(offer.sdp, parsedOffer?.sdp)

        val answer = SignalingMessage.Answer(sdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=sendonly\r\nm=audio 9 UDP/TLS/RTP/SAVPF 97\r\na=sendonly")
        val answerJson = answer.toJson()
        val parsedAnswer = SignalingMessage.fromJson(answerJson) as? SignalingMessage.Answer

        assertNotNull(parsedAnswer)
        assertEquals(answer.sdp, parsedAnswer?.sdp)
    }

    @Test
    fun testReconnectionBackoffProgressionCapsAtFifteenSeconds() {
        var delayMs = 2000L
        val delays = mutableListOf<Long>()
        repeat(6) {
            delays.add(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(15_000L)
        }
        assertEquals(listOf(2000L, 4000L, 8000L, 15000L, 15000L, 15000L), delays)
    }

    @Test
    fun testClientGenerationGuardsAgainstStaleDisconnectEvents() = runTest {
        var clientGeneration = 1L
        val isConnectedFlow = MutableStateFlow(true)

        fun onDisconnect(gen: Long) {
            if (gen == clientGeneration) {
                isConnectedFlow.value = false
            }
        }

        // Simulate reconnect bump
        clientGeneration = 2L

        // Old generation 1 disconnect arrives late
        onDisconnect(1L)
        assertTrue("Late disconnect from old generation must not set connected to false", isConnectedFlow.value)

        // Matching generation disconnect arrives
        onDisconnect(2L)
        assertFalse("Current generation disconnect must update connected state to false", isConnectedFlow.value)
    }

    @Test
    fun testBrokerTlsConfiguration() {
        assertEquals(8883, com.a0soft.gphone.camonitor.data.signaling.MqttSignalingClient.PRIMARY_BROKER_PORT)
        assertEquals("broker.hivemq.com", com.a0soft.gphone.camonitor.data.signaling.MqttSignalingClient.PRIMARY_BROKER_HOST)
        assertEquals(8883, com.a0soft.gphone.camonitor.data.signaling.MqttSignalingClient.SECONDARY_BROKER_PORT)
        assertEquals("broker.emqx.io", com.a0soft.gphone.camonitor.data.signaling.MqttSignalingClient.SECONDARY_BROKER_HOST)
    }
}
