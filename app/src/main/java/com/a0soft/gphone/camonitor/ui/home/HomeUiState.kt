package com.a0soft.gphone.camonitor.ui.home

import androidx.compose.runtime.Immutable
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.update.ReleaseInfo
import com.a0soft.gphone.camonitor.ui.common.BatteryInfo

@Immutable
data class HomeUiState(
    val isLoaded: Boolean = false,
    val loadError: String? = null,
    val cameraName: String = "",
    val pairedViewers: List<PairedDevice> = emptyList(),
    val pairedCameras: List<PairedDevice> = emptyList(),
    val lastConnectedMap: Map<String, Long> = emptyMap(),
    val batteryInfo: BatteryInfo = BatteryInfo(percentage = -1, isCharging = false),
    val networkType: NetworkType = NetworkType.DISCONNECTED,
    val availableRelease: ReleaseInfo? = null,
    val isStealthIntroSeen: Boolean = false
) {
    val layout: HomeLayout
        get() = homeLayoutFor(pairedCameraCount = pairedCameras.size, pairedViewerCount = pairedViewers.size)
}
