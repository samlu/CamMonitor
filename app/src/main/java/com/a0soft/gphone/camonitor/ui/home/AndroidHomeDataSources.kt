package com.a0soft.gphone.camonitor.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.a0soft.gphone.camonitor.data.update.ReleaseCheckResult
import com.a0soft.gphone.camonitor.data.update.ReleaseChecker
import com.a0soft.gphone.camonitor.data.update.ReleaseInfo
import com.a0soft.gphone.camonitor.data.update.UpdatePreferenceManager
import androidx.core.content.edit
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.ui.common.BatteryHelper
import com.a0soft.gphone.camonitor.ui.common.BatteryInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

class AndroidHomePreferences(context: Context) : HomePreferencesDataSource {
    private val prefs = context.getSharedPreferences("cammonitor_ui_prefs", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    override fun isStealthIntroSeen(): Boolean = prefs.getBoolean("stealth_intro_seen", false)

    override fun setStealthIntroSeen(seen: Boolean) {
        prefs.edit { putBoolean("stealth_intro_seen", seen) }
    }

    override fun getLastConnectedMap(): Map<String, Long> = CameraConnectionHistory.getAll(appContext)

    override fun clearConnectionHistory(deviceId: String) {
        CameraConnectionHistory.clear(appContext, deviceId)
    }
}

class AndroidTelemetryDataSource(private val context: Context) : TelemetryDataSource {

    override val batteryFlow: Flow<BatteryInfo> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                trySend(BatteryHelper.getBatteryInfo(context))
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        trySend(BatteryHelper.getBatteryInfo(context))
        awaitClose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }.conflate()

    override val networkFlow: Flow<NetworkType> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            trySend(NetworkType.DISCONNECTED)
            awaitClose {}
            return@callbackFlow
        }

        fun currentStatus(): NetworkType {
            val active = cm.activeNetwork ?: return NetworkType.DISCONNECTED
            val caps = cm.getNetworkCapabilities(active) ?: return NetworkType.DISCONNECTED
            return classifyNetwork(
                hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                hasEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            )
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(currentStatus()) }
            override fun onLost(network: Network) { trySend(currentStatus()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(currentStatus()) }
        }

        try {
            cm.registerDefaultNetworkCallback(callback)
            trySend(currentStatus())
        } catch (e: Exception) {
            AppLogger.w("AndroidTelemetry", "Failed to register network callback: ${e.message}")
            trySend(NetworkType.DISCONNECTED)
        }

        awaitClose {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {}
        }
    }.conflate()
}

class AndroidUpdateRepository(context: Context) : UpdateRepository {
    private val updatePrefs = UpdatePreferenceManager(context)

    override suspend fun getCachedRelease(): ReleaseInfo? = updatePrefs.getCachedRelease()
    override suspend fun saveCachedRelease(release: ReleaseInfo) = updatePrefs.saveCachedRelease(release)
    override suspend fun clearCachedRelease() = updatePrefs.clearCachedRelease()
    override suspend fun isVersionSkipped(version: String): Boolean = updatePrefs.isVersionSkipped(version)
    override suspend fun skipVersion(version: String) = updatePrefs.skipVersion(version)
    override suspend fun shouldCheck(): Boolean = updatePrefs.shouldCheck()
    override suspend fun recordCheck() = updatePrefs.recordCheck()
    override suspend fun checkLatestRelease(): ReleaseCheckResult = ReleaseChecker.checkLatestRelease()
}
