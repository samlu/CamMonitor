package com.a0soft.gphone.camonitor.viewer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerRetryLifecycleTest {

    @Test
    fun testEffectScopeCancellationTerminatesChildRetryLoopImmediately() = runTest {
        var retryAttempts = 0
        var isRemoteDescriptionSet = false

        // Simulate LaunchedEffect(targetCamera, reconnectTrigger)
        val parentJob = Job()
        val effectScope = CoroutineScope(coroutineContext + parentJob)

        // Retry loop launched on effectScope (child coroutine)
        val retryJob = effectScope.launch {
            while (isActive && !isRemoteDescriptionSet) {
                delay(3000L)
                if (!isRemoteDescriptionSet) {
                    retryAttempts++
                }
            }
        }

        // T = 0s
        assertEquals(0, retryAttempts)
        assertTrue(retryJob.isActive)

        // Advance 3s -> attempt 1
        advanceTimeBy(3000L)
        runCurrent()
        assertEquals(1, retryAttempts)
        assertTrue(retryJob.isActive)

        // Advance 3s -> attempt 2
        advanceTimeBy(3000L)
        runCurrent()
        assertEquals(2, retryAttempts)

        // Reconnect trigger fired (user taps retry / network event) -> parent LaunchedEffect cancelled
        parentJob.cancel()
        runCurrent()

        // Child retryJob MUST be cancelled immediately
        assertTrue(retryJob.isCancelled)
        assertFalse(retryJob.isActive)

        // Advance time further by 30 seconds - NO further retry attempts must occur
        val attemptsAtCancel = retryAttempts
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals("Cancelled retryJob must not execute further retry attempts", attemptsAtCancel, retryAttempts)
    }

    @Test
    fun testReconnectingSpawnsFreshJobWithoutOldJobInterference() = runTest {
        var totalOffersSent = 0

        // Attempt 1
        val job1 = Job()
        val scope1 = CoroutineScope(coroutineContext + job1)
        scope1.launch {
            while (isActive) {
                delay(3000L)
                totalOffersSent++
            }
        }

        advanceTimeBy(3000L)
        runCurrent()
        assertEquals(1, totalOffersSent)

        // Reconnect triggered: job1 is cancelled
        job1.cancel()
        runCurrent()

        // Attempt 2 starts on fresh scope
        val job2 = Job()
        val scope2 = CoroutineScope(coroutineContext + job2)
        scope2.launch {
            while (isActive) {
                delay(3000L)
                totalOffersSent++
            }
        }

        // Advance 3s -> only scope2 should increment totalOffersSent
        advanceTimeBy(3000L)
        runCurrent()
        assertEquals("Only attempt 2 should transmit offers after reconnect", 2, totalOffersSent)

        job2.cancel()
    }
}
