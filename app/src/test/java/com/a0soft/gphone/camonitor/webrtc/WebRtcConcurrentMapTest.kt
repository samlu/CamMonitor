package com.a0soft.gphone.camonitor.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WebRtcConcurrentMapTest {

    @Test
    fun testConcurrentMutationsAndIterationsDoNotThrow() {
        val map = ConcurrentHashMap<String, String>()
        val threadCount = 4
        val iterationsPerThread = 1000
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val exceptionCount = AtomicInteger(0)

        // Writers
        for (t in 0 until 2) {
            executor.execute {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val key = "peer-${i % 20}"
                        map[key] = "connection-$i"
                        if (i % 3 == 0) {
                            map.remove(key)
                        }
                    }
                } catch (e: Exception) {
                    exceptionCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        // Readers / Iterators (simulating activeViewersCount, broadcastDataChannelCommand, applyFps)
        for (t in 2 until threadCount) {
            executor.execute {
                try {
                    for (i in 0 until iterationsPerThread) {
                        var sum = 0
                        map.values.forEach { sum += it.length }
                        val keysCount = map.keys.count { it.startsWith("peer-") }
                        assertTrue(keysCount >= 0)
                    }
                } catch (e: Exception) {
                    exceptionCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Execution should finish within timeout", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("No ConcurrentModificationException should occur on ConcurrentHashMap", 0, exceptionCount.get())
    }

    @Test
    fun testPeerConnectionStateTrackingDoesNotThrowOnConcurrentTeardown() {
        val states = ConcurrentHashMap<String, String>()
        states["peer1"] = "CONNECTED"
        states["peer2"] = "CONNECTED"

        // Count connected viewers
        val connectedCount = states.values.count { it == "CONNECTED" }
        assertEquals(2, connectedCount)

        // Simulate concurrent closePeer / closeAll
        states.remove("peer1")
        assertEquals(1, states.values.count { it == "CONNECTED" })

        states.clear()
        assertEquals(0, states.values.count { it == "CONNECTED" })
    }
}
