package com.a0soft.gphone.camonitor.viewer

import com.a0soft.gphone.camonitor.data.model.ViewerConnectionProgress
import com.a0soft.gphone.camonitor.data.model.ViewerConnectionStep
import com.a0soft.gphone.camonitor.data.security.DefaultSecurityRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerConnectionProgressTest {

    @Test
    fun testFourStepPipelineProgressionAndCompletionStates() {
        // Step 1: Connecting to network
        val step1 = ViewerConnectionProgress(currentStep = ViewerConnectionStep.CONNECTING_NETWORK)
        assertTrue(step1.isStepActive(ViewerConnectionStep.CONNECTING_NETWORK))
        assertFalse(step1.isStepCompleted(ViewerConnectionStep.CONNECTING_NETWORK))
        assertFalse(step1.isStepCompleted(ViewerConnectionStep.REACHING_CAMERA))
        assertFalse(step1.isStepCompleted(ViewerConnectionStep.ESTABLISHING_LINK))
        assertFalse(step1.isStepCompleted(ViewerConnectionStep.STARTING_VIDEO))

        // Step 2: Reaching camera (Step 1 done in 180ms)
        val step2 = step1.copy(
            currentStep = ViewerConnectionStep.REACHING_CAMERA,
            step1DurationMs = 180L
        )
        assertTrue(step2.isStepCompleted(ViewerConnectionStep.CONNECTING_NETWORK))
        assertTrue(step2.isStepActive(ViewerConnectionStep.REACHING_CAMERA))
        assertFalse(step2.isStepCompleted(ViewerConnectionStep.REACHING_CAMERA))
        assertEquals(180L, step2.stepDurationMs(ViewerConnectionStep.CONNECTING_NETWORK))
        assertEquals("0.2", ViewerConnectionProgress.formatDurationSec(180L))

        // Step 3: Establishing secure link (Step 2 done in 420ms)
        val step3 = step2.copy(
            currentStep = ViewerConnectionStep.ESTABLISHING_LINK,
            step2DurationMs = 420L
        )
        assertTrue(step3.isStepCompleted(ViewerConnectionStep.CONNECTING_NETWORK))
        assertTrue(step3.isStepCompleted(ViewerConnectionStep.REACHING_CAMERA))
        assertTrue(step3.isStepActive(ViewerConnectionStep.ESTABLISHING_LINK))
        assertFalse(step3.isStepCompleted(ViewerConnectionStep.ESTABLISHING_LINK))
        assertEquals(420L, step3.stepDurationMs(ViewerConnectionStep.REACHING_CAMERA))
        assertEquals("0.4", ViewerConnectionProgress.formatDurationSec(420L))

        // Step 4: Starting live video (Step 3 done in 310ms)
        val step4 = step3.copy(
            currentStep = ViewerConnectionStep.STARTING_VIDEO,
            step3DurationMs = 310L,
            step4DurationMs = 150L
        )
        assertTrue(step4.isStepCompleted(ViewerConnectionStep.CONNECTING_NETWORK))
        assertTrue(step4.isStepCompleted(ViewerConnectionStep.REACHING_CAMERA))
        assertTrue(step4.isStepCompleted(ViewerConnectionStep.ESTABLISHING_LINK))
        assertTrue(step4.isStepActive(ViewerConnectionStep.STARTING_VIDEO))
        assertEquals(310L, step4.stepDurationMs(ViewerConnectionStep.ESTABLISHING_LINK))
        assertEquals(150L, step4.stepDurationMs(ViewerConnectionStep.STARTING_VIDEO))
    }

    @Test
    fun testCountdownAndSlowHintVisibilityThresholds() {
        // T = 0s, attempt 1 -> countdown hidden (normal fast connections complete in <2s without UI clutter)
        val t0 = ViewerConnectionProgress(
            currentStep = ViewerConnectionStep.REACHING_CAMERA,
            attempt = 1,
            remainingTimeoutSec = 15,
            totalTimeoutSec = 15
        )
        assertEquals(0, t0.elapsedWaitSec())
        assertFalse(t0.shouldShowCountdown())
        assertFalse(t0.shouldShowSlowHint())
        assertEquals(1.0f, t0.timeoutProgressFraction(), 0.001f)

        // T = 1s, attempt 1 -> still hidden
        val t1 = t0.copy(remainingTimeoutSec = 14)
        assertEquals(1, t1.elapsedWaitSec())
        assertFalse(t1.shouldShowCountdown())

        // T = 2s -> countdown revealed (13s left)
        val t2 = t0.copy(remainingTimeoutSec = 13)
        assertEquals(2, t2.elapsedWaitSec())
        assertTrue(t2.shouldShowCountdown())
        assertFalse(t2.shouldShowSlowHint())
        assertEquals(13f / 15f, t2.timeoutProgressFraction(), 0.001f)

        // T = 5s (10s left) -> slow hint revealed when camera has not sent CameraWaking ACK
        val t5 = t0.copy(attempt = 2, remainingTimeoutSec = 10, isCameraWakingUp = false)
        assertEquals(5, t5.elapsedWaitSec())
        assertTrue(t5.shouldShowCountdown())
        assertTrue(t5.shouldShowSlowHint())

        // If camera sent CameraWaking ACK (waking sensor from standby), suppress "check if offline" slow hint
        val t5Waking = t5.copy(isCameraWakingUp = true)
        assertTrue(t5Waking.shouldShowCountdown())
        assertFalse(t5Waking.shouldShowSlowHint())

        // Once moved to ESTABLISHING_LINK, countdown and slow hint are hidden
        val step3 = t5.copy(currentStep = ViewerConnectionStep.ESTABLISHING_LINK)
        assertFalse(step3.shouldShowCountdown())
        assertFalse(step3.shouldShowSlowHint())
    }

    @Test
    fun testRollingConnectDurationAverageCalculation() {
        var samples = emptyList<Long>()

        // Sample 1: 1200ms
        samples = DefaultSecurityRepository.updateConnectDurationSamples(samples, 1200L)
        assertEquals(listOf(1200L), samples)
        assertEquals(1200L, samples.sum() / samples.size)

        // Sample 2: 800ms -> avg 1000ms
        samples = DefaultSecurityRepository.updateConnectDurationSamples(samples, 800L)
        assertEquals(listOf(1200L, 800L), samples)
        assertEquals(1000L, samples.sum() / samples.size)

        // Sample 3: 1600ms -> avg 1200ms
        samples = DefaultSecurityRepository.updateConnectDurationSamples(samples, 1600L)
        assertEquals(listOf(1200L, 800L, 1600L), samples)
        assertEquals(1200L, samples.sum() / samples.size)

        // Sample 4: 900ms -> drops oldest (1200L), keeps [800L, 1600L, 900L], avg 1100ms
        samples = DefaultSecurityRepository.updateConnectDurationSamples(samples, 900L)
        assertEquals(listOf(800L, 1600L, 900L), samples)
        assertEquals(1100L, samples.sum() / samples.size)

        // Extreme values are clamped to [100ms, 30_000ms]
        val clampedLow = DefaultSecurityRepository.updateConnectDurationSamples(emptyList(), 5L)
        assertEquals(listOf(100L), clampedLow)

        val clampedHigh = DefaultSecurityRepository.updateConnectDurationSamples(emptyList(), 99_000L)
        assertEquals(listOf(30_000L), clampedHigh)
    }

    @Test
    fun testBounded15SecondCountdownAndMax5AttemptsLifecycle() = runTest {
        var progress = ViewerConnectionProgress(
            currentStep = ViewerConnectionStep.REACHING_CAMERA,
            attempt = 1,
            remainingTimeoutSec = ViewerConnectionProgress.DEFAULT_TIMEOUT_SEC
        )
        var isRemoteDescriptionSet = false
        var timedOut = false
        var offersTransmitted = 1 // Initial offer

        val countdownJob = launch {
            var remaining = ViewerConnectionProgress.DEFAULT_TIMEOUT_SEC
            while (isActive && !isRemoteDescriptionSet && remaining > 0) {
                delay(1000L)
                if (!isRemoteDescriptionSet) {
                    remaining--
                    progress = progress.copy(remainingTimeoutSec = remaining)
                }
            }
            if (isActive && !isRemoteDescriptionSet && remaining <= 0) {
                timedOut = true
            }
        }

        val retryJob = launch {
            var attempt = 1
            while (isActive && !isRemoteDescriptionSet && attempt < ViewerConnectionProgress.DEFAULT_MAX_ATTEMPTS) {
                delay(ViewerConnectionProgress.OFFER_RETRY_INTERVAL_MS)
                if (!isRemoteDescriptionSet && !timedOut) {
                    attempt++
                    offersTransmitted++
                    progress = progress.copy(attempt = attempt)
                }
            }
        }

        // At T = 3s: attempt 2, remaining 12s
        advanceTimeBy(3000L)
        runCurrent()
        assertEquals(2, progress.attempt)
        assertEquals(12, progress.remainingTimeoutSec)
        assertFalse(timedOut)

        // At T = 12s: attempt 5 (max attempts reached), remaining 3s
        advanceTimeBy(9000L)
        runCurrent()
        assertEquals(5, progress.attempt)
        assertEquals(5, offersTransmitted)
        assertEquals(3, progress.remainingTimeoutSec)
        assertFalse(timedOut)

        // At T = 15s: remaining reaches 0 -> timedOut = true, no 6th attempt
        advanceTimeBy(3000L)
        runCurrent()
        assertEquals(0, progress.remainingTimeoutSec)
        assertTrue("Must transition to timeout at 15s", timedOut)
        assertEquals("Total offers must be capped at 5", 5, offersTransmitted)

        countdownJob.cancel()
        retryJob.cancel()
    }
}
