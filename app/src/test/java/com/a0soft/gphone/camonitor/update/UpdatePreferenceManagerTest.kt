package com.a0soft.gphone.camonitor.update

import com.a0soft.gphone.camonitor.data.update.UpdatePreferenceManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePreferenceManagerTest {

    @Test
    fun testShouldCheckWhenFirstTime() {
        val lastCheckTime = 0L
        val now = 100_000L
        val should = UpdatePreferenceManager.shouldCheck(lastCheckTime, now)
        assertTrue("Must check on first launch (lastCheck = 0)", should)
    }

    @Test
    fun testShouldCheckCooldownNotElapsed() {
        val now = 1_000_000_000L
        val lastCheckTime = now - (2 * 60 * 60 * 1000L) // 2 hours ago
        val should = UpdatePreferenceManager.shouldCheck(lastCheckTime, now)
        assertFalse("Must not check if 24 hours have not elapsed", should)
    }

    @Test
    fun testShouldCheckCooldownElapsed() {
        val now = 1_000_000_000L
        val lastCheckTime = now - (25 * 60 * 60 * 1000L) // 25 hours ago
        val should = UpdatePreferenceManager.shouldCheck(lastCheckTime, now)
        assertTrue("Must check if 24 hours have elapsed", should)
    }

    @Test
    fun testIsVersionSkippedWhenNull() {
        assertFalse(UpdatePreferenceManager.isVersionSkipped(null, "1.0.9"))
    }

    @Test
    fun testIsVersionSkippedWhenSameVersion() {
        assertTrue(UpdatePreferenceManager.isVersionSkipped("1.0.9", "1.0.9"))
    }

    @Test
    fun testIsVersionSkippedWhenOlderVersion() {
        assertTrue(UpdatePreferenceManager.isVersionSkipped("1.0.9", "1.0.8"))
    }

    @Test
    fun testIsVersionSkippedWhenNewerVersionPublished() {
        assertFalse("Newer version must not be skipped even if older version was skipped",
            UpdatePreferenceManager.isVersionSkipped("1.0.9", "1.1.0"))
    }
}
