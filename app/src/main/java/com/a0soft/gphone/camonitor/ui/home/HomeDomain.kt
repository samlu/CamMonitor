package com.a0soft.gphone.camonitor.ui.home

import com.a0soft.gphone.camonitor.data.update.ReleaseCheckResult
import com.a0soft.gphone.camonitor.data.update.ReleaseInfo
import com.a0soft.gphone.camonitor.ui.common.BatteryInfo
import kotlinx.coroutines.flow.Flow

interface HomePreferencesDataSource {
    fun isStealthIntroSeen(): Boolean
    fun setStealthIntroSeen(seen: Boolean)
    fun getLastConnectedMap(): Map<String, Long>
    fun clearConnectionHistory(deviceId: String)
}

interface TelemetryDataSource {
    val batteryFlow: Flow<BatteryInfo>
    val networkFlow: Flow<NetworkType>
}

interface UpdateRepository {
    suspend fun getCachedRelease(): ReleaseInfo?
    suspend fun saveCachedRelease(release: ReleaseInfo)
    suspend fun clearCachedRelease()
    suspend fun isVersionSkipped(version: String): Boolean
    suspend fun skipVersion(version: String)
    suspend fun shouldCheck(): Boolean
    suspend fun recordCheck()
    suspend fun checkLatestRelease(): ReleaseCheckResult
}

fun interface PeerCloser {
    fun closePeer(deviceId: String)
}

/**
 * Sanitizes device names: strips ISO control characters and clamps to maxCodePoints (default 32)
 * using code points to prevent slicing multi-byte UTF-16 surrogate pairs (e.g. emojis).
 */
fun sanitizeDeviceName(rawName: String, maxCodePoints: Int = 32): String {
    val filtered = rawName.trim().filter { !it.isISOControl() }
    val codePoints = filtered.codePoints().toArray()
    if (codePoints.size <= maxCodePoints) return filtered
    return String(codePoints, 0, maxCodePoints)
}
