package com.a0soft.gphone.camonitor.service

import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfferDeduplicationTest {

    @Test
    fun identicalOfferWhileInFlightIsIgnoredInsteadOfDestroyingPeerConnection() {
        val tracker = OfferDeduplicationTracker(dedupWindowMs = 15_000L)
        val (action1, session1) = tracker.evaluate(
            senderId = "viewer-1",
            offerSdp = "v=0\r\noffer-A",
            nowMs = 1_000L,
            isPeerAlive = false
        )
        assertEquals(OfferDedupAction.CREATE_NEW_PEER, action1)
        assertNull(session1.answer)

        // Retransmitted Offer arrives 3s later while Answer #1 is still being built
        val (action2, session2) = tracker.evaluate(
            senderId = "viewer-1",
            offerSdp = "v=0\r\noffer-A",
            nowMs = 4_000L,
            isPeerAlive = true
        )
        assertEquals(OfferDedupAction.IGNORE_IN_FLIGHT, action2)
        assertEquals(session1, session2)
    }

    @Test
    fun identicalOfferAfterAnswerReadyResendsCachedAnswerAndLocalIceCandidates() {
        val tracker = OfferDeduplicationTracker(dedupWindowMs = 15_000L)
        val (_, session1) = tracker.evaluate("viewer-1", "v=0\r\noffer-A", 1_000L, isPeerAlive = false)

        val answer = SignalingMessage.Answer(sdp = "v=0\r\nanswer-A", cameraName = "4F", fps = 15)
        val cand1 = SignalingMessage.IceCandidate("0", 0, "candidate:1 1 udp 1 1.2.3.4 5000 typ srflx")
        tracker.recordAnswer("viewer-1", session1, answer)
        tracker.recordLocalCandidate("viewer-1", session1, cand1)

        val (action2, session2) = tracker.evaluate("viewer-1", "v=0\r\noffer-A", 4_500L, isPeerAlive = true)
        assertEquals(OfferDedupAction.RESEND_CACHED_ANSWER, action2)
        assertEquals(answer, session2.answer)
        assertEquals(listOf(cand1), session2.localIceCandidates.toList())
    }

    @Test
    fun newSdpOrDeadPeerOrExpiredWindowCreatesFreshPeerAndIgnoresStaleSessionCallbacks() {
        val tracker = OfferDeduplicationTracker(dedupWindowMs = 15_000L)
        val (_, session1) = tracker.evaluate("viewer-1", "v=0\r\noffer-A", 1_000L, isPeerAlive = false)

        // Different SDP -> creates new peer
        val (actionDiff, session2) = tracker.evaluate("viewer-1", "v=0\r\noffer-B", 2_000L, isPeerAlive = true)
        assertEquals(OfferDedupAction.CREATE_NEW_PEER, actionDiff)

        // Stale async callback from session1 must NOT overwrite session2
        val staleAnswer = SignalingMessage.Answer(sdp = "v=0\r\nstale", cameraName = "4F", fps = 15)
        tracker.recordAnswer("viewer-1", session1, staleAnswer)
        assertNull(session2.answer)

        // Same SDP after peer died -> creates new peer
        val (actionDead, _) = tracker.evaluate("viewer-1", "v=0\r\noffer-B", 3_000L, isPeerAlive = false)
        assertEquals(OfferDedupAction.CREATE_NEW_PEER, actionDead)

        // Same SDP after dedup window expired -> creates new peer
        val (actionExpired, _) = tracker.evaluate("viewer-1", "v=0\r\noffer-B", 20_000L, isPeerAlive = true)
        assertEquals(OfferDedupAction.CREATE_NEW_PEER, actionExpired)
    }
}
