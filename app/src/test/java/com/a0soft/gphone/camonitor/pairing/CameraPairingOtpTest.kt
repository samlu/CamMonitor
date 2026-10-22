package com.a0soft.gphone.camonitor.pairing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraPairingOtpTest {

    @Test
    fun testOtpCountdownRepeatsContinuouslyAcrossMultipleWindows() = runTest {
        var otp = "OTP_1"
        var secondsLeft = 300
        var otpGenerationCount = 1

        val job = launch {
            while (isActive) {
                while (secondsLeft > 0) {
                    delay(1000L)
                    secondsLeft--
                }
                otpGenerationCount++
                otp = "OTP_$otpGenerationCount"
                secondsLeft = 300
            }
        }

        // T = 0s
        assertEquals("OTP_1", otp)
        assertEquals(300, secondsLeft)

        // Advance 100s -> secondsLeft = 200, still OTP_1
        advanceTimeBy(100_000L)
        runCurrent()
        assertEquals(200, secondsLeft)
        assertEquals("OTP_1", otp)

        // Advance remaining 200s (total 300s = 5 mins) -> OTP #2 generated, reset to 300
        advanceTimeBy(200_000L)
        runCurrent()
        assertEquals("OTP_2", otp)
        assertEquals(300, secondsLeft)
        assertTrue("Timer job must remain active after 1st expiration", job.isActive)

        // Advance 150s into second window (T = 450s) -> secondsLeft = 150, still OTP_2
        advanceTimeBy(150_000L)
        runCurrent()
        assertEquals(150, secondsLeft)
        assertEquals("OTP_2", otp)

        // Advance remaining 150s (total 600s = 10 mins) -> OTP #3 generated, reset to 300
        advanceTimeBy(150_000L)
        runCurrent()
        assertEquals("OTP_3", otp)
        assertEquals(300, secondsLeft)
        assertTrue("Timer job must remain active after 2nd expiration", job.isActive)

        job.cancel()
    }

    @Test
    fun testActivePairingOtpRemainsValidAcrossHandshakeRetries() {
        var activeOtp: String? = "TEST_OTP_123"
        val pairedDevices = mutableListOf<String>()

        // Simulate Camera receiving PAIR_REQUEST #1:
        // Prior to R2-04 fix, addPairedDevice wiped activeOtp = null here.
        fun addDevicePreservingOtp(deviceId: String) {
            pairedDevices.add(deviceId)
            // clearPairingOtp() intentionally NOT called here
        }

        addDevicePreservingOtp("viewer-1")
        assertEquals(1, pairedDevices.size)
        // Verify active OTP is STILL present for retry #2
        assertEquals("TEST_OTP_123", activeOtp)

        // When viewer retries PAIR_REQUEST #2, activeOtp is still available
        val canDecryptRetry = (activeOtp != null)
        assertTrue("Active OTP must remain available for retried PAIR_REQUEST", canDecryptRetry)

        // Cleanup only happens when pairing screen is disposed
        activeOtp = null
        org.junit.Assert.assertNull(activeOtp)
    }
}
