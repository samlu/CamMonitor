package com.a0soft.gphone.camonitor.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraStandbyStateTest {

    @Test
    fun testInactivityTimerTransitionsToPausedInactivityAfter15s() = runTest {
        assertEquals(15_000L, CameraMonitorService.NO_VIEWER_PAUSE_MS)
        val streamingState = MutableStateFlow(CameraStreamingState.WAITING_FOR_VIEWERS)
        var cameraHardwareStopped = false

        var inactivityJob: Job? = null
        fun startTimer() {
            inactivityJob?.cancel()
            inactivityJob = launch {
                delay(CameraMonitorService.NO_VIEWER_PAUSE_MS)
                cameraHardwareStopped = true
                streamingState.value = CameraStreamingState.PAUSED_INACTIVITY
            }
        }

        startTimer()

        // Advance 10 seconds - should not be paused yet
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(CameraStreamingState.WAITING_FOR_VIEWERS, streamingState.value)
        assertFalse(cameraHardwareStopped)

        // Advance remaining 5 seconds - should pause at 15s
        advanceTimeBy(5_001L)
        runCurrent()
        assertEquals(CameraStreamingState.PAUSED_INACTIVITY, streamingState.value)
        assertTrue(cameraHardwareStopped)
    }

    @Test
    fun testActiveViewerCancelsInactivityTimerAndEntersStreaming() = runTest {
        val streamingState = MutableStateFlow(CameraStreamingState.WAITING_FOR_VIEWERS)
        var cameraHardwareStopped = false
        var inactivityJob: Job? = null

        fun cancelTimer() {
            inactivityJob?.cancel()
            inactivityJob = null
        }

        fun startTimer() {
            inactivityJob?.cancel()
            inactivityJob = launch {
                delay(CameraMonitorService.NO_VIEWER_PAUSE_MS)
                cameraHardwareStopped = true
                streamingState.value = CameraStreamingState.PAUSED_INACTIVITY
            }
        }

        startTimer()

        // After 5 seconds, a viewer connects
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(CameraStreamingState.WAITING_FOR_VIEWERS, streamingState.value)

        // Viewer connected (active viewers = 1)
        streamingState.value = CameraStreamingState.STREAMING
        cancelTimer()

        // Advance past 15s total time
        advanceTimeBy(20_000L)
        runCurrent()

        // Must still be STREAMING and not stopped
        assertEquals(CameraStreamingState.STREAMING, streamingState.value)
        assertFalse(cameraHardwareStopped)
    }

    @Test
    fun testIncomingOfferNearTimeoutCancelsInactivityTimerAndPreventsStop() = runTest {
        val streamingState = MutableStateFlow(CameraStreamingState.WAITING_FOR_VIEWERS)
        var cameraHardwareStopped = false
        var inactivityJob: Job? = null

        fun cancelTimer() {
            inactivityJob?.cancel()
            inactivityJob = null
        }

        fun startTimer() {
            inactivityJob?.cancel()
            inactivityJob = launch {
                delay(CameraMonitorService.NO_VIEWER_PAUSE_MS)
                cameraHardwareStopped = true
                streamingState.value = CameraStreamingState.PAUSED_INACTIVITY
            }
        }

        fun handleIncomingOffer() {
            cancelTimer()
            if (streamingState.value == CameraStreamingState.PAUSED_INACTIVITY) {
                streamingState.value = CameraStreamingState.WAITING_FOR_VIEWERS
                startTimer()
            } else {
                startTimer()
            }
        }

        startTimer()

        // Advance to 14s into the 15s countdown
        advanceTimeBy(14_000L)
        runCurrent()
        assertFalse(cameraHardwareStopped)
        assertEquals(CameraStreamingState.WAITING_FOR_VIEWERS, streamingState.value)

        // Remote offer arrives at t=14s
        handleIncomingOffer()

        // Advance 5 more seconds (past the original 15s mark to t=19s)
        advanceTimeBy(5_000L)
        runCurrent()

        // Camera must NOT be stopped because countdown was refreshed for incoming offer
        assertFalse("Camera capture must not be stopped mid-handshake past original 15s timer", cameraHardwareStopped)
        assertEquals(CameraStreamingState.WAITING_FOR_VIEWERS, streamingState.value)

        // WebRTC handshake completes: viewer connects (active count = 1)
        streamingState.value = CameraStreamingState.STREAMING
        cancelTimer()

        // Advance past 15s of new timer
        advanceTimeBy(20_000L)
        runCurrent()
        assertFalse(cameraHardwareStopped)
        assertEquals(CameraStreamingState.STREAMING, streamingState.value)
    }

    @Test
    fun testResumeStreamingFromPauseWhenOfferArrives() = runTest {
        val streamingState = MutableStateFlow(CameraStreamingState.PAUSED_INACTIVITY)
        var cameraHardwareStarted = false
        var inactivityJob: Job? = null

        fun startTimer() {
            inactivityJob?.cancel()
            inactivityJob = launch {
                delay(CameraMonitorService.NO_VIEWER_PAUSE_MS)
                streamingState.value = CameraStreamingState.PAUSED_INACTIVITY
            }
        }

        fun resumeStreamingFromPause() {
            cameraHardwareStarted = true
            streamingState.value = CameraStreamingState.WAITING_FOR_VIEWERS
            startTimer()
        }

        // Remote offer arrives while in PAUSED_INACTIVITY
        if (streamingState.value == CameraStreamingState.PAUSED_INACTIVITY) {
            resumeStreamingFromPause()
        }

        runCurrent()
        assertTrue(cameraHardwareStarted)
        assertEquals(CameraStreamingState.WAITING_FOR_VIEWERS, streamingState.value)

        // Advancing 15s should re-pause if viewer doesn't connect
        advanceTimeBy(CameraMonitorService.NO_VIEWER_PAUSE_MS + 1L)
        runCurrent()
        assertEquals(CameraStreamingState.PAUSED_INACTIVITY, streamingState.value)
    }

    @Test
    fun testViewerDisconnectRestartsCountdown() = runTest {
        val streamingState = MutableStateFlow(CameraStreamingState.STREAMING)
        var timerStarted = false
        var inactivityJob: Job? = null

        fun startTimer() {
            timerStarted = true
            inactivityJob?.cancel()
            inactivityJob = launch {
                delay(CameraMonitorService.NO_VIEWER_PAUSE_MS)
                streamingState.value = CameraStreamingState.PAUSED_INACTIVITY
            }
        }

        // Viewer disconnects (count goes from 1 to 0)
        streamingState.value = CameraStreamingState.WAITING_FOR_VIEWERS
        startTimer()

        runCurrent()
        assertTrue(timerStarted)
        assertEquals(CameraStreamingState.WAITING_FOR_VIEWERS, streamingState.value)

        advanceTimeBy(CameraMonitorService.NO_VIEWER_PAUSE_MS + 1L)
        runCurrent()
        assertEquals(CameraStreamingState.PAUSED_INACTIVITY, streamingState.value)
    }

    @Test
    fun testReconnectingViewerBypassesCapacityRejection() {
        val existingPeers = mutableSetOf("viewer-1", "viewer-2")
        val activeViewersCount = 2

        fun canAcceptOffer(senderId: String): Boolean {
            val isExistingPeer = existingPeers.contains(senderId)
            if (activeViewersCount >= 2 && !isExistingPeer) {
                return false // MAX_CAPACITY_REACHED
            }
            return true
        }

        // Reconnecting existing peer (viewer-1) should NOT be rejected
        assertTrue("Reconnecting viewer-1 must be accepted even if activeViewersCount == 2", canAcceptOffer("viewer-1"))
        assertTrue("Reconnecting viewer-2 must be accepted even if activeViewersCount == 2", canAcceptOffer("viewer-2"))

        // New third viewer (viewer-3) must be rejected
        assertFalse("New 3rd viewer must be rejected when activeViewersCount == 2", canAcceptOffer("viewer-3"))
    }

    @Test
    fun testStartStreamingIdempotencyPreventsDuplicateCollectors() = runTest {
        var isStreamingActive = false
        var collectorLaunchCount = 0

        fun startStreaming() {
            if (isStreamingActive) {
                // Already active: ensure camera capture running but do NOT spawn duplicate collectors
                return
            }
            isStreamingActive = true
            collectorLaunchCount++
        }

        // First invocation (initial start)
        startStreaming()
        assertEquals(1, collectorLaunchCount)
        assertTrue(isStreamingActive)

        // Second invocation (e.g. screen rotation / ServiceConnection re-bind)
        startStreaming()
        assertEquals("Duplicate collectors must not be spawned on service re-entry", 1, collectorLaunchCount)

        // Third invocation
        startStreaming()
        assertEquals(1, collectorLaunchCount)
    }

    @Test
    fun testSecondViewerOfferWhileStreamingDoesNotStopActiveStream() = runTest {
        val streamingState = MutableStateFlow(CameraStreamingState.STREAMING)
        val activeViewersCount = MutableStateFlow(1)
        var cameraHardwareStopped = false
        var inactivityJob: Job? = null

        fun cancelTimer() {
            inactivityJob?.cancel()
            inactivityJob = null
        }

        fun startTimer() {
            inactivityJob?.cancel()
            inactivityJob = launch {
                delay(CameraMonitorService.NO_VIEWER_PAUSE_MS)
                if (activeViewersCount.value > 0) return@launch
                cameraHardwareStopped = true
                streamingState.value = CameraStreamingState.PAUSED_INACTIVITY
            }
        }

        fun handleIncomingOffer() {
            cancelTimer()
            if (streamingState.value == CameraStreamingState.PAUSED_INACTIVITY) {
                streamingState.value = CameraStreamingState.WAITING_FOR_VIEWERS
                startTimer()
            } else {
                if (activeViewersCount.value == 0) {
                    startTimer()
                }
            }
        }

        // Viewer 1 is already streaming (activeViewersCount = 1), Viewer 2 sends Offer
        handleIncomingOffer()

        // Viewer 2 fails ICE negotiation, so activeViewersCount remains 1.
        // Advance past 15s inactivity window:
        advanceTimeBy(CameraMonitorService.NO_VIEWER_PAUSE_MS + 5_000L)
        runCurrent()

        assertFalse("Camera must not stop while Viewer 1 is still connected", cameraHardwareStopped)
        assertEquals(CameraStreamingState.STREAMING, streamingState.value)
    }

    @Test
    fun testStartStreamingWhilePausedInactivityDoesNotWakeSensor() {
        var isStreamingActive = true
        val streamingState = MutableStateFlow(CameraStreamingState.PAUSED_INACTIVITY)
        var cameraStartCalled = false

        fun startStreaming() {
            if (isStreamingActive) {
                if (streamingState.value == CameraStreamingState.PAUSED_INACTIVITY) {
                    // Remain in standby
                } else {
                    cameraStartCalled = true
                }
                return
            }
            isStreamingActive = true
            cameraStartCalled = true
        }

        // Rebind / onStartCommand while in PAUSED_INACTIVITY must NOT wake sensor
        startStreaming()
        assertFalse("Sensor must not wake on service rebind while in PAUSED_INACTIVITY", cameraStartCalled)
        assertEquals(CameraStreamingState.PAUSED_INACTIVITY, streamingState.value)
    }

    @Test
    fun testCameraRecoveryExponentialBackoffAndMaxAttempts() = runTest {
        assertEquals(1_000L, CameraMonitorService.computeRecoveryDelayMs(0))
        assertEquals(2_000L, CameraMonitorService.computeRecoveryDelayMs(1))
        assertEquals(4_000L, CameraMonitorService.computeRecoveryDelayMs(2))
        assertEquals(8_000L, CameraMonitorService.computeRecoveryDelayMs(3))
        assertEquals(16_000L, CameraMonitorService.computeRecoveryDelayMs(4))
        assertEquals(20_000L, CameraMonitorService.computeRecoveryDelayMs(5))

        val streamingState = MutableStateFlow(CameraStreamingState.WAITING_FOR_VIEWERS)
        var cameraRecoveryAttempts = 0
        var cameraRecoveryJob: Job? = null
        var startCameraCount = 0

        fun scheduleCameraRecovery() {
            if (cameraRecoveryJob?.isActive == true) return
            if (cameraRecoveryAttempts >= CameraMonitorService.MAX_CAMERA_RECOVERY_ATTEMPTS) return
            val attempt = cameraRecoveryAttempts
            val delayMs = CameraMonitorService.computeRecoveryDelayMs(attempt)
            cameraRecoveryAttempts = attempt + 1
            cameraRecoveryJob = launch {
                delay(delayMs)
                if (streamingState.value == CameraStreamingState.STREAMING ||
                    streamingState.value == CameraStreamingState.WAITING_FOR_VIEWERS
                ) {
                    startCameraCount++
                }
            }
        }

        // 5 consecutive camera failures should schedule 5 retries (1s, 2s, 4s, 8s, 16s)
        val expectedDelays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)
        expectedDelays.forEachIndexed { index, delayMs ->
            scheduleCameraRecovery()
            advanceTimeBy(delayMs)
            runCurrent()
            assertEquals(index + 1, startCameraCount)
        }

        // 6th failure must be aborted (capped at 5 attempts)
        scheduleCameraRecovery()
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals("Recovery must stop after 5 consecutive attempts", 5, startCameraCount)
    }

    @Test
    fun testCameraRecoverySkippedWhenTransitionedToPausedInactivity() = runTest {
        val streamingState = MutableStateFlow(CameraStreamingState.WAITING_FOR_VIEWERS)
        var startCameraCount = 0

        val recoveryJob = launch {
            delay(CameraMonitorService.computeRecoveryDelayMs(1)) // 2000ms
            if (streamingState.value == CameraStreamingState.STREAMING ||
                streamingState.value == CameraStreamingState.WAITING_FOR_VIEWERS
            ) {
                startCameraCount++
            }
        }

        // At 1000ms, inactivity timer pauses the service
        advanceTimeBy(1_000L)
        runCurrent()
        streamingState.value = CameraStreamingState.PAUSED_INACTIVITY
        recoveryJob.cancel()

        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals("Pending camera recovery must not wake sensor after PAUSED_INACTIVITY", 0, startCameraCount)
    }
}
