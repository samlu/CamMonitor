package com.a0soft.gphone.camonitor.home

import com.a0soft.gphone.camonitor.ui.home.BroadcasterLaunchRoute
import com.a0soft.gphone.camonitor.ui.home.HomeLayout
import com.a0soft.gphone.camonitor.ui.home.LastConnectedTime
import com.a0soft.gphone.camonitor.ui.home.NetworkType
import com.a0soft.gphone.camonitor.ui.home.StealthLaunchIntent
import com.a0soft.gphone.camonitor.ui.home.broadcasterLaunchRoute
import com.a0soft.gphone.camonitor.ui.home.calculateLastConnectedTime
import com.a0soft.gphone.camonitor.ui.home.classifyNetwork
import com.a0soft.gphone.camonitor.ui.home.homeLayoutFor
import com.a0soft.gphone.camonitor.ui.home.shouldWarnLowBatteryBeforeBroadcast
import com.a0soft.gphone.camonitor.ui.home.isBatteryLow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogicTest {

    private fun net(
        internet: Boolean = true,
        validated: Boolean = true,
        wifi: Boolean = false,
        ethernet: Boolean = false,
        cellular: Boolean = false
    ) = classifyNetwork(internet, validated, wifi, ethernet, cellular)

    @Test
    fun noInternetCapabilityIsDisconnected() {
        assertEquals(NetworkType.DISCONNECTED, net(internet = false, validated = false, wifi = true))
    }

    @Test
    fun unvalidatedWifiIsNotReported_asReady() {
        // Captive portal / dead uplink must not show "Wi-Fi Ready".
        assertEquals(NetworkType.NO_INTERNET, net(validated = false, wifi = true))
    }

    @Test
    fun validatedTransportsMapCorrectly() {
        assertEquals(NetworkType.WIFI, net(wifi = true))
        assertEquals(NetworkType.ETHERNET, net(ethernet = true))
        assertEquals(NetworkType.CELLULAR, net(cellular = true))
    }

    @Test
    fun ethernetIsNeverReportedAsDisconnected() {
        assertTrue(net(ethernet = true) != NetworkType.DISCONNECTED)
    }

    @Test
    fun vpnOnlyValidatedLinkIsUsable() {
        assertEquals(NetworkType.WIFI, net())
    }

    @Test
    fun lowBatteryBoundaries() {
        assertTrue(isBatteryLow(20, isCharging = false))
        assertFalse(isBatteryLow(21, isCharging = false))
        assertFalse(isBatteryLow(5, isCharging = true))
        assertFalse(isBatteryLow(-1, isCharging = false)) // unknown level
    }

    @Test
    fun launchRoutePairsFirstWhenNoViewers() {
        assertEquals(BroadcasterLaunchRoute.PAIR_VIEWER_FIRST, broadcasterLaunchRoute(0))
        assertEquals(BroadcasterLaunchRoute.START_CAMERA, broadcasterLaunchRoute(1))
        assertEquals(BroadcasterLaunchRoute.START_CAMERA, broadcasterLaunchRoute(2))
    }

    @Test
    fun stealthIntentSurvivesPairingDetourThenClears() {
        val intent = StealthLaunchIntent()
        assertFalse(intent.pending)

        // Stealth tapped with 0 viewers: routed to pairing, intent kept for the camera that follows.
        intent.request(true)
        assertTrue(intent.pending)

        // Leaving camera mode / pairing clears it so it cannot leak into a later normal launch.
        intent.clear()
        assertFalse(intent.pending)
    }

    @Test
    fun viewerOnlyDeviceShowsCamerasFirst() {
        assertEquals(HomeLayout.CAMERAS_FIRST, homeLayoutFor(pairedCameraCount = 1, pairedViewerCount = 0))
        assertEquals(HomeLayout.CAMERAS_FIRST, homeLayoutFor(pairedCameraCount = 8, pairedViewerCount = 0))
    }

    @Test
    fun otherCombinationsKeepDefaultOrder() {
        assertEquals(HomeLayout.BROADCASTER_FIRST, homeLayoutFor(0, 0))
        assertEquals(HomeLayout.BROADCASTER_FIRST, homeLayoutFor(0, 2))
        assertEquals(HomeLayout.BROADCASTER_FIRST, homeLayoutFor(3, 1)) // both roles: stable order
    }

    @Test
    fun lowBatteryBroadcastWarningBoundaries() {
        assertTrue(shouldWarnLowBatteryBeforeBroadcast(30, isCharging = false))
        assertFalse(shouldWarnLowBatteryBeforeBroadcast(31, isCharging = false))
        assertFalse(shouldWarnLowBatteryBeforeBroadcast(10, isCharging = true))
        assertFalse(shouldWarnLowBatteryBeforeBroadcast(-1, isCharging = false)) // unknown level
        // Warns earlier than the red pre-flight bar threshold (20%).
        assertTrue(shouldWarnLowBatteryBeforeBroadcast(25, false) && !isBatteryLow(25, false))
    }

    @Test
    fun normalLaunchOverridesEarlierStealthRequest() {
        val intent = StealthLaunchIntent()
        intent.request(true)
        intent.request(false)
        assertFalse(intent.pending)
    }

    @Test
    fun calculateLastConnectedTime_nullOrNonPositive_returnsNever() {
        assertEquals(LastConnectedTime.Never, calculateLastConnectedTime(null, now = 100_000L))
        assertEquals(LastConnectedTime.Never, calculateLastConnectedTime(0L, now = 100_000L))
        assertEquals(LastConnectedTime.Never, calculateLastConnectedTime(-500L, now = 100_000L))
    }

    @Test
    fun calculateLastConnectedTime_underOneMinute_returnsJustNow() {
        val now = 1_000_000L
        assertEquals(LastConnectedTime.JustNow, calculateLastConnectedTime(now, now = now))
        assertEquals(LastConnectedTime.JustNow, calculateLastConnectedTime(now - 30_000L, now = now))
        assertEquals(LastConnectedTime.JustNow, calculateLastConnectedTime(now - 59_999L, now = now))
    }

    @Test
    fun calculateLastConnectedTime_futureTimestamp_coercedToJustNow() {
        val now = 1_000_000L
        // Local clock drift where last connected is slightly in the future
        assertEquals(LastConnectedTime.JustNow, calculateLastConnectedTime(now + 10_000L, now = now))
    }

    @Test
    fun calculateLastConnectedTime_minutesAgo() {
        val now = 10_000_000L
        assertEquals(LastConnectedTime.MinutesAgo(1), calculateLastConnectedTime(now - 60_000L, now = now))
        assertEquals(LastConnectedTime.MinutesAgo(25), calculateLastConnectedTime(now - 25 * 60_000L, now = now))
        assertEquals(LastConnectedTime.MinutesAgo(59), calculateLastConnectedTime(now - 59 * 60_000L, now = now))
    }

    @Test
    fun calculateLastConnectedTime_hoursAgo() {
        val now = 100_000_000L
        val hourMs = 60 * 60_000L
        assertEquals(LastConnectedTime.HoursAgo(1), calculateLastConnectedTime(now - hourMs, now = now))
        assertEquals(LastConnectedTime.HoursAgo(5), calculateLastConnectedTime(now - 5 * hourMs, now = now))
        assertEquals(LastConnectedTime.HoursAgo(23), calculateLastConnectedTime(now - 23 * hourMs, now = now))
    }

    @Test
    fun calculateLastConnectedTime_daysAgo() {
        val now = 1_000_000_000L
        val dayMs = 24 * 60 * 60_000L
        assertEquals(LastConnectedTime.DaysAgo(1), calculateLastConnectedTime(now - dayMs, now = now))
        assertEquals(LastConnectedTime.DaysAgo(3), calculateLastConnectedTime(now - 3 * dayMs, now = now))
        assertEquals(LastConnectedTime.DaysAgo(6), calculateLastConnectedTime(now - 6 * dayMs, now = now))
    }

    @Test
    fun calculateLastConnectedTime_sevenDaysOrMore_returnsExactDate() {
        val now = 1_700_000_000_000L // realistic epoch timestamp
        val dayMs = 24 * 60 * 60_000L
        val ts7d = now - 7 * dayMs
        val ts30d = now - 30 * dayMs
        assertEquals(LastConnectedTime.ExactDate(ts7d), calculateLastConnectedTime(ts7d, now = now))
        assertEquals(LastConnectedTime.ExactDate(ts30d), calculateLastConnectedTime(ts30d, now = now))
    }
}
