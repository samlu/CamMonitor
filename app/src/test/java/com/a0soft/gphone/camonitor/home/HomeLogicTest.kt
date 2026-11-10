package com.a0soft.gphone.camonitor.home

import com.a0soft.gphone.camonitor.ui.home.BroadcasterLaunchRoute
import com.a0soft.gphone.camonitor.ui.home.HomeLayout
import com.a0soft.gphone.camonitor.ui.home.NetworkType
import com.a0soft.gphone.camonitor.ui.home.StealthLaunchIntent
import com.a0soft.gphone.camonitor.ui.home.broadcasterLaunchRoute
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
}
