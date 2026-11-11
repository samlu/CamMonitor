# Architectural Specification & Proposal: HomeViewModel Refactoring & Route/Screen Decoupling

**Document:** `code_review/HomeViewModel_refactor_20261111.md`  
**Date:** 2026-11-11  
**Status:** FINAL SPECIFICATION (ACCEPTED §10 IMPLEMENTATION SPEC)  
**Target Repository:** [`samlu/CamMonitor`](https://github.com/samlu/CamMonitor)  
**Author:** AI Pair Programming Assistant  

---

## 1. Executive Summary & Problem Diagnosis

While the visual design and UX of the Home Screen have been hardened into the Operational Command Center (see [`Specification.md`](../Specification.md) §5.1), the underlying implementation in [`HomeScreen.kt`](../app/src/main/java/com/a0soft/gphone/camonitor/ui/home/HomeScreen.kt) remains coupled to Android lifecycle infrastructure and application singletons.

### 1.1 Core Architectural Deficiencies

1. **Tight Coupling to Singletons & Hardware KeyStore**:
   - [`HomeScreen.kt`](../app/src/main/java/com/a0soft/gphone/camonitor/ui/home/HomeScreen.kt) directly accesses `CamMonitorApp.instance.securityRepository` to query and mutate whitelist records, hardware-backed keys, and device names.
   - This prevents standard Jetpack Compose `@Preview` tooling from rendering and blocks isolated JVM Compose UI tests.
2. **Network I/O in the UI Tree**:
   - `ReleaseChecker.checkLatestRelease()` (HTTP query to GitHub Releases API) executes inside a `LaunchedEffect(Unit)` within the composable.
   - Configuration changes and recomposition cycles unnecessarily interleave network scheduling with UI rendering.
3. **Hardware Telemetry Leakage Beyond UI Visibility**:
   - Battery (`ACTION_BATTERY_CHANGED`) and network (`ConnectivityManager.NetworkCallback`) are registered inside a `DisposableEffect(context)`.
   - When the user navigates forward (e.g. to Viewer Mode or System Logs), `HomeScreen` stays in the navigation backstack; callbacks continue posting state updates despite the UI being invisible.
4. **Scattered Mutations Violating Unidirectional Data Flow (UDF)**:
   - Device renaming, unpairing revocation, update version skipping, and stealth intro flags are written inline within Dialog `onClick` closures instead of flowing through an explicit ViewModel intent pipeline.

---

## 2. Target Architecture (Specification §4.2 Compliance)

```
┌────────────────────────────────────────────────────────┐
│                      MainActivity                      │
└──────────────────────────┬─────────────────────────────┘
                           │ injects ViewModel / routes
                           ▼
┌────────────────────────────────────────────────────────┐
│                       HomeRoute                        │ (Stateful Composable)
│  - Injects HomeViewModel via viewModel()               │
│  - Collects uiState with collectAsStateWithLifecycle() │
│  - Bridges navigation lambdas                          │
└──────────────────────────┬─────────────────────────────┘
                           │ passes HomeUiState & callbacks
                           ▼
┌────────────────────────────────────────────────────────┐
│                       HomeScreen                       │ (Pure Stateless Composable)
│  - Zero Android Context, zero Singletons               │
│  - 100% Previewable (@Preview supported)               │
│  - Renders Consoles, Telemetry Bar, Dialogs            │
└────────────────────────────────────────────────────────┘
```

---

## 3. Concrete Implementation Plan (Draft / Superseded by §10)

> [!NOTE]
> The initial draft in §3 has been refined across several review rounds. Refer directly to [**§10 Final Implementation Specification**](#10-fifth-pass-response--final-implementation-specification-2026-11-11) for the authoritative code assets and architecture.

### 3.1 Data Model: Immutable UI State (`HomeUiState.kt`)

Create `app/src/main/java/com/a0soft/gphone/camonitor/ui/home/HomeUiState.kt`:

```kotlin
package com.a0soft.gphone.camonitor.ui.home

import androidx.compose.runtime.Immutable
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.update.ReleaseInfo
import com.a0soft.gphone.camonitor.ui.common.BatteryInfo

/**
 * Immutable snapshot of all state required to render the Operational Home Command Center.
 */
@Immutable
data class HomeUiState(
    val cameraName: String = "",
    val pairedViewers: List<PairedDevice> = emptyList(),
    val pairedCameras: List<PairedDevice> = emptyList(),
    val batteryInfo: BatteryInfo = BatteryInfo(percentage = -1, isCharging = false),
    val networkType: NetworkType = NetworkType.DISCONNECTED,
    val availableRelease: ReleaseInfo? = null,
    val isStealthIntroSeen: Boolean = false,
    val layout: HomeLayout = HomeLayout.BROADCASTER_FIRST
)
```

---

### 3.2 ViewModel Implementation (`HomeViewModel.kt`)

Create `app/src/main/java/com/a0soft/gphone/camonitor/ui/home/HomeViewModel.kt`:

```kotlin
package com.a0soft.gphone.camonitor.ui.home

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.a0soft.gphone.camonitor.BuildConfig
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.data.update.ReleaseCheckResult
import com.a0soft.gphone.camonitor.data.update.ReleaseChecker
import com.a0soft.gphone.camonitor.data.update.UpdatePreferenceManager
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.ui.common.BatteryHelper
import com.a0soft.gphone.camonitor.ui.common.UpdateNotificationHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HomeViewModel(
    application: Application,
    private val securityRepo: SecurityRepository,
    private val updatePrefs: UpdatePreferenceManager = UpdatePreferenceManager(application),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {

    private val uiPrefs = application.getSharedPreferences("cammonitor_ui_prefs", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    companion object {
        private const val KEY_STEALTH_INTRO_SEEN = "stealth_intro_seen"
    }

    init {
        UpdateNotificationHelper.dismissUpdateNotification(application)
        loadLocalDevices()
        observeTelemetry()
        checkForUpdates()
    }

    /**
     * Called on Activity ON_RESUME to reload paired devices and name in case
     * pairing or settings changed in another screen.
     */
    fun onResume() {
        loadLocalDevices()
    }

    private fun loadLocalDevices() {
        val viewers = securityRepo.getPairedViewers()
        val cameras = securityRepo.getPairedCameras()
        val name = securityRepo.getDeviceName()
        val seen = uiPrefs.getBoolean(KEY_STEALTH_INTRO_SEEN, false)
        _uiState.update { current ->
            current.copy(
                cameraName = name,
                pairedViewers = viewers,
                pairedCameras = cameras,
                isStealthIntroSeen = seen,
                layout = homeLayoutFor(cameras.size, viewers.size)
            )
        }
    }

    private fun observeTelemetry() {
        val batteryFlow = callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    trySend(BatteryHelper.getBatteryInfo(getApplication()))
                }
            }
            ContextCompat.registerReceiver(
                getApplication(),
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            trySend(BatteryHelper.getBatteryInfo(getApplication()))
            awaitClose {
                try {
                    getApplication<Application>().unregisterReceiver(receiver)
                } catch (_: Exception) {}
            }
        }

        val networkFlow = callbackFlow {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null) {
                trySend(NetworkType.DISCONNECTED)
                awaitClose {}
                return@callbackFlow
            }

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(getNetworkStatus(getApplication()))
                }
                override fun onLost(network: Network) {
                    trySend(getNetworkStatus(getApplication()))
                }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    trySend(getNetworkStatus(getApplication()))
                }
            }
            try {
                cm.registerDefaultNetworkCallback(callback)
                trySend(getNetworkStatus(getApplication()))
            } catch (e: Exception) {
                AppLogger.w("HomeViewModel", "Failed to register network callback: ${e.message}")
                trySend(NetworkType.DISCONNECTED)
            }
            awaitClose {
                try {
                    cm.unregisterNetworkCallback(callback)
                } catch (_: Exception) {}
            }
        }

        viewModelScope.launch {
            combine(batteryFlow, networkFlow) { bat, net -> Pair(bat, net) }
                .collect { (bat, net) ->
                    _uiState.update { it.copy(batteryInfo = bat, networkType = net) }
                }
        }
    }

    private fun checkForUpdates() {
        viewModelScope.launch(ioDispatcher) {
            val cached = updatePrefs.getCachedRelease()
            if (cached != null && !updatePrefs.isVersionSkipped(cached.versionName)) {
                val eval = ReleaseChecker.evaluateRelease(cached, BuildConfig.VERSION_NAME)
                if (eval is ReleaseCheckResult.UpdateAvailable) {
                    _uiState.update { it.copy(availableRelease = cached) }
                } else {
                    updatePrefs.clearCachedRelease()
                }
            }

            if (updatePrefs.shouldCheck()) {
                val result = ReleaseChecker.checkLatestRelease()
                updatePrefs.recordCheck()
                if (result is ReleaseCheckResult.UpdateAvailable) {
                    if (!updatePrefs.isVersionSkipped(result.release.versionName)) {
                        updatePrefs.saveCachedRelease(result.release)
                        _uiState.update { it.copy(availableRelease = result.release) }
                    }
                }
            }
        }
    }

    fun renameCamera(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) {
            securityRepo.setDeviceName(trimmed)
            _uiState.update { it.copy(cameraName = trimmed) }
        }
    }

    fun unpairCamera(deviceId: String, closePeer: (String) -> Unit) {
        securityRepo.removePairedDevice(deviceId)
        closePeer(deviceId)
        loadLocalDevices()
    }

    fun markStealthIntroSeen() {
        uiPrefs.edit().putBoolean(KEY_STEALTH_INTRO_SEEN, true).apply()
        _uiState.update { it.copy(isStealthIntroSeen = true) }
    }

    fun skipVersion(version: String) {
        updatePrefs.skipVersion(version)
        _uiState.update { it.copy(availableRelease = null) }
    }

    fun dismissUpdateBanner() {
        _uiState.update { it.copy(availableRelease = null) }
    }
}
```

---

### 3.3 Route & Screen Decoupling (`HomeScreen.kt`)

Refactor `HomeScreen.kt` into two layers:

#### Stateful `HomeRoute`
```kotlin
@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onStartCameraMode: (startInStealth: Boolean) -> Unit,
    onPairNewViewer: () -> Unit,
    onStartViewerMode: (deviceId: String) -> Unit,
    onPairNewCamera: () -> Unit,
    onOpenPairedDevices: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    HomeScreen(
        uiState = uiState,
        onRenameCamera = viewModel::renameCamera,
        onUnpairCamera = { deviceId ->
            viewModel.unpairCamera(deviceId) { id ->
                CamMonitorApp.instance.webRtcManager.closePeer(id)
            }
        },
        onMarkStealthIntroSeen = viewModel::markStealthIntroSeen,
        onSkipVersion = viewModel::skipVersion,
        onDismissUpdate = viewModel::dismissUpdateBanner,
        onStartCameraMode = onStartCameraMode,
        onPairNewViewer = onPairNewViewer,
        onStartViewerMode = onStartViewerMode,
        onPairNewCamera = onPairNewCamera,
        onOpenPairedDevices = onOpenPairedDevices,
        onOpenLogs = onOpenLogs,
        onOpenAbout = onOpenAbout
    )
}
```

#### Pure Stateless `HomeScreen`
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onRenameCamera: (String) -> Unit,
    onUnpairCamera: (String) -> Unit,
    onMarkStealthIntroSeen: () -> Unit,
    onSkipVersion: (String) -> Unit,
    onDismissUpdate: () -> Unit,
    onStartCameraMode: (startInStealth: Boolean) -> Unit,
    onPairNewViewer: () -> Unit,
    onStartViewerMode: (deviceId: String) -> Unit,
    onPairNewCamera: () -> Unit,
    onOpenPairedDevices: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit
) {
    // Zero context, zero KeyStore calls, zero Network calls.
    // Pure rendering driven entirely by uiState.
}
```

---

### 3.4 Call Site in `MainActivity.kt`

In [`MainActivity.kt`](../app/src/main/java/com/a0soft/gphone/camonitor/MainActivity.kt):

```kotlin
// Instantiate or remember HomeViewModel with custom Factory providing application & securityRepo
val homeViewModel: HomeViewModel = viewModel(
    factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(
                application = application,
                securityRepo = securityRepo
            ) as T
        }
    }
)

when (currentScreen) {
    AppScreen.HOME -> {
        HomeRoute(
            viewModel = homeViewModel,
            onStartCameraMode = { stealth -> ... },
            ...
        )
    }
    ...
}
```

---

## 4. Testing & Verification Strategy

### 4.1 JVM Unit Tests (`HomeViewModelTest.kt`)
Create `app/src/test/java/com/a0soft/gphone/camonitor/home/HomeViewModelTest.kt`:
1. **Initial State & Layout Test**:
   - Verify that when `securityRepo.getPairedCameras()` has entries and `getPairedViewers()` is empty, `uiState.value.layout` initializes to `HomeLayout.CAMERAS_FIRST`.
2. **Rename Mutation**:
   - Calling `renameCamera("Front Garden")` invokes `securityRepo.setDeviceName("Front Garden")` and updates `uiState.value.cameraName`.
3. **Unpair Flow**:
   - Calling `unpairCamera(id, closePeer)` removes the record from `securityRepo`, calls `closePeer(id)`, and refreshes `uiState`.
4. **Update Banner Dismissal & Skip**:
   - Calling `skipVersion("v1.2.0")` records skip in prefs and sets `availableRelease = null`.

### 4.2 Regression Checks
- Run `.\gradlew.bat testDebugUnitTest` to guarantee all 167+ existing tests continue passing.
- Run `.\gradlew.bat assembleDebug` to ensure compilation and R8/ProGuard rules pass.

---

## 5. Implementation Checklist

- [ ] Create `HomeUiState.kt` under `app/src/main/java/com/a0soft/gphone/camonitor/ui/home/`
- [ ] Create `HomeViewModel.kt` under `app/src/main/java/com/a0soft/gphone/camonitor/ui/home/`
- [ ] Refactor `HomeScreen.kt` to export `HomeRoute` and stateless `HomeScreen`
- [ ] Update `MainActivity.kt` to inject and wire `HomeRoute`
- [ ] Implement `HomeViewModelTest.kt` under `app/src/test/java/com/a0soft/gphone/camonitor/home/`
- [ ] Update [`Specification.md`](../Specification.md) §4.2 and §5.1 to reflect `HomeViewModel` architecture
- [ ] Verify test suite passes (`.\gradlew.bat testDebugUnitTest`)

---

## 6. Review Comments (2026-11-11)

1. **High — Telemetry collection outlives the Home screen.** `HomeViewModel.init` starts `observeTelemetry()` and collects both callback flows in `viewModelScope` (proposal lines 122–123, 152–210). With the proposed Activity-scoped ViewModel, the battery receiver and network callback can stay registered while another screen is active. In the current `MainActivity`, only the selected screen is composed, so Home's existing `DisposableEffect` is disposed on navigation. Tie telemetry collection to Home visibility, for example with lifecycle-aware collection and a `WhileSubscribed` sharing policy.

2. **High — `getNetworkStatus()` is unresolved from the new ViewModel.** The proposed ViewModel calls it at lines 185–196, but the existing function is private to `HomeScreen.kt`. Move the implementation to an accessible helper or add an appropriate implementation in the ViewModel before this plan can compile.

3. **High — Returning to Home may not refresh paired-device state.** `HomeRoute` reloads state only on a lifecycle `ON_RESUME` event (lines 291–298). Internal navigation in this app does not pause/resume the Activity, so returning to Home with a retained ViewModel may not emit that event. Refresh on Home route activation or explicitly when navigating back.

4. **Medium — Rename does not enforce the documented name constraints.** `renameCamera()` trims and rejects empty input, while `SecurityRepository.setDeviceName()` also only trims. The specification requires a 32-character limit and sanitization. Enforce those constraints in the ViewModel intent path as well as in the text field.

5. **Medium — The stateless screen interface does not cover all existing actions.** The current Home screen opens release URLs and changes the app language using Android context and locale helpers. The proposed callback lists do not expose those actions, so the stated context-free `HomeScreen` boundary is incomplete. Add callbacks for those actions (and handle their Android-specific work in the route/host).

6. **Correction to the problem diagnosis:** The claim that Home remains composed on the navigation backstack does not match the current `MainActivity`; its `when (currentScreen)` composes only the selected screen. The current Home telemetry effects are therefore disposed when navigating away. The proposed ViewModel-owned collection would create the longer-lived callback issue described in finding 1 unless it is explicitly scoped to Home visibility.

---

## 7. Additional Review Comments (second pass, 2026-11-11)

Findings 1-6 in §6 stand; I verified them against `HomeScreen.kt` (private `getNetworkStatus` at L64, `ON_RESUME` observer at L171, telemetry `DisposableEffect` at L134) and `MainActivity.kt` (`when (currentScreen)` at L273). The items below are new.

1. **High - Blocking storage I/O on the main thread.** `loadLocalDevices()` runs in `init` and from `onResume()`. It calls `securityRepo.getPairedViewers()`, `getPairedCameras()` and `getDeviceName()`, and reads `uiPrefs`. If `SecurityRepository` is backed by EncryptedSharedPreferences/KeyStore (it is hardware-backed per §1.1), these calls can take tens of ms and sometimes much longer on first access. `renameCamera`, `unpairCamera` and `markStealthIntroSeen` do synchronous writes on `viewModelScope`'s `Main.immediate`. Move all of them to `ioDispatcher` with `withContext`, then publish the result via `_uiState.update`.

2. **High - The "JVM unit tests" in §4.1 can't run as written.** `AndroidViewModel(Application)` plus `getSharedPreferences` and `BatteryHelper`/`ConnectivityManager` need an Android runtime. A plain JVM test will throw `Method ... not mocked`. Either add Robolectric (new dependency) or, better, hide the Android pieces behind small interfaces (`TelemetrySource: Flow<BatteryInfo>/Flow<NetworkType>`, `UiPrefs`, `UpdateRepository`, `PeerCloser`) and make `HomeViewModel` a plain `ViewModel`. That also fixes finding 2 in §6 and the Preview goal. Tests also need `Dispatchers.setMain(...)` because `viewModelScope` uses `Dispatchers.Main.immediate`. Add `kotlinx-coroutines-test` and inject the dispatcher consistently.

3. **Medium - `unpairCamera(deviceId, closePeer: (String) -> Unit)` leaks a UI-layer concern into the ViewModel API.** The caller has to pass a `WebRtcManager` lambda every time. Inject a `PeerCloser` (or the manager) via the constructor. The name is also misleading: the same path presumably unpairs viewers (`pairedViewers`). Use `unpairDevice`, and decide whether `removePairedDevice` + `closePeer` should be ordered the other way round, so no live peer outlives its revoked key.

4. **Medium - Behavior change in update and notification side effects.** Today the update check, the cached-release evaluation and `dismissUpdateNotification` run on every Home entry (`LaunchedEffect(Unit)`). In the proposal they run once in `init`, i.e. once per Activity lifetime. Returning from Settings or About will never re-evaluate the cached release, so a banner can linger after the user updates. It can also stay dismissed or stale if the notification is re-posted while the Activity is alive. Re-run the cached-release part (cheap, no network) from the same "Home activated" trigger as the device reload. Keep the network call behind `shouldCheck()`.

5. **Medium - Unhandled exceptions in `checkForUpdates()`.** `ReleaseChecker.checkLatestRelease()` and the prefs calls are not wrapped in try/catch. An uncaught exception inside `viewModelScope.launch` crashes the app (there is no handler). The current composable presumably tolerated this inside `LaunchedEffect` only if `checkLatestRelease()` itself swallows errors. Either confirm it returns an error result and never throws, or wrap the block and log with `AppLogger.w`. Also rethrow `CancellationException`.

6. **Low - Telemetry flows emit redundantly.** `trySend` fires on every `onCapabilitiesChanged` (signal strength changes fire very often) and on every battery tick. Add `.distinctUntilChanged()` on both flows (and on the combined result) so `_uiState` and recomposition don't churn. Also `combine` emits nothing until both flows have a first value; that's fine today, but add `.onStart { emit(default) }` or an initial value so the UI doesn't depend on that. `catch (_: Exception) {}` in `awaitClose` should at least log at DEBUG (respecting `AppLogger.minLevel`).

7. **Low - Use `viewModelFactory { initializer { ... } }` instead of an anonymous `ViewModelProvider.Factory` with an unchecked cast.** Move the factory out of the `when` branch in `MainActivity` (it's created inline during composition) into `HomeViewModel.Factory` or a companion property.

8. **Low - `HomeUiState` has unused or loosely typed fields.** `pairedViewers` and `pairedCameras` are `List<PairedDevice>`, and `layout` is derivable from the two list sizes. Storing it separately lets it get out of sync with the lists. Expose it as a computed property (`val layout get() = homeLayoutFor(...)`). `@Immutable` on a class holding `List` is a promise the compiler can't check. Prefer `ImmutableList` or `@Stable` plus the Compose compiler's stability config.

9. **Process.** §5 checklist is missing the README sync required by AGENTS.md only if user-visible behavior changes, so it can stay out. But "167+ existing tests" conflicts with the "97+" figure in AGENTS.md. Re-run `.\gradlew.bat testDebugUnitTest` for the real baseline and record the actual count in the PR description. Also add a `LocaleResourcesTest` check to the plan if any string changes are made while moving dialogs.

### Recommendation
Do not implement as written. Fix §6 items 1-3 and §7 items 1-2 in the design first (plain `ViewModel` with injected interfaces, visibility-scoped telemetry, a "Home activated" refresh trigger, IO dispatching). The rest can be addressed during implementation.


---

## 8. Third-Pass Architectural Audit (Superseded by §10)

> [!NOTE]
> Addressed in the fourth-pass review (§9) and fully resolved in [**§10 Final Implementation Specification**](#10-fifth-pass-response--final-implementation-specification-2026-11-11).

Building upon the initial review (§6) and second pass (§7), this audit addresses regressions introduced by recent features (specifically Option 2: Dynamic Last Connected Status in commit `08701be`), resolves the JVM unit testing dilemma without introducing Robolectric, and establishes the definitive, production-ready implementation blueprint.

### 8.1 Key Findings & Defect Analysis

1. **Option 2 Feature Regression (`lastConnectedMap` & `CameraConnectionHistory`)**:
   - Commit `08701be` replaced static pair dates on camera tiles with relative last-connected timestamps backed by `CameraConnectionHistory` in `cammonitor_ui_prefs`.
   - The proposed `HomeUiState` in §3.1 completely omitted `lastConnectedMap: Map<String, Long>`. If implemented as proposed, `MonitoredCamerasSection` and `DirectCameraTile` would lose connection timestamps or fail to compile.
   - In `HomeScreen.kt` line 408, unpairing a camera explicitly calls `CameraConnectionHistory.clear(context, targetCamera.deviceId)`. In §3.2, `unpairCamera()` only called `securityRepo.removePairedDevice(deviceId)`, leaking orphaned connection timestamps in persistent storage.
   - **Fix:** Include `val lastConnectedMap: Map<String, Long> = emptyMap()` in `HomeUiState`, inject a connection history interface into the ViewModel, and ensure `unpairDevice()` purges history.

2. **Stateless `HomeScreen` Leaks Android Context via Dialogs**:
   - The proposal claims `HomeScreen` will have *"Zero Android Context, zero Singletons, 100% Previewable"*.
   - However, `LanguageDialog` calls `LocaleHelper.getCurrentLanguageTag(context)` and `LocaleHelper.setLanguage(context, tag)`.
   - Furthermore, `UpdateBannerCard` calls `context.startActivity(Intent(ACTION_VIEW, ...))`.
   - If `HomeScreen` is to be 100% context-free and previewable, it must not execute these operations internally.
   - **Fix:** Pass `currentLanguageTag: String`, `onOpenReleaseUrl: (String) -> Unit`, and `onChangeLanguage: (String) -> Unit` into `HomeScreen`. Handle the `Context`-dependent operations (activity launching, configuration/locale updates) entirely within `HomeRoute`.

3. **Plain JVM Testability vs. Robolectric Overhead**:
   - CamMonitor's `app/build.gradle.kts` does **not** include Robolectric; its unit tests are fast JVM tests utilizing MockK, JUnit 4, and Turbine.
   - Extending `AndroidViewModel(Application)` and referencing `getSharedPreferences`, `BroadcastReceiver`, or `ConnectivityManager` inside `init` or methods causes JVM tests to throw `RuntimeException: Method ... not mocked`.
   - **Fix:** Decouple `HomeViewModel` from `Application` and Android platform SDK classes by introducing lean domain interfaces:
     - `HomePreferencesDataSource`: handles `isStealthIntroSeen()`, `setStealthIntroSeen()`, `getLastConnectedMap()`, and `clearConnectionHistory()`.
     - `TelemetryDataSource`: exposes `batteryFlow: Flow<BatteryInfo>` and `networkFlow: Flow<NetworkType>`.
     - `PeerCloser`: functional interface for closing WebRTC peer connections on unpair.
   - `HomeViewModel` becomes a pure `ViewModel` (inheriting from `androidx.lifecycle.ViewModel`). In JVM tests, these interfaces are mocked effortlessly with MockK without requiring an Android emulator or Robolectric.

4. **Lifecycle-Safe Telemetry via `WhileSubscribed(5_000)`**:
   - Because `MainActivity` uses a manual Compose router (`when (currentScreen)`), navigating to Viewer or Camera mode unmounts `HomeRoute` from the composition tree, but retains the Activity-scoped `HomeViewModel`.
   - Instead of manually tracking `ON_RESUME`/`ON_PAUSE` or registering callbacks in ViewModel `init`, expose telemetry via a shared `StateFlow` configured with `SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)`.
   - When `HomeRoute` is composed, `collectAsStateWithLifecycle()` subscribes to the flow, triggering callback registration.
   - When the user navigates away, the subscriber count drops to 0. After 5 seconds, the upstream flow cancels, and `awaitClose` immediately unregisters the `BroadcastReceiver` and `NetworkCallback`.

5. **Home Activation Trigger (`onActivated`)**:
   - Internal backstack navigation does not trigger Activity `ON_RESUME`.
   - In `HomeRoute`, invoke `viewModel.onActivated()` within `LaunchedEffect(Unit)`.
   - `onActivated()` asynchronously queries `SecurityRepository` and `HomePreferencesDataSource` on `Dispatchers.IO`, re-evaluates cached update releases, and updates `_uiState` cleanly without main-thread blocking.

---

### 8.2 Definitive Component Architecture

#### 1. Interfaces & Data Models

`app/src/main/java/com/a0soft/gphone/camonitor/ui/home/HomeUiState.kt`:
```kotlin
package com.a0soft.gphone.camonitor.ui.home

import androidx.compose.runtime.Immutable
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.update.ReleaseInfo
import com.a0soft.gphone.camonitor.ui.common.BatteryInfo

@Immutable
data class HomeUiState(
    val cameraName: String = "",
    val pairedViewers: List<PairedDevice> = emptyList(),
    val pairedCameras: List<PairedDevice> = emptyList(),
    val lastConnectedMap: Map<String, Long> = emptyMap(),
    val batteryInfo: BatteryInfo = BatteryInfo(percentage = -1, isCharging = false),
    val networkType: NetworkType = NetworkType.DISCONNECTED,
    val availableRelease: ReleaseInfo? = null,
    val isStealthIntroSeen: Boolean = false,
    val currentLanguageTag: String = ""
) {
    val layout: HomeLayout
        get() = homeLayoutFor(camerasCount = pairedCameras.size, viewersCount = pairedViewers.size)
}
```

`app/src/main/java/com/a0soft/gphone/camonitor/ui/home/HomeDataSources.kt`:
```kotlin
package com.a0soft.gphone.camonitor.ui.home

import com.a0soft.gphone.camonitor.ui.common.BatteryInfo
import kotlinx.coroutines.flow.Flow

interface HomePreferencesDataSource {
    fun isStealthIntroSeen(): Boolean
    fun setStealthIntroSeen(seen: Boolean)
    fun getLastConnectedMap(): Map<String, Long>
    fun clearConnectionHistory(deviceId: String)
    fun getCurrentLanguageTag(): String
}

interface TelemetryDataSource {
    val batteryFlow: Flow<BatteryInfo>
    val networkFlow: Flow<NetworkType>
}

fun interface PeerCloser {
    fun closePeer(deviceId: String)
}
```

#### 2. Pure `HomeViewModel` (100% JVM Testable)

`app/src/main/java/com/a0soft/gphone/camonitor/ui/home/HomeViewModel.kt`:
```kotlin
package com.a0soft.gphone.camonitor.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.a0soft.gphone.camonitor.BuildConfig
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.data.update.ReleaseCheckResult
import com.a0soft.gphone.camonitor.data.update.ReleaseChecker
import com.a0soft.gphone.camonitor.data.update.UpdatePreferenceManager
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.ui.common.BatteryInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(
    private val securityRepo: SecurityRepository,
    private val preferencesDataSource: HomePreferencesDataSource,
    private val telemetryDataSource: TelemetryDataSource,
    private val updatePrefs: UpdatePreferenceManager,
    private val peerCloser: PeerCloser,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // Collect telemetry with WhileSubscribed(5_000): hardware receivers only active when Home is visible.
        combine(
            telemetryDataSource.batteryFlow.distinctUntilChanged(),
            telemetryDataSource.networkFlow.distinctUntilChanged()
        ) { battery, network -> battery to network }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = BatteryInfo(-1, false) to NetworkType.DISCONNECTED
            )
            .onEach { (battery, network) ->
                _uiState.update { it.copy(batteryInfo = battery, networkType = network) }
            }
            .launchIn(viewModelScope)
    }

    fun onActivated() {
        viewModelScope.launch(ioDispatcher) {
            reloadDevicesAndPrefs()
            evaluateCachedRelease()
            checkForNetworkUpdates()
        }
    }

    private suspend fun reloadDevicesAndPrefs() = withContext(ioDispatcher) {
        val viewers = securityRepo.getPairedViewers()
        val cameras = securityRepo.getPairedCameras()
        val name = securityRepo.getDeviceName()
        val seen = preferencesDataSource.isStealthIntroSeen()
        val lastConnected = preferencesDataSource.getLastConnectedMap()
        val lang = preferencesDataSource.getCurrentLanguageTag()

        _uiState.update { current ->
            current.copy(
                cameraName = name,
                pairedViewers = viewers,
                pairedCameras = cameras,
                lastConnectedMap = lastConnected,
                isStealthIntroSeen = seen,
                currentLanguageTag = lang
            )
        }
    }

    fun renameCamera(newName: String) {
        val sanitized = newName.trim().take(32)
        if (sanitized.isNotEmpty()) {
            viewModelScope.launch(ioDispatcher) {
                securityRepo.setDeviceName(sanitized)
                _uiState.update { it.copy(cameraName = sanitized) }
            }
        }
    }

    fun unpairDevice(deviceId: String) {
        viewModelScope.launch(ioDispatcher) {
            peerCloser.closePeer(deviceId)
            securityRepo.removePairedDevice(deviceId)
            preferencesDataSource.clearConnectionHistory(deviceId)
            reloadDevicesAndPrefs()
        }
    }

    fun markStealthIntroSeen() {
        viewModelScope.launch(ioDispatcher) {
            preferencesDataSource.setStealthIntroSeen(true)
            _uiState.update { it.copy(isStealthIntroSeen = true) }
        }
    }

    fun skipVersion(version: String) {
        viewModelScope.launch(ioDispatcher) {
            updatePrefs.skipVersion(version)
            _uiState.update { it.copy(availableRelease = null) }
        }
    }

    fun dismissUpdateBanner() {
        _uiState.update { it.copy(availableRelease = null) }
    }

    private suspend fun evaluateCachedRelease() = withContext(ioDispatcher) {
        try {
            val cached = updatePrefs.getCachedRelease()
            if (cached != null && !updatePrefs.isVersionSkipped(cached.versionName)) {
                val eval = ReleaseChecker.evaluateRelease(cached, BuildConfig.VERSION_NAME)
                if (eval is ReleaseCheckResult.UpdateAvailable) {
                    _uiState.update { it.copy(availableRelease = cached) }
                } else {
                    updatePrefs.clearCachedRelease()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.w("HomeViewModel", "Error evaluating cached release: ${e.message}")
        }
    }

    private suspend fun checkForNetworkUpdates() = withContext(ioDispatcher) {
        try {
            if (updatePrefs.shouldCheck()) {
                val result = ReleaseChecker.checkLatestRelease()
                updatePrefs.recordCheck()
                if (result is ReleaseCheckResult.UpdateAvailable) {
                    if (!updatePrefs.isVersionSkipped(result.release.versionName)) {
                        updatePrefs.saveCachedRelease(result.release)
                        _uiState.update { it.copy(availableRelease = result.release) }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.w("HomeViewModel", "Error checking latest release: ${e.message}")
        }
    }
}
```

#### 3. Decoupled Route & Stateless Screen Boundary

`app/src/main/java/com/a0soft/gphone/camonitor/ui/home/HomeScreen.kt`:
```kotlin
@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onStartCameraMode: (startInStealth: Boolean) -> Unit,
    onPairNewViewer: () -> Unit,
    onStartViewerMode: (deviceId: String) -> Unit,
    onPairNewCamera: () -> Unit,
    onOpenPairedDevices: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Refresh state whenever Home is activated or navigated back to
    LaunchedEffect(Unit) {
        UpdateNotificationHelper.dismissUpdateNotification(context)
        viewModel.onActivated()
    }

    HomeScreen(
        uiState = uiState,
        onRenameCamera = viewModel::renameCamera,
        onUnpairCamera = viewModel::unpairDevice,
        onMarkStealthIntroSeen = viewModel::markStealthIntroSeen,
        onSkipVersion = viewModel::skipVersion,
        onDismissUpdate = viewModel::dismissUpdateBanner,
        onOpenReleaseUrl = { url ->
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                AppLogger.w("HomeRoute", "Failed to open release URL: ${e.message}")
            }
        },
        onChangeLanguage = { tag ->
            LocaleHelper.setLanguage(context, tag)
            viewModel.onActivated()
        },
        onStartCameraMode = onStartCameraMode,
        onPairNewViewer = onPairNewViewer,
        onStartViewerMode = onStartViewerMode,
        onPairNewCamera = onPairNewCamera,
        onOpenPairedDevices = onOpenPairedDevices,
        onOpenLogs = onOpenLogs,
        onOpenAbout = onOpenAbout
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onRenameCamera: (String) -> Unit,
    onUnpairCamera: (String) -> Unit,
    onMarkStealthIntroSeen: () -> Unit,
    onSkipVersion: (String) -> Unit,
    onDismissUpdate: () -> Unit,
    onOpenReleaseUrl: (String) -> Unit,
    onChangeLanguage: (String) -> Unit,
    onStartCameraMode: (startInStealth: Boolean) -> Unit,
    onPairNewViewer: () -> Unit,
    onStartViewerMode: (deviceId: String) -> Unit,
    onPairNewCamera: () -> Unit,
    onOpenPairedDevices: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit
) {
    // Pure Compose: Zero Context, Zero Singletons, Zero IO.
    // Previewable with Mocked HomeUiState!
}
```

---

### 8.4 Revised Verification Plan

1. **Pure JVM Unit Tests**:
   - `HomeViewModelTest` verifies state transitions using MockK and Turbine without Robolectric.
   - Verifies `layout` dynamically resolves to `CAMERAS_FIRST` when `pairedCameras > 0 && pairedViewers == 0`.
   - Verifies `unpairDevice` calls `peerCloser.closePeer`, `securityRepo.removePairedDevice`, and `preferencesDataSource.clearConnectionHistory`.
   - Verifies `renameCamera` trims and truncates to 32 characters.
2. **Regression & Locale Parity**:
   - Run `.\gradlew.bat testDebugUnitTest` ensuring all 174 tests pass.
   - Run `.\gradlew.bat assembleDebug` to verify compilation.


---

## 9. Fourth-Pass Review of §8 (2026-11-11)

Checked §8 against `HomeScreen.kt`, `LocaleHelper.kt`, `WebRtcManager.kt` and `app/build.gradle.kts`. The direction is right: pure `ViewModel`, injected data sources, `lastConnectedMap` restored and a language/URL boundary. §8 still has one critical bug and several factual and completeness gaps.

### 9.1 Critical

1. **The `WhileSubscribed` telemetry fix does not work (§8.2, `HomeViewModel.init`).** The code is `combine(...).stateIn(viewModelScope, WhileSubscribed(5_000), ...).onEach { ... }.launchIn(viewModelScope)`. `launchIn(viewModelScope)` is itself a permanent subscriber of the `stateIn` flow, so the subscriber count never reaches 0 while the ViewModel is alive. The battery receiver and `NetworkCallback` stay registered after Home is left. That is the exact leak §6.1 and §8.1.4 set out to fix.
   - Fix: don't collect it in the ViewModel. Expose `val telemetry: StateFlow<Telemetry>` (`combine(...).stateIn(viewModelScope, WhileSubscribed(5_000), Telemetry.Initial)`) and have `HomeRoute` collect it with `collectAsStateWithLifecycle()`. Pass the merged `HomeUiState` into `HomeScreen`, or derive `uiState` itself with `combine(_state, telemetry).stateIn(WhileSubscribed(5_000))`.
   - Add a Turbine test: cancel the collector, advance virtual time past 5s, assert the `awaitClose` unregister ran (fake `TelemetryDataSource` counting registrations).

### 9.2 High

2. **§8.1.3 and §8.4 say the tests use MockK. MockK is not a test dependency.** `app/build.gradle.kts` L180-182 has only `junit`, `kotlinx-coroutines-test` and `cash.turbine` (plus `org.json`). Either add MockK explicitly to the checklist and version catalog, or (preferred, and consistent with "lean interfaces") use hand-written fakes for `HomePreferencesDataSource`, `TelemetryDataSource` and `PeerCloser`. This also forces the next point.

3. **`SecurityRepository`, `UpdatePreferenceManager` and the `ReleaseChecker` object are still concrete Android/network types in the constructor.** The "100% JVM testable" claim therefore fails.
   - `UpdatePreferenceManager` is built from a `Context`, so in a JVM test it needs a mocking library or an interface.
   - `ReleaseChecker.checkLatestRelease()` is a static `object` call. `onActivated()` will hit the real GitHub API in any test unless it is abstracted.
   - Introduce `UpdateRepository` (`cachedRelease()`, `isSkipped()`, `skip()`, `shouldCheck()`, `checkLatest()`, `clearCache()`). Do the same for `SecurityRepository`, or confirm it already has an interface.
   - Only §8.4 mentions tests. Add the `setMain` dispatcher rule (`Dispatchers.setMain(UnconfinedTestDispatcher())`) because `viewModelScope` needs it. Pass the test dispatcher as `ioDispatcher`.

4. **Async load introduces a first-frame regression.** Today the screen reads state synchronously via `remember`. In §8, `HomeUiState()` defaults are `isStealthIntroSeen = false`, `layout = f(0 cameras, 0 viewers)` and `cameraName = ""`. They are rendered until `onActivated()` finishes on IO.
   - The stealth intro dialog may flash on every Home entry for users who already dismissed it.
   - The layout will jump (broadcaster-first/empty, then cameras-first) and the camera name will pop in.
   - Fix: add `val isLoaded: Boolean = false`. Gate dialogs on `isLoaded`, and render nothing or a skeleton for the console area until loaded. Because the ViewModel is Activity-scoped, only the first entry pays this cost.
   - Add a test that `isStealthIntroSeen = true` never produces `showStealthIntro` before load.

5. **Language flow is incomplete (§8.1.2).** `HomeScreen.kt` also calls `LocaleHelper.openSystemLanguageSettings(context)` (L483). The proposed `HomeScreen` signature has no callback for it. `onChangeLanguage` also reads state back with `viewModel.onActivated()` straight after `LocaleHelper.setLanguage(...)`.
   - On API 33+ `setLanguage` writes `LocaleManager.applicationLocales`, which recreates the Activity. The recreated `HomeRoute` runs `LaunchedEffect(Unit)` again, so `onActivated()` runs twice (see item 6).
   - Add `onOpenSystemLanguageSettings`. Drop the explicit `onActivated()` call in `onChangeLanguage` if recreation is guaranteed, or make `currentLanguageTag` derive from the route (`LocaleHelper.getCurrentLanguageTag(context)` in `HomeRoute`) instead of from the ViewModel. That also removes `getCurrentLanguageTag()` from `HomePreferencesDataSource`, which is not a preference of Home.

6. **`onActivated()` is not idempotent or serialized.** Each call launches independently, so a configuration change, a language change and a back-navigation can run overlapping `checkForNetworkUpdates()` calls. Two concurrent calls can both pass `shouldCheck()` before either calls `recordCheck()`, which means duplicate GitHub requests. Hold a `private var activationJob: Job?` (cancel and relaunch for the local reload, but guard the network check with a `Mutex` or `if (updateJob?.isActive == true) return`). Run `recordCheck()` before the request or inside the same critical section.

7. **`unpairDevice` ordering and threading.**
   - It calls `peerCloser.closePeer()` before `securityRepo.removePairedDevice()`. Between the two, the peer can reconnect through the whitelist and re-establish the stream you just closed. Remove the pairing first, then close the peer.
   - `closePeer` now runs on `Dispatchers.IO`. `WebRtcManager.closePeer` mutates shared maps and disposes `PeerConnection`s. Check what thread the rest of the class uses. If it assumes the signaling or main thread, dispatch `PeerCloser` through the right context inside its implementation rather than in the ViewModel.
   - A failure in either call throws inside `viewModelScope.launch`, which crashes the app. Only the update paths have try/catch. Add a `CoroutineExceptionHandler` or per-intent `runCatching` + log, and ideally an error one-shot to show a snackbar.

### 9.3 Medium

8. **Mutation ordering.** `renameCamera`, `unpairDevice`, `markStealthIntroSeen` and `skipVersion` each launch their own IO coroutine, so two quick renames can finish out of order and persist the older name. Serialize the mutating intents (a `Mutex`, or a single `Channel<Intent>` consumer). Also, every mutation updates `_uiState` from the IO thread, which is safe for `MutableStateFlow` but unordered relative to `reloadDevicesAndPrefs()`: a reload that started before the rename can overwrite `cameraName` with the old value.

9. **`renameCamera` sanitization is only `trim().take(32)`.** `take(32)` counts UTF-16 units and can cut a surrogate pair (emoji) in half. Use code-point-aware truncation. Strip control characters, or use whatever the Specification defines as "sanitization". Call the same function from the text field's `onValueChange` so the UI and the ViewModel never disagree. Put the rule in a pure helper and test it directly.

10. **Redundant dispatching.** `viewModelScope.launch(ioDispatcher) { reloadDevicesAndPrefs() }` calls a function that does `withContext(ioDispatcher)` again. Pick one level. The same applies to `evaluateCachedRelease` and `checkForNetworkUpdates`. Also the two update paths are sequenced inside one coroutine, so a slow GitHub call delays nothing visible, but a slow `reloadDevicesAndPrefs()` delays the cached banner. Launch them as independent children.

11. **`HomeUiState` holds mutable-looking types.** `Map<String, Long>` and `List<PairedDevice>` under `@Immutable` are unverified promises (same note as §7.8, still open). `ReleaseInfo` and `PairedDevice` need to be immutable data classes for the annotation to be honest.

### 9.4 Document hygiene

12. **The document is now contradictory and its status is wrong.** §3.1, §3.2, §3.3, §3.4 and §5 still describe the superseded `AndroidViewModel` design (`HomeUiState` without `lastConnectedMap`, `onResume`, `unpairCamera(id, closePeer)`, `application`/`securityRepo` factory). The header still says "PROPOSED & READY FOR IMPLEMENTATION". Mark §3 as superseded by §8 (or rewrite it), change the status to "UNDER REVIEW", and replace the §5 checklist. It needs the new files (`HomeDataSources.kt`, the Android implementations of `HomePreferencesDataSource` and `TelemetryDataSource`, a `PeerCloser` bound to `CamMonitorApp.instance.webRtcManager`), the `MainActivity` factory with the new constructor, and the `Specification.md` §4.2/§5.1 updates required by AGENTS.md.

13. **Section numbering jumps from §8.2 to §8.4.** There is no §8.3 (probably the missing Android implementation / `MainActivity` wiring section, which is exactly what is needed).

14. **Android implementations are not specified.** The ViewModel is now clean, but the `callbackFlow` code that moved out of the earlier draft (battery receiver, `registerDefaultNetworkCallback`, `getNetworkStatus`) has no home in §8. Add `AndroidTelemetryDataSource(context)` with the code from the §3.2 draft (including a `distinctUntilChanged()` and conflation), and `AndroidHomePreferences(context)` wrapping `cammonitor_ui_prefs` and `CameraConnectionHistory`.

15. **Test count.** 174 is correct for the current tree (I counted 174 `@Test` annotations). Record the new total in the PR description as AGENTS.md requires, and don't hard-code "all 174 tests" in the plan since the number will change as soon as `HomeViewModelTest` is added.

### Verdict
Revise before implementing: fix 9.1 (the leak), 9.2 items 2-7, and the document cleanup in 9.4. Items in 9.3 can be handled during implementation but should be covered by tests.


---

## 10. Fifth-Pass Response & Final Implementation Specification (2026-11-11)

All 15 findings in §9 are accepted without reservation. The critique correctly diagnosed the self-canceling subscriber in §8's `WhileSubscribed` implementation, caught the absent MockK dependency, addressed the first-frame UI jump / dialog flash, and identified missing Android platform implementations.

This section serves as the **definitive, canonical implementation blueprint** superseding §3 and §8.

---

### 10.1 Resolutions to §9 Findings

| Issue | Finding | Definitive Resolution in §10 |
|---|---|---|
| **9.1** | `WhileSubscribed` invalidated by ViewModel `launchIn(viewModelScope)` | Removed internal collection. `HomeViewModel` exposes a single `uiState: StateFlow<HomeUiState>` produced by `combine(_internalState, telemetryFlow)` wrapped in `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())`. Downstream UI is the sole subscriber; leaving Home terminates hardware listeners after 5s. |
| **9.2** | MockK not in `build.gradle.kts` | Zero new dependencies added. Provided handwritten test fakes (`FakeSecurityRepository`, `FakeHomePreferences`, `FakeTelemetryDataSource`, `FakeUpdateRepository`, `FakePeerCloser`) running on pure JUnit 4 + Turbine. |
| **9.3** | Concrete Android types in ViewModel constructor | Introduced `UpdateRepository` interface abstracting `ReleaseChecker` HTTP and `UpdatePreferenceManager` storage. `SecurityRepository` is already an interface. Injected standard `CoroutineDispatcher` (default `Dispatchers.IO`). |
| **9.4** | First-frame jump and dialog flash | Added `val isLoaded: Boolean = false` to `HomeUiState`. `HomeScreen` suppresses dialog checks and renders a stable container skeleton until `isLoaded == true`. |
| **9.5** | Language flow incomplete | Added `onOpenSystemLanguageSettings: () -> Unit` to `HomeScreen`. Derived `currentLanguageTag` in `HomeRoute` via `LocaleHelper.getCurrentLanguageTag(context)` instead of leaking into ViewModel. |
| **9.6** | Concurrent `onActivated()` executions | Serialized with a coroutine `Mutex`. Network update check guards against duplicate execution while an existing check is in-flight. |
| **9.7** | Unpair ordering and error handling | Revoke whitelist in `securityRepo` *first*, purge `CameraConnectionHistory` *second*, close WebRTC peer *third*. Operations wrapped in `runCatching` with `AppLogger.w`. |
| **9.8** | Mutation race conditions | State mutations run sequentially under `mutationMutex`. |
| **9.9** | Name truncation slicing surrogate pairs | Extracted `sanitizeDeviceName(rawName, maxCodePoints)` using Java `codePoints()` stream; strips ISO controls and clamps to 32 code points without splitting emojis. |
| **9.13–9.14** | Missing Android implementations & §8.3 gap | Implemented `AndroidTelemetryDataSource`, `AndroidHomePreferences`, `AndroidUpdateRepository`, and `HomeViewModel.Factory`. |

---

### 10.2 Production Architecture & Code Assets

#### 1. Immutable State (`app/src/main/java/com/a0soft/gphone/camonitor/ui/home/HomeUiState.kt`)

```kotlin
package com.a0soft.gphone.camonitor.ui.home

import androidx.compose.runtime.Immutable
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.update.ReleaseInfo
import com.a0soft.gphone.camonitor.ui.common.BatteryInfo

@Immutable
data class HomeUiState(
    val isLoaded: Boolean = false,
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
        get() = homeLayoutFor(camerasCount = pairedCameras.size, viewersCount = pairedViewers.size)
}
```

---

#### 2. Domain Interfaces & Utilities (`app/src/main/java/com/a0soft/gphone/camonitor/ui/home/HomeDomain.kt`)

```kotlin
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
 * Sanitizes device names to prevent injection, control characters, and surrogate pair slicing.
 * Clamps by Unicode code points (max 32), safely handling multi-byte UTF-16 emojis.
 */
fun sanitizeDeviceName(rawName: String, maxCodePoints: Int = 32): String {
    val filtered = rawName.trim().filter { !it.isISOControl() }
    val codePoints = filtered.codePoints().toArray()
    if (codePoints.size <= maxCodePoints) return filtered
    return String(codePoints, 0, maxCodePoints)
}
```

---

#### 3. Pure `HomeViewModel` (`app/src/main/java/com/a0soft/gphone/camonitor/ui/home/HomeViewModel.kt`)

```kotlin
package com.a0soft.gphone.camonitor.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.a0soft.gphone.camonitor.BuildConfig
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.data.update.ReleaseCheckResult
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
        val cameraName: String = "",
        val pairedViewers: List<com.a0soft.gphone.camonitor.data.model.PairedDevice> = emptyList(),
        val pairedCameras: List<com.a0soft.gphone.camonitor.data.model.PairedDevice> = emptyList(),
        val lastConnectedMap: Map<String, Long> = emptyMap(),
        val availableRelease: com.a0soft.gphone.camonitor.data.update.ReleaseInfo? = null,
        val isStealthIntroSeen: Boolean = false
    )

    private val _internalState = MutableStateFlow(InternalState())
    private val activationMutex = Mutex()
    private val mutationMutex = Mutex()
    private var isNetworkCheckInFlight = false

    private val telemetryFlow = combine(
        telemetryDataSource.batteryFlow.distinctUntilChanged(),
        telemetryDataSource.networkFlow.distinctUntilChanged()
    ) { battery, network -> battery to network }

    /**
     * Single public StateFlow consumed by HomeRoute.
     * WhileSubscribed(5_000) guarantees hardware telemetry listeners unregister 5s
     * after HomeRoute leaves composition, solving the leak identified in §9.1.
     */
    val uiState: StateFlow<HomeUiState> = combine(_internalState, telemetryFlow) { internal, (battery, network) ->
        HomeUiState(
            isLoaded = internal.isLoaded,
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
        runCatching {
            val viewers = securityRepo.getPairedViewers()
            val cameras = securityRepo.getPairedCameras()
            val name = securityRepo.getDeviceName()
            val seen = preferencesDataSource.isStealthIntroSeen()
            val lastConnected = preferencesDataSource.getLastConnectedMap()

            _internalState.update { current ->
                current.copy(
                    isLoaded = true,
                    cameraName = name,
                    pairedViewers = viewers,
                    pairedCameras = cameras,
                    lastConnectedMap = lastConnected,
                    isStealthIntroSeen = seen
                )
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            AppLogger.w("HomeViewModel", "Failed to reload local data: ${e.message}")
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
                    runCatching {
                        // Whitelist record revoked FIRST so no incoming signaling is accepted
                        securityRepo.removePairedDevice(deviceId)
                        preferencesDataSource.clearConnectionHistory(deviceId)
                        // Live WebRTC peer connection torn down SECOND
                        peerCloser.closePeer(deviceId)
                        reloadLocalData()
                    }.onFailure { e ->
                        if (e is CancellationException) throw e
                        AppLogger.w("HomeViewModel", "Failed to unpair device $deviceId: ${e.message}")
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
        if (isNetworkCheckInFlight) return
        if (!updateRepository.shouldCheck()) return

        isNetworkCheckInFlight = true
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
        } finally {
            isNetworkCheckInFlight = false
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
```

---

#### 4. Platform Implementations (`app/src/main/java/com/a0soft/gphone/camonitor/ui/home/AndroidHomeDataSources.kt`)

```kotlin
package com.a0soft.gphone.camonitor.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.a0soft.gphone.camonitor.BuildConfig
import com.a0soft.gphone.camonitor.data.update.ReleaseCheckResult
import com.a0soft.gphone.camonitor.data.update.ReleaseChecker
import com.a0soft.gphone.camonitor.data.update.ReleaseInfo
import com.a0soft.gphone.camonitor.data.update.UpdatePreferenceManager
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
        prefs.edit().putBoolean("stealth_intro_seen", seen).apply()
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
```

---

#### 5. Decoupled Route & Stateless Screen (`app/src/main/java/com/a0soft/gphone/camonitor/ui/home/HomeScreen.kt`)

```kotlin
@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onStartCameraMode: (startInStealth: Boolean) -> Unit,
    onPairNewViewer: () -> Unit,
    onStartViewerMode: (deviceId: String) -> Unit,
    onPairNewCamera: () -> Unit,
    onOpenPairedDevices: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        UpdateNotificationHelper.dismissUpdateNotification(context)
        viewModel.onActivated()
    }

    HomeScreen(
        uiState = uiState,
        currentLanguageTag = LocaleHelper.getCurrentLanguageTag(context),
        onRenameCamera = viewModel::renameCamera,
        onUnpairCamera = viewModel::unpairDevice,
        onMarkStealthIntroSeen = viewModel::markStealthIntroSeen,
        onSkipVersion = viewModel::skipVersion,
        onDismissUpdate = viewModel::dismissUpdateBanner,
        onOpenReleaseUrl = { url ->
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                AppLogger.w("HomeRoute", "Failed to open release URL: ${e.message}")
            }
        },
        onChangeLanguage = { tag ->
            LocaleHelper.setLanguage(context, tag)
        },
        onOpenSystemLanguageSettings = {
            LocaleHelper.openSystemLanguageSettings(context)
        },
        onStartCameraMode = onStartCameraMode,
        onPairNewViewer = onPairNewViewer,
        onStartViewerMode = onStartViewerMode,
        onPairNewCamera = onPairNewCamera,
        onOpenPairedDevices = onOpenPairedDevices,
        onOpenLogs = onOpenLogs,
        onOpenAbout = onOpenAbout
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    currentLanguageTag: String,
    onRenameCamera: (String) -> Unit,
    onUnpairCamera: (String) -> Unit,
    onMarkStealthIntroSeen: () -> Unit,
    onSkipVersion: (String) -> Unit,
    onDismissUpdate: () -> Unit,
    onOpenReleaseUrl: (String) -> Unit,
    onChangeLanguage: (String) -> Unit,
    onOpenSystemLanguageSettings: () -> Unit,
    onStartCameraMode: (startInStealth: Boolean) -> Unit,
    onPairNewViewer: () -> Unit,
    onStartViewerMode: (deviceId: String) -> Unit,
    onPairNewCamera: () -> Unit,
    onOpenPairedDevices: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit
) {
    if (!uiState.isLoaded) {
        // Calm loading placeholder prevents layout jumping and suppresses dialog triggers
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Pure Stateless Composable rendering Consoles, Bars, and Dialogs
}
```

---

#### 6. Zero-Dependency Unit Test Suite (`app/src/test/java/com/a0soft/gphone/camonitor/home/HomeViewModelTest.kt`)

Runs under plain JUnit 4 + Kotlinx Coroutines Test + Turbine without Robolectric or MockK:

```kotlin
package com.a0soft.gphone.camonitor.home

import app.cash.turbine.test
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.data.update.ReleaseCheckResult
import com.a0soft.gphone.camonitor.data.update.ReleaseInfo
import com.a0soft.gphone.camonitor.ui.common.BatteryInfo
import com.a0soft.gphone.camonitor.ui.home.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    // Handwritten Test Fakes
    private class FakePreferences : HomePreferencesDataSource {
        var seen = false
        val history = mutableMapOf<String, Long>()
        override fun isStealthIntroSeen(): Boolean = seen
        override fun setStealthIntroSeen(seen: Boolean) { this.seen = seen }
        override fun getLastConnectedMap(): Map<String, Long> = history.toMap()
        override fun clearConnectionHistory(deviceId: String) { history.remove(deviceId) }
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
        override suspend fun getCachedRelease() = cached
        override suspend fun saveCachedRelease(release: ReleaseInfo) { cached = release }
        override suspend fun clearCachedRelease() { cached = null }
        override suspend fun isVersionSkipped(version: String) = version in skipped
        override suspend fun skipVersion(version: String) { skipped.add(version) }
        override suspend fun shouldCheck() = false
        override suspend fun recordCheck() {}
        override suspend fun checkLatestRelease() = ReleaseCheckResult.NoUpdate
    }

    private class FakeSecurity : SecurityRepository {
        var name = "Initial Camera"
        val devices = mutableListOf<PairedDevice>()
        override fun getDeviceId() = "dev-1"
        override fun getDeviceName() = name
        override fun setDeviceName(name: String) { this.name = name }
        override fun getDevicePublicKeyBase64() = "pk"
        override fun getKeyPair() = throw NotImplementedError()
        override fun generatePairingOtp() = "123456"
        override fun setActivePairingOtp(otp: String) {}
        override fun getActivePairingOtp(): String? = null
        override fun clearPairingOtp() {}
        override fun getPairedDevices() = devices.toList()
        override fun addPairedDevice(device: PairedDevice, maxLimit: Int) = devices.add(device)
        override fun removePairedDevice(deviceId: String) { devices.removeAll { it.deviceId == deviceId } }
        override fun isDeviceWhitelisted(deviceId: String) = devices.any { it.deviceId == deviceId }
        override fun getPairedDevice(deviceId: String) = devices.find { it.deviceId == deviceId }
        override fun createEnvelope(recipientId: String, plaintextJson: String, secretOrDerivedKey: ByteArray) = throw NotImplementedError()
        override fun verifyAndDecryptEnvelope(envelope: com.a0soft.gphone.camonitor.data.model.SignalingEnvelope, secretOrDerivedKey: ByteArray, isOtpKey: Boolean) = null
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
    fun initialState_isNotLoaded_suppressesDialogs() = runTest(testDispatcher) {
        val vm = HomeViewModel(FakeSecurity(), FakePreferences(), FakeTelemetry(), FakeUpdates(), PeerCloser {}, testDispatcher)
        assertFalse(vm.uiState.value.isLoaded)
    }

    @Test
    fun onActivated_loadsDataAndResolvesCamerasFirstLayout() = runTest(testDispatcher) {
        val security = FakeSecurity().apply {
            devices.add(PairedDevice("cam1", "4F", "pk1", "CAMERA", 1000L))
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
            devices.add(PairedDevice("cam1", "4F", "pk1", "CAMERA", 1000L))
        }
        val prefs = FakePreferences().apply { history["cam1"] = 5000L }
        var closedPeerId = ""
        val vm = HomeViewModel(security, prefs, FakeTelemetry(), FakeUpdates(), { closedPeerId = it }, testDispatcher)

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

        // 35 emojis clamped to 32 code points without slicing
        val input = "😀".repeat(35)
        vm.renameCamera(input)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(32, vm.uiState.value.cameraName.codePoints().count())
        assertEquals(String("😀".repeat(32).codePoints().toArray(), 0, 32), security.name)
    }
}
```

---

### 10.3 Updated Implementation Checklist

- [ ] Create `HomeUiState.kt` under `app/src/main/java/com/a0soft/gphone/camonitor/ui/home/`
- [ ] Create `HomeDomain.kt` under `app/src/main/java/com/a0soft/gphone/camonitor/ui/home/` (interfaces & `sanitizeDeviceName`)
- [ ] Create `AndroidHomeDataSources.kt` under `app/src/main/java/com/a0soft/gphone/camonitor/ui/home/`
- [ ] Create `HomeViewModel.kt` under `app/src/main/java/com/a0soft/gphone/camonitor/ui/home/`
- [ ] Refactor `HomeScreen.kt` into `HomeRoute` (stateful) and `HomeScreen` (stateless, context-free, previewable)
- [ ] Update `MainActivity.kt` to inject `HomeViewModel` via `HomeViewModel.provideFactory`
- [ ] Add `HomeViewModelTest.kt` under `app/src/test/java/com/a0soft/gphone/camonitor/home/`
- [ ] Run `.\gradlew.bat testDebugUnitTest` and update the PR description with the actual test count
- [ ] Synchronize [`Specification.md`](../Specification.md) §4.2 & §5.1
