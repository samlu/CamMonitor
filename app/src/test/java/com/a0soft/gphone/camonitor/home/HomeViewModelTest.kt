package com.a0soft.gphone.camonitor.home

import app.cash.turbine.test
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.model.SignalingEnvelope
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.data.update.ReleaseCheckResult
import com.a0soft.gphone.camonitor.data.update.ReleaseInfo
import com.a0soft.gphone.camonitor.ui.common.BatteryInfo
import com.a0soft.gphone.camonitor.ui.home.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.KeyPair

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakePreferences : HomePreferencesDataSource {
        var seen = false
        val history = mutableMapOf<String, Long>()
        var shouldThrowOnClearHistory: Boolean = false
        override fun isStealthIntroSeen(): Boolean = seen
        override fun setStealthIntroSeen(seen: Boolean) { this.seen = seen }
        override fun getLastConnectedMap(): Map<String, Long> = history.toMap()
        override fun clearConnectionHistory(deviceId: String) {
            if (shouldThrowOnClearHistory) throw RuntimeException("Disk write error")
            history.remove(deviceId)
        }
    }

    private class FakeTelemetry : TelemetryDataSource {
        val battery = MutableSharedFlow<BatteryInfo>(replay = 1)
        val network = MutableSharedFlow<NetworkType>(replay = 1)
        override val batteryFlow = battery
        override val networkFlow = network
    }

    private class FakeUpdates : UpdateRepository {
        var cached: ReleaseInfo? = null
        var skipped = mutableSetOf<String>()
        var checkCount = 0
        override suspend fun getCachedRelease() = cached
        override suspend fun saveCachedRelease(release: ReleaseInfo) { cached = release }
        override suspend fun clearCachedRelease() { cached = null }
        override suspend fun isVersionSkipped(version: String) = version in skipped
        override suspend fun skipVersion(version: String) { skipped.add(version) }
        override suspend fun shouldCheck() = false
        override suspend fun recordCheck() { checkCount++ }
        override suspend fun checkLatestRelease(): ReleaseCheckResult = ReleaseCheckResult.UpToDate
    }

    private class FakeSecurity : SecurityRepository {
        var name = "Initial Camera"
        val devices = mutableListOf<PairedDevice>()
        var audioPlaybackEnabled = false
        var shouldThrowOnGetPairedCameras: Boolean = false

        override fun getDeviceId() = "dev-1"
        override fun getDeviceName() = name
        override fun setDeviceName(name: String) { this.name = name }
        override fun getDevicePublicKeyBase64() = "pk"
        override fun getKeyPair(): KeyPair = throw NotImplementedError()
        override fun generatePairingOtp() = "123456"
        override fun setActivePairingOtp(otp: String) {}
        override fun getActivePairingOtp(): String? = null
        override fun clearPairingOtp() {}
        override fun getPairedDevices() = devices.toList()
        override fun getPairedCameras(): List<PairedDevice> {
            if (shouldThrowOnGetPairedCameras) throw RuntimeException("KeyStore read failure")
            return super.getPairedCameras()
        }
        override fun addPairedDevice(device: PairedDevice, maxLimit: Int) = devices.add(device)
        override fun removePairedDevice(deviceId: String) { devices.removeAll { it.deviceId == deviceId } }
        override fun isDeviceWhitelisted(deviceId: String) = devices.any { it.deviceId == deviceId }
        override fun getPairedDevice(deviceId: String) = devices.find { it.deviceId == deviceId }
        override fun createEnvelope(recipientId: String, plaintextJson: String, secretOrDerivedKey: ByteArray): SignalingEnvelope = throw NotImplementedError()
        override fun verifyAndDecryptEnvelope(envelope: SignalingEnvelope, secretOrDerivedKey: ByteArray, isOtpKey: Boolean) = null
        override fun isViewerAudioPlaybackEnabled(): Boolean = audioPlaybackEnabled
        override fun setViewerAudioPlaybackEnabled(enabled: Boolean) { audioPlaybackEnabled = enabled }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_isNotLoaded() = runTest(testDispatcher) {
        val vm = HomeViewModel(FakeSecurity(), FakePreferences(), FakeTelemetry(), FakeUpdates(), PeerCloser {}, testDispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        assertFalse(vm.uiState.value.isLoaded)
    }

    @Test
    fun onActivated_loadsDataAndResolvesCamerasFirstLayout() = runTest(testDispatcher) {
        val security = FakeSecurity().apply {
            devices.add(PairedDevice("cam1", "4F", "pk1", 1000L, "CAMERA"))
        }
        val prefs = FakePreferences().apply { history["cam1"] = 5000L }
        val vm = HomeViewModel(security, prefs, FakeTelemetry(), FakeUpdates(), PeerCloser {}, testDispatcher)

        vm.uiState.test {
            assertEquals(false, awaitItem().isLoaded)

            vm.onActivated()
            testDispatcher.scheduler.advanceUntilIdle()

            val loaded = awaitItem()
            assertTrue(loaded.isLoaded)
            assertEquals("Initial Camera", loaded.cameraName)
            assertEquals(1, loaded.pairedCameras.size)
            assertEquals(0, loaded.pairedViewers.size)
            assertEquals(HomeLayout.CAMERAS_FIRST, loaded.layout)
            assertEquals(5000L, loaded.lastConnectedMap["cam1"])
        }
    }

    @Test
    fun unpairDevice_revokesKey_clearsHistory_andClosesPeer() = runTest(testDispatcher) {
        val security = FakeSecurity().apply {
            devices.add(PairedDevice("cam1", "4F", "pk1", 1000L, "CAMERA"))
        }
        val prefs = FakePreferences().apply { history["cam1"] = 5000L }
        var closedPeerId = ""
        val vm = HomeViewModel(security, prefs, FakeTelemetry(), FakeUpdates(), { closedPeerId = it }, testDispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

        vm.onActivated()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.unpairDevice("cam1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("cam1", closedPeerId)
        assertTrue(security.devices.isEmpty())
        assertFalse("cam1" in prefs.history)
        assertEquals(0, vm.uiState.value.pairedCameras.size)
    }

    @Test
    fun renameCamera_sanitizesAndClampsCodePoints() = runTest(testDispatcher) {
        val security = FakeSecurity()
        val vm = HomeViewModel(security, FakePreferences(), FakeTelemetry(), FakeUpdates(), PeerCloser {}, testDispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

        val input = "😀".repeat(35)
        vm.renameCamera(input)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(32, vm.uiState.value.cameraName.codePoints().count())
        assertEquals(String("😀".repeat(32).codePoints().toArray(), 0, 32), security.name)
    }

    @Test
    fun renameCamera_emptyOrControlCharsOnly_doesNotMutate() = runTest(testDispatcher) {
        val security = FakeSecurity().apply { name = "KeepMe" }
        val vm = HomeViewModel(security, FakePreferences(), FakeTelemetry(), FakeUpdates(), PeerCloser {}, testDispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

        vm.onActivated()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.renameCamera("   \n\t\u0000  ")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("KeepMe", security.name)
        assertEquals("KeepMe", vm.uiState.value.cameraName)
    }

    @Test
    fun skipVersion_recordsSkipAndDismissesBanner() = runTest(testDispatcher) {
        val updates = FakeUpdates()
        val vm = HomeViewModel(FakeSecurity(), FakePreferences(), FakeTelemetry(), updates, PeerCloser {}, testDispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

        vm.skipVersion("v1.5.0")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(updates.isVersionSkipped("v1.5.0"))
        assertNull(vm.uiState.value.availableRelease)
    }

    @Test
    fun markStealthIntroSeen_persistsSeenFlag() = runTest(testDispatcher) {
        val prefs = FakePreferences()
        val vm = HomeViewModel(FakeSecurity(), prefs, FakeTelemetry(), FakeUpdates(), PeerCloser {}, testDispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

        assertFalse(prefs.seen)
        vm.markStealthIntroSeen()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(prefs.seen)
        assertTrue(vm.uiState.value.isStealthIntroSeen)
    }

    @Test
    fun telemetryFlow_updatesUiState() = runTest(testDispatcher) {
        val telemetry = FakeTelemetry()
        val vm = HomeViewModel(FakeSecurity(), FakePreferences(), telemetry, FakeUpdates(), PeerCloser {}, testDispatcher)

        vm.uiState.test {
            // initial
            val item0 = awaitItem()
            assertEquals(-1, item0.batteryInfo.percentage)

            // emit telemetry
            telemetry.battery.emit(BatteryInfo(percentage = 88, isCharging = true))
            telemetry.network.emit(NetworkType.WIFI)
            testDispatcher.scheduler.advanceUntilIdle()

            val updated = awaitItem()
            assertEquals(88, updated.batteryInfo.percentage)
            assertTrue(updated.batteryInfo.isCharging)
            assertEquals(NetworkType.WIFI, updated.networkType)
        }
    }

    @Test
    fun unpairDevice_historyClearFails_stillClosesPeerAndReloadsState() = runTest(testDispatcher) {
        val security = FakeSecurity().apply {
            devices.add(PairedDevice("cam1", "4F", "pk1", 1000L, "CAMERA"))
        }
        val prefs = FakePreferences().apply {
            history["cam1"] = 5000L
            shouldThrowOnClearHistory = true
        }
        var closedPeerId = ""
        val vm = HomeViewModel(security, prefs, FakeTelemetry(), FakeUpdates(), { closedPeerId = it }, testDispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

        vm.onActivated()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.unpairDevice("cam1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("cam1", closedPeerId)
        assertTrue(security.devices.isEmpty())
        assertEquals(0, vm.uiState.value.pairedCameras.size)
    }

    @Test
    fun reloadLocalData_repositoryThrows_stillMarksLoadedAndDoesNotHang() = runTest(testDispatcher) {
        val security = FakeSecurity().apply {
            shouldThrowOnGetPairedCameras = true
        }
        val vm = HomeViewModel(security, FakePreferences(), FakeTelemetry(), FakeUpdates(), PeerCloser {}, testDispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

        vm.onActivated()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.isLoaded)
        assertTrue(vm.uiState.value.pairedCameras.isEmpty())
        assertNotNull(vm.uiState.value.loadError)
    }

    @Test
    fun reloadLocalData_partialReadFailure_preservesLastKnownValuesAndSetsError() = runTest(testDispatcher) {
        val security = FakeSecurity().apply {
            devices.add(PairedDevice("cam1", "4F", "pk1", 1000L, "CAMERA"))
        }
        val vm = HomeViewModel(security, FakePreferences(), FakeTelemetry(), FakeUpdates(), PeerCloser {}, testDispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

        // Initial successful load
        vm.onActivated()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.uiState.value.pairedCameras.size)
        assertNull(vm.uiState.value.loadError)

        // Simulate transient storage failure on subsequent read
        security.shouldThrowOnGetPairedCameras = true
        vm.onActivated()
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify last known cameras are preserved, NOT replaced with emptyList()
        assertEquals(1, vm.uiState.value.pairedCameras.size)
        assertEquals("cam1", vm.uiState.value.pairedCameras.first().deviceId)
        assertNotNull(vm.uiState.value.loadError)
        assertEquals("KeyStore read failure", vm.uiState.value.loadError)

        // Retry and succeed
        security.shouldThrowOnGetPairedCameras = false
        vm.onActivated()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.uiState.value.pairedCameras.size)
        assertNull(vm.uiState.value.loadError)
    }
}
