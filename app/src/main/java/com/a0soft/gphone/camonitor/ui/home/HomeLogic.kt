package com.a0soft.gphone.camonitor.ui.home

/** Network state shown in the Home pre-flight bar. */
enum class NetworkType {
    WIFI,
    ETHERNET,
    CELLULAR,
    /** A network is attached but has no validated internet (captive portal, dead uplink). */
    NO_INTERNET,
    DISCONNECTED
}

/**
 * Pure mapping from [android.net.NetworkCapabilities] flags to [NetworkType], kept free of
 * Android types so it can be unit-tested on the JVM.
 */
fun classifyNetwork(
    hasInternet: Boolean,
    isValidated: Boolean,
    hasWifi: Boolean,
    hasEthernet: Boolean,
    hasCellular: Boolean
): NetworkType = when {
    !hasInternet -> NetworkType.DISCONNECTED
    !isValidated -> NetworkType.NO_INTERNET
    hasWifi -> NetworkType.WIFI
    hasEthernet -> NetworkType.ETHERNET
    hasCellular -> NetworkType.CELLULAR
    // VPN-only or other validated transport: treat as a usable local link.
    else -> NetworkType.WIFI
}

/** Low battery rule shared by the pre-flight bar: <= 20% and not charging. */
fun isBatteryLow(percentage: Int, isCharging: Boolean): Boolean =
    percentage in 0..LOW_BATTERY_PERCENT && !isCharging

const val LOW_BATTERY_PERCENT = 20

/** Earlier, softer threshold used for the inline warning next to the Start buttons. */
const val LOW_BATTERY_BROADCAST_WARN_PERCENT = 30

fun shouldWarnLowBatteryBeforeBroadcast(percentage: Int, isCharging: Boolean): Boolean =
    percentage in 0..LOW_BATTERY_BROADCAST_WARN_PERCENT && !isCharging

/** What the Broadcaster card's launch buttons should do. */
enum class BroadcasterLaunchRoute { PAIR_VIEWER_FIRST, START_CAMERA }

fun broadcasterLaunchRoute(pairedViewerCount: Int): BroadcasterLaunchRoute =
    if (pairedViewerCount == 0) BroadcasterLaunchRoute.PAIR_VIEWER_FIRST else BroadcasterLaunchRoute.START_CAMERA

/**
 * Tracks whether the next Camera Mode entry should start in the OLED black screen.
 * The intent survives the pairing detour (Stealth tapped with 0 viewers) and is cleared
 * whenever the user leaves Camera Pairing or Camera Mode, so it can never leak into a later launch.
 */
class StealthLaunchIntent {
    var pending: Boolean = false
        private set

    fun request(stealth: Boolean) { pending = stealth }
    fun clear() { pending = false }
}

/** Section ordering on the Home screen. */
enum class HomeLayout {
    /** Default: Broadcaster console first, then Monitored Cameras. */
    BROADCASTER_FIRST,

    /** Viewer-only device: cameras on top, Broadcaster collapsed to a compact row. */
    CAMERAS_FIRST
}

/**
 * Viewer-only devices (cameras paired, no viewers paired) never use the big Broadcaster card, so
 * the cameras they actually watch go first. Any other combination keeps the stable default order.
 */
fun homeLayoutFor(pairedCameraCount: Int, pairedViewerCount: Int): HomeLayout =
    if (pairedCameraCount > 0 && pairedViewerCount == 0) HomeLayout.CAMERAS_FIRST else HomeLayout.BROADCASTER_FIRST
