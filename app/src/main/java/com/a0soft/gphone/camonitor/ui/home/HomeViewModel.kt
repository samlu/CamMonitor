package com.a0soft.gphone.camonitor.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.a0soft.gphone.camonitor.BuildConfig
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.data.update.ReleaseCheckResult
import com.a0soft.gphone.camonitor.data.update.ReleaseInfo
import com.a0soft.gphone.camonitor.log.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HomeViewModel(
    private val securityRepo: SecurityRepository,
    private val preferencesDataSource: HomePreferencesDataSource,
    private val telemetryDataSource: TelemetryDataSource,
    private val updateRepository: UpdateRepository,
    private val peerCloser: PeerCloser,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private data class InternalState(
        val isLoaded: Boolean = false,
        val loadError: String? = null,
        val cameraName: String = "",
        val pairedViewers: List<PairedDevice> = emptyList(),
        val pairedCameras: List<PairedDevice> = emptyList(),
        val lastConnectedMap: Map<String, Long> = emptyMap(),
        val availableRelease: ReleaseInfo? = null,
        val isStealthIntroSeen: Boolean = false
    )

    private val _internalState = MutableStateFlow(InternalState())
    private val activationMutex = Mutex()
    private val mutationMutex = Mutex()

    private val telemetryFlow = combine(
        telemetryDataSource.batteryFlow.distinctUntilChanged().onStart { emit(com.a0soft.gphone.camonitor.ui.common.BatteryInfo(percentage = -1, isCharging = false)) },
        telemetryDataSource.networkFlow.distinctUntilChanged().onStart { emit(NetworkType.DISCONNECTED) }
    ) { battery, network -> battery to network }

    val uiState: StateFlow<HomeUiState> = combine(_internalState, telemetryFlow) { internal, (battery, network) ->
        HomeUiState(
            isLoaded = internal.isLoaded,
            loadError = internal.loadError,
            cameraName = internal.cameraName,
            pairedViewers = internal.pairedViewers,
            pairedCameras = internal.pairedCameras,
            lastConnectedMap = internal.lastConnectedMap,
            batteryInfo = battery,
            networkType = network,
            availableRelease = internal.availableRelease,
            isStealthIntroSeen = internal.isStealthIntroSeen
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
    )

    fun onActivated() {
        viewModelScope.launch {
            activationMutex.withLock {
                withContext(ioDispatcher) {
                    reloadLocalData()
                    evaluateCachedRelease()
                    triggerNetworkUpdateCheck()
                }
            }
        }
    }

    private fun reloadLocalData() {
        val viewersRes = runCatching { securityRepo.getPairedViewers() }
        val camerasRes = runCatching { securityRepo.getPairedCameras() }
        val nameRes = runCatching { securityRepo.getDeviceName() }
        val seenRes = runCatching { preferencesDataSource.isStealthIntroSeen() }
        val lastConnRes = runCatching { preferencesDataSource.getLastConnectedMap() }

        val firstError = viewersRes.exceptionOrNull()
            ?: camerasRes.exceptionOrNull()
            ?: nameRes.exceptionOrNull()
            ?: seenRes.exceptionOrNull()
            ?: lastConnRes.exceptionOrNull()

        if (firstError != null) {
            if (firstError is CancellationException) throw firstError
            AppLogger.w("HomeViewModel", "Partial or total failure reading local data: ${firstError.message}")
        }

        _internalState.update { current ->
            current.copy(
                isLoaded = true,
                loadError = firstError?.message,
                cameraName = nameRes.getOrDefault(current.cameraName),
                pairedViewers = viewersRes.getOrDefault(current.pairedViewers),
                pairedCameras = camerasRes.getOrDefault(current.pairedCameras),
                lastConnectedMap = lastConnRes.getOrDefault(current.lastConnectedMap),
                isStealthIntroSeen = seenRes.getOrDefault(current.isStealthIntroSeen)
            )
        }
    }

    fun renameCamera(newName: String) {
        val sanitized = sanitizeDeviceName(newName)
        if (sanitized.isEmpty()) return

        viewModelScope.launch {
            mutationMutex.withLock {
                withContext(ioDispatcher) {
                    runCatching {
                        securityRepo.setDeviceName(sanitized)
                        _internalState.update { it.copy(cameraName = sanitized) }
                    }.onFailure { e ->
                        if (e is CancellationException) throw e
                        AppLogger.w("HomeViewModel", "Failed to rename camera: ${e.message}")
                    }
                }
            }
        }
    }

    fun unpairDevice(deviceId: String) {
        viewModelScope.launch {
            mutationMutex.withLock {
                withContext(ioDispatcher) {
                    try {
                        // 1. Whitelist record revoked FIRST so no incoming signaling is accepted
                        runCatching { securityRepo.removePairedDevice(deviceId) }
                            .onFailure { e ->
                                if (e is CancellationException) throw e
                                AppLogger.w("HomeViewModel", "Failed to revoke paired device: ${e.message}")
                            }

                        // 2. Tear down WebRTC peer connection IMMEDIATELY
                        runCatching { peerCloser.closePeer(deviceId) }
                            .onFailure { e ->
                                if (e is CancellationException) throw e
                                AppLogger.w("HomeViewModel", "Failed to close WebRTC peer $deviceId: ${e.message}")
                            }

                        // 3. Purge auxiliary connection history independently
                        runCatching { preferencesDataSource.clearConnectionHistory(deviceId) }
                            .onFailure { e ->
                                if (e is CancellationException) throw e
                                AppLogger.w("HomeViewModel", "Failed to clear connection history for $deviceId: ${e.message}")
                            }
                    } finally {
                        // 4. Always reload so the UI accurately reflects latest state
                        reloadLocalData()
                    }
                }
            }
        }
    }

    fun markStealthIntroSeen() {
        viewModelScope.launch {
            mutationMutex.withLock {
                withContext(ioDispatcher) {
                    preferencesDataSource.setStealthIntroSeen(true)
                    _internalState.update { it.copy(isStealthIntroSeen = true) }
                }
            }
        }
    }

    fun skipVersion(version: String) {
        viewModelScope.launch {
            mutationMutex.withLock {
                withContext(ioDispatcher) {
                    updateRepository.skipVersion(version)
                    _internalState.update { it.copy(availableRelease = null) }
                }
            }
        }
    }

    fun dismissUpdateBanner() {
        _internalState.update { it.copy(availableRelease = null) }
    }

    private suspend fun evaluateCachedRelease() {
        runCatching {
            val cached = updateRepository.getCachedRelease()
            if (cached != null && !updateRepository.isVersionSkipped(cached.versionName)) {
                val eval = com.a0soft.gphone.camonitor.data.update.ReleaseChecker.evaluateRelease(cached, BuildConfig.VERSION_NAME)
                if (eval is ReleaseCheckResult.UpdateAvailable) {
                    _internalState.update { it.copy(availableRelease = cached) }
                } else {
                    updateRepository.clearCachedRelease()
                }
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            AppLogger.w("HomeViewModel", "Error evaluating cached release: ${e.message}")
        }
    }

    private suspend fun triggerNetworkUpdateCheck() {
        if (!updateRepository.shouldCheck()) return

        try {
            updateRepository.recordCheck()
            val result = updateRepository.checkLatestRelease()
            if (result is ReleaseCheckResult.UpdateAvailable) {
                if (!updateRepository.isVersionSkipped(result.release.versionName)) {
                    updateRepository.saveCachedRelease(result.release)
                    _internalState.update { it.copy(availableRelease = result.release) }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.w("HomeViewModel", "Error checking latest release: ${e.message}")
        }
    }

    companion object {
        fun provideFactory(
            securityRepo: SecurityRepository,
            preferencesDataSource: HomePreferencesDataSource,
            telemetryDataSource: TelemetryDataSource,
            updateRepository: UpdateRepository,
            peerCloser: PeerCloser
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    securityRepo = securityRepo,
                    preferencesDataSource = preferencesDataSource,
                    telemetryDataSource = telemetryDataSource,
                    updateRepository = updateRepository,
                    peerCloser = peerCloser
                )
            }
        }
    }
}
