package com.a0soft.gphone.camonitor.camera

import com.a0soft.gphone.camonitor.ui.camera.BlackScreenUnlockPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackScreenUnlockPolicyTest {

    @Test
    fun testConstants() {
        assertEquals(300L, BlackScreenUnlockPolicy.LOCK_ARM_DELAY_MS)
        assertEquals(1_500L, BlackScreenUnlockPolicy.HINT_DELAY_MS)
        assertEquals(0.01f, BlackScreenUnlockPolicy.LOCKED_BRIGHTNESS, 0.001f)
    }

    @Test
    fun testZeroWidthStillRequiresTheMinimumSwipe() {
        assertEquals(120f, BlackScreenUnlockPolicy.requiredSwipePx(0f), 0.001f)
    }

    @Test
    fun testNegativeWidthIsClamped() {
        assertEquals(120f, BlackScreenUnlockPolicy.requiredSwipePx(-500f), 0.001f)
    }

    @Test
    fun testNarrowScreenUsesThe120PxFloor() {
        // 30% of 300 px is 90 px, below the floor.
        assertEquals(120f, BlackScreenUnlockPolicy.requiredSwipePx(300f), 0.001f)
        assertEquals(120f, BlackScreenUnlockPolicy.requiredSwipePx(399f), 0.001f)
    }

    @Test
    fun testWideScreenUses30Percent() {
        assertEquals(150f, BlackScreenUnlockPolicy.requiredSwipePx(500f), 0.001f)
        assertEquals(300f, BlackScreenUnlockPolicy.requiredSwipePx(1000f), 0.001f)
    }

    @Test
    fun testThresholdIsInclusive() {
        assertTrue(
            "Exactly 30% of the width should unlock",
            BlackScreenUnlockPolicy.shouldUnlock(300f, 0f, 1000f, 300L)
        )
    }

    @Test
    fun testOnePixelShortDoesNotUnlock() {
        assertFalse(
            "A swipe just below the threshold must be ignored",
            BlackScreenUnlockPolicy.shouldUnlock(299f, 0f, 1000f, 300L)
        )
    }

    @Test
    fun testGestureInsideThe300MsGateIsRejected() {
        assertFalse(
            "The tap that locked the screen must not unlock it",
            BlackScreenUnlockPolicy.shouldUnlock(400f, 0f, 1000f, 299L)
        )
    }

    @Test
    fun testGestureAtExactly300MsIsAccepted() {
        assertTrue(BlackScreenUnlockPolicy.shouldUnlock(400f, 0f, 1000f, 300L))
    }

    @Test
    fun testVerticalSwipeCounts() {
        assertTrue(BlackScreenUnlockPolicy.shouldUnlock(0f, 200f, 500f, 1_000L))
    }

    @Test
    fun testReverseSwipeCountsBecauseDistanceIsAbsolute() {
        assertTrue(BlackScreenUnlockPolicy.shouldUnlock(-400f, 0f, 1000f, 1_000L))
    }

    @Test
    fun testLongerAxisWinsForDiagonalSwipes() {
        assertTrue(
            "A diagonal gesture should use the longer axis, not the sum",
            BlackScreenUnlockPolicy.shouldUnlock(10f, 130f, 300f, 1_000L)
        )
    }

    @Test
    fun testTapWithoutMovementNeverUnlocks() {
        assertFalse(BlackScreenUnlockPolicy.shouldUnlock(0f, 0f, 1000f, 1_000L))
    }

    @Test
    fun testShouldKeepScreenOnOnlyWhenUnlocked() {
        assertTrue(BlackScreenUnlockPolicy.shouldKeepScreenOn(isLocked = false))
        assertFalse(BlackScreenUnlockPolicy.shouldKeepScreenOn(isLocked = true))
    }
}
