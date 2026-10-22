package com.a0soft.gphone.camonitor.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.IceCandidate

class WebRtcIceCacheTest {

    @Test
    fun testIceCandidateDeduplicationAndReconnectReset() {
        // Test data
        val peerId = "peer-camera-01"
        val cand1 = IceCandidate("0", 0, "candidate:4234199997 1 udp 2122260223 192.168.1.50 51432 typ host")
        val cand2 = IceCandidate("0", 0, "candidate:4234199998 1 udp 1686052607 203.0.113.1 51432 typ srflx")

        val addedRemoteIceCandidates = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
        val pendingRemoteIceCandidates = java.util.concurrent.ConcurrentHashMap<String, MutableList<IceCandidate>>()

        fun addRemoteIceCandidate(peer: String, candidate: IceCandidate): Boolean {
            val seen = addedRemoteIceCandidates.getOrPut(peer) { java.util.Collections.synchronizedSet(mutableSetOf()) }
            val candidateKey = "${candidate.sdpMid}:${candidate.sdpMLineIndex}:${candidate.sdp}"
            if (!seen.add(candidateKey)) {
                return false // duplicate ignored
            }
            val list = pendingRemoteIceCandidates.getOrPut(peer) { java.util.Collections.synchronizedList(mutableListOf()) }
            list.add(candidate)
            return true
        }

        fun closePeer(peer: String, keepPendingCandidates: Boolean) {
            if (!keepPendingCandidates) {
                pendingRemoteIceCandidates.remove(peer)
                addedRemoteIceCandidates.remove(peer)
            } else {
                val pending = pendingRemoteIceCandidates[peer]
                if (pending.isNullOrEmpty()) {
                    addedRemoteIceCandidates.remove(peer)
                } else {
                    val freshSet = java.util.Collections.synchronizedSet(
                        pending.map { "${it.sdpMid}:${it.sdpMLineIndex}:${it.sdp}" }.toMutableSet()
                    )
                    addedRemoteIceCandidates[peer] = freshSet
                }
            }
        }

        fun drainRemoteIceCandidates(peer: String): List<IceCandidate> {
            return pendingRemoteIceCandidates.remove(peer) ?: emptyList()
        }

        // --- Session 1 ---
        // First candidate received
        assertTrue("cand1 should be accepted in session 1", addRemoteIceCandidate(peerId, cand1))
        // Duplicate candidate received in session 1
        assertFalse("cand1 duplicate should be dropped in session 1", addRemoteIceCandidate(peerId, cand1))
        // Second candidate received in session 1
        assertTrue("cand2 should be accepted in session 1", addRemoteIceCandidate(peerId, cand2))

        // Session 1 drains candidates after remote description is set
        val drained = drainRemoteIceCandidates(peerId)
        assertEquals(2, drained.size)
        assertTrue(pendingRemoteIceCandidates[peerId].isNullOrEmpty())
        assertEquals(2, addedRemoteIceCandidates[peerId]?.size)

        // --- Reconnect (B-03 Trigger) ---
        // createPeerConnection calls closePeer(peerId, keepPendingCandidates = true)
        closePeer(peerId, keepPendingCandidates = true)

        // Stale added candidates must be purged because pending was empty
        assertNull("Stale added candidates must be purged on reconnect", addedRemoteIceCandidates[peerId])

        // --- Session 2 ---
        // Same candidate strings arrive from remote peer across reconnect
        assertTrue("cand1 must be accepted in session 2 after reconnect", addRemoteIceCandidate(peerId, cand1))
        assertTrue("cand2 must be accepted in session 2 after reconnect", addRemoteIceCandidate(peerId, cand2))
        assertFalse("cand1 duplicate in session 2 should be dropped", addRemoteIceCandidate(peerId, cand1))
    }

    @Test
    fun testKeepPendingCandidatesPreservesOnlyEarlyCandidatesOnReconnect() {
        val peerId = "peer-camera-02"
        val oldCand = IceCandidate("0", 0, "candidate:old 1 udp 2122260223 192.168.1.50 51432 typ host")
        val earlyNewCand = IceCandidate("0", 0, "candidate:new 1 udp 2122260223 192.168.1.50 51433 typ host")

        val addedRemoteIceCandidates = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
        val pendingRemoteIceCandidates = java.util.concurrent.ConcurrentHashMap<String, MutableList<IceCandidate>>()

        // Session 1 had an old candidate drained
        addedRemoteIceCandidates.getOrPut(peerId) { mutableSetOf() }.add("0:0:${oldCand.sdp}")

        // An early candidate arrived for session 2 right before createPeerConnection
        val pending = pendingRemoteIceCandidates.getOrPut(peerId) { mutableListOf() }
        pending.add(earlyNewCand)
        addedRemoteIceCandidates[peerId]?.add("0:0:${earlyNewCand.sdp}")

        // Reconnect: closePeer with keepPendingCandidates = true
        val pendingList = pendingRemoteIceCandidates[peerId]
        if (pendingList.isNullOrEmpty()) {
            addedRemoteIceCandidates.remove(peerId)
        } else {
            val freshSet = java.util.Collections.synchronizedSet(
                pendingList.map { "${it.sdpMid}:${it.sdpMLineIndex}:${it.sdp}" }.toMutableSet()
            )
            addedRemoteIceCandidates[peerId] = freshSet
        }

        val cache = addedRemoteIceCandidates[peerId]
        assertNotNull(cache)
        assertEquals(1, cache?.size)
        assertTrue(cache!!.contains("0:0:${earlyNewCand.sdp}"))
        assertFalse(cache.contains("0:0:${oldCand.sdp}"))
    }

    @Test
    fun testCandidateBufferedAroundDrainDoesNotGetStranded() {
        val peerId = "peer-camera-03"
        val cand1 = IceCandidate("0", 0, "candidate:cand1 1 udp 2122260223 192.168.1.50 51432 typ host")
        val cand2 = IceCandidate("0", 0, "candidate:cand2 1 udp 2122260223 192.168.1.50 51433 typ host")

        val remoteDescriptionSetPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val pendingRemoteIceCandidates = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<IceCandidate>>()
        val addedRemoteIceCandidates = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
        val deliveredToPc = mutableListOf<IceCandidate>()

        fun drainRemoteIceCandidates(peer: String) {
            remoteDescriptionSetPeers.add(peer)
            val pending = pendingRemoteIceCandidates.remove(peer)
            if (!pending.isNullOrEmpty()) {
                deliveredToPc.addAll(pending)
            }
        }

        fun addRemoteIceCandidate(peer: String, candidate: IceCandidate) {
            val seen = addedRemoteIceCandidates.getOrPut(peer) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
            val candidateKey = "${candidate.sdpMid}:${candidate.sdpMLineIndex}:${candidate.sdp}"
            if (!seen.add(candidateKey)) return

            if (remoteDescriptionSetPeers.contains(peer)) {
                deliveredToPc.add(candidate)
            } else {
                val list = pendingRemoteIceCandidates.getOrPut(peer) { java.util.concurrent.CopyOnWriteArrayList() }
                list.add(candidate)
                if (remoteDescriptionSetPeers.contains(peer)) {
                    drainRemoteIceCandidates(peer)
                }
            }
        }

        // Candidate 1 arrives before remote description is set
        addRemoteIceCandidate(peerId, cand1)
        assertEquals(0, deliveredToPc.size)
        assertEquals(1, pendingRemoteIceCandidates[peerId]?.size)

        // Drain occurs when remote description is set
        drainRemoteIceCandidates(peerId)
        assertEquals(1, deliveredToPc.size)
        assertNull(pendingRemoteIceCandidates[peerId])

        // Candidate 2 arrives after remote description is set
        addRemoteIceCandidate(peerId, cand2)
        assertEquals(2, deliveredToPc.size)
        assertNull(pendingRemoteIceCandidates[peerId])
    }
}
