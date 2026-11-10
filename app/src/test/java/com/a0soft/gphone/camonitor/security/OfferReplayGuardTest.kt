package com.a0soft.gphone.camonitor.security

import com.a0soft.gphone.camonitor.data.security.OfferReplayGuard
import com.a0soft.gphone.camonitor.data.security.OfferTimestampStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferReplayGuardTest {

    private class MapStore : OfferTimestampStore {
        val map = HashMap<String, Long>()
        var writes = 0
        override fun get(senderId: String): Long? = map[senderId]
        override fun put(senderId: String, timestamp: Long) {
            writes++
            map[senderId] = timestamp
        }
        override fun remove(senderId: String) {
            map.remove(senderId)
        }
    }

    @Test
    fun firstOffer_Accepted() {
        assertTrue(OfferReplayGuard(MapStore()).accept("pb-1", 1_000L))
    }

    @Test
    fun sameOffer_ReplayedAfterRestart_Rejected() {
        val persisted = MapStore()
        assertTrue(OfferReplayGuard(persisted).accept("pb-1", 1_000L))

        // "Process restart": brand-new guard instance (and, in production, an empty nonce cache)
        // backed by the same persisted store.
        val afterRestart = OfferReplayGuard(persisted)
        assertFalse("Replayed Offer must be rejected after restart", afterRestart.accept("pb-1", 1_000L))
    }

    @Test
    fun olderOffer_Rejected_NewerOffer_Accepted() {
        val guard = OfferReplayGuard(MapStore())
        assertTrue(guard.accept("pb-1", 5_000L))
        assertFalse(guard.accept("pb-1", 4_999L))
        assertTrue(guard.accept("pb-1", 5_001L))
    }

    @Test
    fun senders_AreIndependent() {
        val guard = OfferReplayGuard(MapStore())
        assertTrue(guard.accept("pb-1", 9_000L))
        assertTrue("Other sender's clock must not be affected", guard.accept("pb-2", 1_000L))
    }

    @Test
    fun forget_AllowsFreshPairing() {
        val guard = OfferReplayGuard(MapStore())
        assertTrue(guard.accept("pb-1", 9_000L))
        guard.forget("pb-1")
        assertTrue(guard.accept("pb-1", 1_000L))
    }

    @Test
    fun rejectedOffer_DoesNotWrite() {
        val store = MapStore()
        val guard = OfferReplayGuard(store)
        guard.accept("pb-1", 2_000L)
        guard.accept("pb-1", 1_000L)
        guard.accept("pb-1", 2_000L)
        assertEquals("Only accepted Offers hit storage", 1, store.writes)
    }

    @Test
    fun concurrentSameOffer_ExactlyOneAccepted() {
        val guard = OfferReplayGuard(MapStore())
        val threads = 8
        val executor = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val accepted = java.util.concurrent.atomic.AtomicInteger(0)
        val latch = java.util.concurrent.CountDownLatch(threads)
        repeat(threads) {
            executor.execute {
                try {
                    if (guard.accept("pb-1", 7_000L)) accepted.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(1, accepted.get())
    }
}
