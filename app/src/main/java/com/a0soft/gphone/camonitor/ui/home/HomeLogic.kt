package com.a0soft.gphone.camonitor.ui.home

import android.content.Context

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

sealed class LastConnectedTime {
    object Never : LastConnectedTime()
    object JustNow : LastConnectedTime()
    data class MinutesAgo(val minutes: Long) : LastConnectedTime()
    data class HoursAgo(val hours: Long) : LastConnectedTime()
    data class DaysAgo(val days: Long) : LastConnectedTime()
    data class ExactDate(val timestamp: Long) : LastConnectedTime()
}

/**
 * Categorizes the elapsed duration since the last active streaming session into
 * UI-friendly relative buckets (e.g. Just now, 5 min ago, 2 hr ago, 3 d ago, or absolute date).
 * Pure function with zero Android dependencies, fully testable on the JVM.
 */
fun calculateLastConnectedTime(
    lastConnectedTimestamp: Long?,
    now: Long = System.currentTimeMillis()
): LastConnectedTime {
    if (lastConnectedTimestamp == null || lastConnectedTimestamp <= 0L) {
        return LastConnectedTime.Never
    }
    val diff = (now - lastConnectedTimestamp).coerceAtLeast(0L)
    val minutes = diff / (60 * 1000L)
    val hours = diff / (60 * 60 * 1000L)
    val days = diff / (24 * 60 * 60 * 1000L)

    return when {
        minutes < 1 -> LastConnectedTime.JustNow
        minutes < 60 -> LastConnectedTime.MinutesAgo(minutes)
        hours < 24 -> LastConnectedTime.HoursAgo(hours)
        days < 7 -> LastConnectedTime.DaysAgo(days)
        else -> LastConnectedTime.ExactDate(lastConnectedTimestamp)
    }
}

/**
 * Persists and retrieves the last successful WebRTC streaming timestamp per camera device ID.
 * Backed by the lightweight non-security UI preferences ("cammonitor_ui_prefs").
 */
object CameraConnectionHistory {
    private const val PREFS_NAME = "cammonitor_ui_prefs"
    private const val KEY_PREFIX = "last_connected_"

    fun recordConnection(context: Context, deviceId: String, timestamp: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PREFIX + deviceId, timestamp)
            .apply()
    }

    fun getLastConnected(context: Context, deviceId: String): Long? {
        val ts = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_PREFIX + deviceId, 0L)
        return if (ts > 0L) ts else null
    }

    fun getAll(context: Context): Map<String, Long> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val result = mutableMapOf<String, Long>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value is Long && value > 0L) {
                result[key.removePrefix(KEY_PREFIX)] = value
            }
        }
        return result
    }

    fun clear(context: Context, deviceId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFIX + deviceId)
            .apply()
    }
}
