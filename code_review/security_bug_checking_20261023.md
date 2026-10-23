# Security & Bug Audit Report (2026-10-23) - CamMonitor
**Date:** 2026-10-23  
**Target Codebase:** `com.a0soft.gphone.camonitor` (v1.0.4 / `versionCode = 5`, commit `74afada`)

---

## Executive Summary
Conducted a fresh, deep-dive security and reliability audit across the entire CamMonitor codebase (`app/src/main`). While all 35 issues from previous rounds remain resolved, this audit uncovered **6 new architectural, security, state-synchronization, and lifecycle bugs**:

1. **R6-01 (High - Security & Concurrency):** Diagnostic logs containing device IDs, pairing metadata, and network candidate IPs/ports are written to world/MTP-accessible external storage (`getExternalFilesDir`) instead of sandboxed internal storage (`filesDir`), and `_logsFlow` updates in [`AppLogger.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/log/AppLogger.kt#L35) suffer from a multi-threaded read-modify-write race condition.
2. **R6-02 (High - Security & Access Control):** Unauthenticated remote audio enablement on the Camera device before `PttStart` in [`WebRtcManager.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L458-L462) (allowing silent audio injection without triggering the `"Intercom Live"` UI indicator), plus missing role filtering in [`ViewerModeScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L86-L93) and [`CameraMonitorService.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L273).
3. **R6-03 (Medium - Multi-Viewer Concurrency):** Multi-viewer Push-to-Talk (`_isPttReceiving`) state clobbering, stuck `"Intercom Live"` HUD banner when a viewer disconnects mid-PTT, and stale disposed `remoteAudioTrack` reference in [`CameraMonitorService.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L407-L417) and [`WebRtcManager.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L790-L799).
4. **R6-04 (Medium - Service Lifecycle & Battery Drain):** `START_STICKY` restart in [`CameraMonitorService.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L64-L81) leaves a zombie foreground service holding a 24-hour CPU `WakeLock` and high-perf `WifiLock` in `IDLE` state without streaming when restarted by the OS after process death.
5. **R6-05 (Medium - Hardware & State Sync):** Torch state desynchronization across standby/reconnects and multi-viewer sessions, plus `CameraManager.setTorchMode` hardware contention during CameraX initialization in [`TorchManager.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/TorchManager.kt#L41-L62), [`CameraXVideoCapturer.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L714-L723), and [`ViewerModeScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L125).
6. **R6-06 (Low - Threading & Resource Leak):** Non-volatile cross-thread access to `isRemoteDescriptionSet` and `localOfferDesc` in [`ViewerModeScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L283), and post-stop `Surface` leak in [`CameraXVideoCapturer.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L414-L432).

---

## Audit Findings & Detailed Remediations

### R6-01: External Storage Log Exposure & Non-Atomic StateFlow Race in `AppLogger`
* **Severity:** **High**
* **Status:** **Resolved / Fixed**
* **Location:** [`AppLogger.kt:35, 78-84, 133-145`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/log/AppLogger.kt#L35), [`file_paths.xml:1-5`](file:///E:/Antigravity/CamMonitor/app/src/main/res/xml/file_paths.xml#L1-L5)
* **Root Cause:**
  1. In [`AppLogger.init`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/log/AppLogger.kt#L35):
     ```kotlin
     val dir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
     ```
     `getExternalFilesDir("logs")` places `cammonitor.log` on external storage (`/sdcard/Android/data/com.a0soft.gphone.camonitor/files/logs`). Because `minSdk = 28` (Android 9), any app on Android 9 or 10 holding `READ_EXTERNAL_STORAGE` can read these log files directly, and on all Android versions they are exposed via USB/MTP without root. The logs contain sensitive operational data (device UUIDs, pairing events, local/STUN IP addresses and ports in ICE candidates).
  2. In [`AppLogger.log`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/log/AppLogger.kt#L78-L84):
     ```kotlin
     val current = _logsFlow.value
     val updated = if (current.size >= MAX_MEMORY_LOGS) {
         current.drop(1) + entry
     } else {
         current + entry
     }
     _logsFlow.value = updated
     ```
     `AppLogger.log` is invoked concurrently from the Main thread, WebRTC native signaling/worker threads, HiveMQ Netty threads, and `Dispatchers.IO`. Performing a non-atomic read of `_logsFlow.value` followed by assignment causes lost updates and races against `clearLogs()`.
* **Remediation & Applied Resolution:**
  1. Stored log files exclusively inside sandboxed internal storage `File(context.filesDir, "logs")` and deleted any legacy logs in `context.getExternalFilesDir("logs")` on startup in [`AppLogger.init`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/log/AppLogger.kt#L35-L45).
  2. Removed `<external-files-path>` from [`file_paths.xml`](file:///E:/Antigravity/CamMonitor/app/src/main/res/xml/file_paths.xml).
  3. Replaced non-atomic `_logsFlow.value = ...` with `kotlinx.coroutines.flow.update` (CAS loop) in [`AppLogger.log`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/log/AppLogger.kt#L82-L88).

```kotlin
// In AppLogger.kt:
import kotlinx.coroutines.flow.update

fun init(context: Context) {
    // Clean up any legacy external logs for privacy
    try {
        context.getExternalFilesDir("logs")?.deleteRecursively()
    } catch (_: Exception) {}

    val dir = File(context.filesDir, "logs")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    logDir = dir
    activeLogFile = File(dir, "cammonitor.log")
    ...
}

// In log():
_logsFlow.update { current ->
    if (current.size >= MAX_MEMORY_LOGS) {
        current.drop(1) + entry
    } else {
        current + entry
    }
}
```

---

### R6-02: Unauthenticated Remote Audio Enablement & Role Bypass in `WebRtcManager` / `ViewerModeScreen` / `CameraMonitorService`
* **Severity:** **High**
* **Status:** **Resolved / Fixed**
* **Location:** [`WebRtcManager.kt:458-468`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L458-L468), [`ViewerModeScreen.kt:86-93`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L86-L93), [`CameraMonitorService.kt:283-287`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L283-L287)
* **Root Cause:**
  1. **Silent Audio Injection Without PTT Indicator:** In [`WebRtcManager.onTrack`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L458-L462):
     ```kotlin
     val isCamera = localVideoTrack != null
     if (isCamera) {
         track.setEnabled(true)
         com.a0soft.gphone.camonitor.ui.common.AudioRoutingManager.routeToSpeaker(context)
         AppLogger.i(tag, "Camera (PA) enabled remote AudioTrack from viewer $peerId and routed to speaker")
     }
     ```
     As soon as the WebRTC connection is established, the Camera (PA) immediately enables the viewer's incoming `AudioTrack` (`track.setEnabled(true)`). It relies solely on the Viewer (PB) keeping its outgoing mic track muted until the user presses the PTT button. A modified or compromised viewer client can transmit audio continuously to the Camera's speaker **without sending `ControlCommand.PttStart`**, bypassing the `"Intercom Live"` (`_isPttReceiving`) visual indicator on the Camera screen.
  2. **Role Confusion in `ViewerModeScreen` & `CameraMonitorService`:**
     - In [`ViewerModeScreen.kt:86-93`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L86-L93), `val pairedDevices = remember { securityRepo.getPairedDevices() }` fetches all paired devices regardless of role. If a device was paired with a Viewer (`role = "VIEWER"`) and `selectedDeviceId` is null or stale, `pairedDevices.firstOrNull()` selects the `VIEWER` record as `targetCamera`.
     - In [`CameraMonitorService.kt:273`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L273), `observeSignaling` only checks `isDeviceWhitelisted(senderId)` instead of verifying that the sender is an authorized viewer (`getPairedViewers().any { it.deviceId == senderId }`).
* **Remediation & Applied Resolution:**
  1. In [`WebRtcManager.onTrack`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L463-L468), initialized incoming viewer audio tracks on Camera (PA) as **disabled** (`val isPeerPttActive = activePttPeers.contains(peerId); track.setEnabled(isPeerPttActive)`), and only enabled them when `onPttCommand(peerId, true)` is invoked via `ControlCommand.PttStart`.
  2. In [`ViewerModeScreen.kt:86-93`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L86-L93), replaced `getPairedDevices()` with `securityRepo.getPairedCameras()` and resolved `targetCamera` exclusively from paired cameras.
  3. In [`CameraMonitorService.kt:283-287`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L283-L287), verified `val isAuthorizedViewer = securityRepository.getPairedViewers().any { it.deviceId == senderId }` before accepting signaling messages on the Camera.

```kotlin
// In WebRtcManager.kt (onTrack):
} else if (track is AudioTrack) {
    AppLogger.i(tag, "Remote AudioTrack received from $peerId: id=${track.id()}, state=${track.state()}")
    remoteAudioTracks[peerId] = track
    remoteAudioTrack = track
    val isCamera = localVideoTrack != null
    if (isCamera) {
        val isPeerPttActive = activePttPeers.contains(peerId)
        track.setEnabled(isPeerPttActive)
        AppLogger.i(tag, "Camera (PA) received remote AudioTrack from viewer $peerId (initially enabled=$isPeerPttActive)")
    } else {
        track.setEnabled(isRemoteAudioEnabled)
        AppLogger.i(tag, "Viewer (PB) remote AudioTrack enabled: $isRemoteAudioEnabled")
    }
}
```

---

### R6-03: Multi-Viewer PTT State Clobbering, Stuck Intercom Banner on Disconnect, and Disposed `remoteAudioTrack` Reference
* **Severity:** **Medium**
* **Status:** **Resolved / Fixed**
* **Location:** [`CameraMonitorService.kt:58-59, 418-423`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L58-L59), [`WebRtcManager.kt:127-129, 431-437, 708-721, 804-818`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L708-L721)
* **Root Cause:**
  1. **Multi-Viewer PTT Clobbering:** Camera (PA) supports up to 2 concurrent viewers. In [`CameraMonitorService.handleViewerCommand`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L407-L417), `_isPttReceiving` is set directly to `true` on `PttStart` and `false` on `PttStop` without tracking which `peerId` is speaking. If Viewer 1 is holding PTT and Viewer 2 taps and releases PTT, `_isPttReceiving.value` becomes `false` (hiding the `"Intercom Live"` banner) while Viewer 1's audio is still playing.
  2. **Stuck `"Intercom Live"` Banner on Disconnect:** If a viewer is holding PTT (`PttStart` sent) and loses network connectivity (`DISCONNECTED` / `FAILED` / `CLOSED`) before `PttStop` can be sent, `_isPttReceiving.value` stays `true` indefinitely on the Camera screen.
  3. **Disposed `remoteAudioTrack` Fallback:** In [`WebRtcManager.closePeer`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L794-L797):
     ```kotlin
     remoteAudioTracks.remove(peerId)
     if (remoteAudioTrack == null || remoteAudioTracks.isEmpty()) {
         remoteAudioTrack = null
     }
     ```
     If Viewer 2 connected after Viewer 1 (`remoteAudioTrack = track2`) and then Viewer 2 disconnects, `remoteAudioTracks.isEmpty()` is `false` (Viewer 1 is still connected), so `remoteAudioTrack` remains pointing to Viewer 2's disposed `AudioTrack`!
* **Remediation & Applied Resolution:**
  1. Tracked active PTT speakers per peer in [`WebRtcManager`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L127-L129) using `private val activePttPeers = ConcurrentHashMap.newKeySet<String>()` and exposed `val isPttReceiving: StateFlow<Boolean>` delegated directly by [`CameraMonitorService.isPttReceiving`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L58-L59).
  2. Removed `peerId` from `activePttPeers` and updated `_isPttReceiving.value = activePttPeers.isNotEmpty()` whenever `onPttCommand(peerId, false)` is called, `closePeer(peerId)` is called, `closeAll()` is called, or `onConnectionChange` transitions to `DISCONNECTED`, `FAILED`, or `CLOSED`.
  3. In [`WebRtcManager.closePeer`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L810-L818), updated `remoteAudioTrack = remoteAudioTracks.values.firstOrNull()` and only cleared `remoteVideoTrack` when in Viewer mode (`localVideoTrack == null`) or when `peerConnections.isEmpty()`.

```kotlin
// In WebRtcManager.kt:
private val activePttPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
private val _isPttReceiving = MutableStateFlow(false)
val isPttReceiving: StateFlow<Boolean> = _isPttReceiving.asStateFlow()

fun onPttCommand(peerId: String, isStart: Boolean) {
    if (isStart) {
        activePttPeers.add(peerId)
    } else {
        activePttPeers.remove(peerId)
    }
    _isPttReceiving.value = activePttPeers.isNotEmpty()
    val track = remoteAudioTracks[peerId]
    track?.setEnabled(isStart)
    if (isStart) {
        com.a0soft.gphone.camonitor.ui.common.AudioRoutingManager.routeToSpeaker(context)
    }
    AppLogger.i(tag, "onPttCommand: peerId=$peerId, isStart=$isStart, activePttCount=${activePttPeers.size}")
}
```

---

### R6-04: Zombie `START_STICKY` Foreground Service Holding 24h WakeLock/WifiLock After OS Restart
* **Severity:** **Medium**
* **Status:** **Resolved / Fixed**
* **Location:** [`CameraMonitorService.kt:63-76`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L63-L76), [`CameraModeScreen.kt:96-115`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/camera/CameraModeScreen.kt#L96-L115)
* **Root Cause:**  
  [`CameraMonitorService.onStartCommand`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L64-L67) returns `START_STICKY`, but `startStreaming()` is only called from `CameraModeScreen`'s `ServiceConnection.onServiceConnected` callback.  
  If the user backgrounds the Camera app while streaming and the Android OS kills the process under memory pressure, Android later recreates `CameraMonitorService` and calls `onCreate()` + `onStartCommand(null, 0, startId)` **without** any bound `MainActivity`.
  - `onCreate()` acquires a 24-hour `PARTIAL_WAKE_LOCK`, acquires `WIFI_MODE_FULL_HIGH_PERF`, sets `AudioManager.MODE_IN_COMMUNICATION`, and displays the `"CamMonitor Active"` foreground notification.
  - Because no Activity is bound, `startStreaming()` is **never called**. The service remains in `CameraStreamingState.IDLE` indefinitely—draining battery and holding audio communication mode while neither capturing video nor listening to MQTT signaling.
* **Remediation & Applied Resolution:**  
  In [`CameraMonitorService.onStartCommand`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L63-L76), verified that `CAMERA` and `RECORD_AUDIO` permissions are granted; if missing, calls `stopSelf()` and returns `START_NOT_STICKY`. Otherwise invokes `startStreaming()` directly (which is idempotent when `isStreamingActive` is already `true`), ensuring sticky OS restarts resume MQTT signaling and camera standby properly.

```kotlin
// In CameraMonitorService.kt:
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    AppLogger.i(tag, "CameraMonitorService onStartCommand (intent=${intent != null}, isStreamingActive=$isStreamingActive)")
    val hasCam = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!hasCam || !hasMic) {
        AppLogger.w(tag, "Missing CAMERA or RECORD_AUDIO permission in onStartCommand; stopping service")
        stopSelf()
        return START_NOT_STICKY
    }
    startStreaming()
    return START_STICKY
}
```

---

### R6-05: Torch State Desynchronization Across Reconnects/Standby & `CameraManager.setTorchMode` Contention
* **Severity:** **Medium**
* **Status:** **Resolved / Fixed**
* **Location:** [`TorchManager.kt:41-68`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/TorchManager.kt#L41-L68), [`CameraXVideoCapturer.kt:724-735`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L724-L735), [`WebRtcManager.kt:214-216, 326-328, 525-530`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L525-L530), [`ViewerModeScreen.kt:445-448`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L445-L448)
* **Root Cause:**
  1. **Hardware Contention During CameraX Init:** In [`CameraXVideoCapturer.setTorch`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L714-L723), if `camera?.cameraControl` is null (because CameraX is still asynchronously binding), `setTorch()` sets `isTorchActive = enabled` and returns `false`. [`TorchManager.setTorch`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/TorchManager.kt#L46) sees `false` and falls back to `cameraManager.setTorchMode(id, enabled)` while CameraX is opening the camera device, causing Camera2 `CAMERA_IN_USE` errors.
  2. **Torch State Lost on Standby / Camera Restart:** When `stopCameraCapture()` is called after 60s of inactivity, the camera hardware is released (turning off the physical flashlight), but `TorchManager._isTorchOn.value` remains `true`. When a new `CameraXVideoCapturer` is created in `startCameraCapture()`, its `isTorchActive` defaults to `false`, out of sync with `TorchManager.isTorchOn.value`.
  3. **No Torch State Sync to Viewers:** Unlike `NightModeReport`, `FpsReport`, `Battery`, and `Rename`, there is no `ControlCommand.TorchReport`. When a Viewer connects or reconnects while the Camera's torch is ON (or when Viewer 1 toggles the torch while Viewer 2 is also watching), the Viewer UI's `isTorchActive` is out of sync with the Camera's actual torch state.
* **Remediation & Applied Resolution:**
  1. Added `data class TorchReport(val enabled: Boolean) : ControlCommand()` to [`Models.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/model/Models.kt#L244) and verified JSON serialization in [`ModelSerializationTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/model/ModelSerializationTest.kt#L151-L159).
  2. Sent `TorchReport` on DataChannel open in [`WebRtcManager.setupDataChannel`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L525-L530), in response to `QueryBattery`, and broadcasted `TorchReport` whenever `ControlCommand.Torch` is handled in [`CameraMonitorService`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L406-L426).
  3. Handled `ControlCommand.TorchReport` in [`ViewerModeScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L445-L448) to update `isTorchActive`.
  4. In [`CameraXVideoCapturer.setTorch`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L724-L735), returned `true` when `isCapturing.get()` is `true` so `TorchManager` does not invoke `CameraManager.setTorchMode` while CameraX is asynchronously binding (`bindCameraSession` applies `isTorchActive` once bound). Added [`TorchManager.resetState()`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/TorchManager.kt#L65-L67) called from `stopCameraCapture()`, and synced `capturer.setTorch(CamMonitorApp.instance.torchManager.isTorchOn.value)` in `startCameraCapture()`.

---

### R6-06: Non-Volatile Cross-Thread Handshake Variables in `ViewerModeScreen` & Post-Stop `SurfaceRequest` Leak in `CameraXVideoCapturer`
* **Severity:** **Low**
* **Status:** **Resolved / Fixed**
* **Location:** [`ViewerModeScreen.kt:283, 318, 323-326, 370`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L283), [`CameraXVideoCapturer.kt:414-440`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L414-L440)
* **Root Cause:**
  1. In [`ViewerModeScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L283), `var localOfferDesc: SessionDescription? = null` and `var isRemoteDescriptionSet = false` are plain non-volatile local variables mutated on the WebRTC native signaling thread (`SdpObserver.onSetSuccess`) and read inside the `retryJob` coroutine loop on the Main dispatcher without a memory barrier.
  2. In [`CameraXVideoCapturer.bindCameraSession`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L414-L432):
     ```kotlin
     newPreview.setSurfaceProvider(mainExecutor) { request ->
         ...
         this.surface?.release()
         val newSurface = Surface(surfaceTexture)
         this.surface = newSurface
         ...
         request.provideSurface(newSurface, mainExecutor) { result ->
             AppLogger.d(tag, "CameraX Surface closed/invalidated: ${result.resultCode}")
         }
     }
     ```
     If `stopCapture()` executes before a queued `SurfaceRequest` callback runs on `mainExecutor`, the callback still allocates a new `Surface(surfaceTexture)` and provides it to CameraX after `stopCapture()` has already completed cleanup, leaking the native `Surface`. Furthermore, when `newSurface` is eventually invalidated by CameraX (e.g. after rebinding), the `provideSurface` result listener does not release `newSurface` if it was superseded.
* **Remediation & Applied Resolution:**
  1. Replaced `localOfferDesc` with `AtomicReference<SessionDescription?>(null)` and `isRemoteDescriptionSet` with `AtomicBoolean(false)` in [`ViewerModeScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L283-L370).
  2. In [`CameraXVideoCapturer.bindCameraSession()`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L414-L440), guarded the `setSurfaceProvider` callback with `if (!isCapturing.get()) { request.willNotProvideSurface(); return@setSurfaceProvider }` and released `newSurface` in the `provideSurface` completion callback when CameraX finishes using it.

```kotlin
// In CameraXVideoCapturer.kt:
newPreview.setSurfaceProvider(mainExecutor) { request ->
    if (!isCapturing.get()) {
        request.willNotProvideSurface()
        return@setSurfaceProvider
    }
    val res = request.resolution
    AppLogger.i(tag, "CameraX Surface requested: ${res.width}x${res.height} (night=$enableNight)")
    helper.setTextureSize(res.width, res.height)
    val surfaceTexture = helper.surfaceTexture
    surfaceTexture.setDefaultBufferSize(res.width, res.height)

    this.surface?.release()
    val newSurface = Surface(surfaceTexture)
    this.surface = newSurface

    request.setTransformationInfoListener(mainExecutor) { transformationInfo ->
        currentRotationDegrees = transformationInfo.rotationDegrees
        AppLogger.i(tag, "TransformationInfo: rotationDegrees=${transformationInfo.rotationDegrees}, cropRect=${transformationInfo.cropRect}, targetRot=$currentTargetRotation")
    }
    request.provideSurface(newSurface, mainExecutor) { result ->
        AppLogger.d(tag, "CameraX Surface closed/invalidated: ${result.resultCode}")
        if (this.surface === newSurface) {
            this.surface = null
        }
        newSurface.release()
    }
}
```

---

## Summary Matrix

| ID | Category | Severity | Status | Location | Issue Summary |
|---|---|---|---|---|---|
| **R6-01** | Security / Concurrency | **High** | ✅ Fixed | [`AppLogger.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/log/AppLogger.kt#L35), [`file_paths.xml`](file:///E:/Antigravity/CamMonitor/app/src/main/res/xml/file_paths.xml) | Logs stored on external storage (`getExternalFilesDir`) & non-atomic `_logsFlow.value` race |
| **R6-02** | Security / Access Control | **High** | ✅ Fixed | [`WebRtcManager.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L458), [`ViewerModeScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L86), [`CameraMonitorService.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L273) | Remote viewer audio enabled before `PttStart` (silent audio injection) & missing role filtering |
| **R6-03** | Multi-Viewer / Concurrency | **Medium** | ✅ Fixed | [`CameraMonitorService.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L407), [`WebRtcManager.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L695) | Multi-viewer PTT state clobbering, stuck PTT banner on disconnect, & disposed `remoteAudioTrack` |
| **R6-04** | Service Lifecycle | **Medium** | ✅ Fixed | [`CameraMonitorService.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L64) | `START_STICKY` OS restart leaves zombie service holding 24h WakeLock/WifiLock without streaming |
| **R6-05** | Hardware / State Sync | **Medium** | ✅ Fixed | [`TorchManager.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/TorchManager.kt#L41), [`Models.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/model/Models.kt#L243), [`ViewerModeScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L125) | Torch state desync across reconnects/standby & `CameraManager.setTorchMode` contention |
| **R6-06** | Threading / Resource Leak | **Low** | ✅ Fixed | [`ViewerModeScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L283), [`CameraXVideoCapturer.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L414) | Non-volatile cross-thread SDP variables & post-stop `Surface` leak in `SurfaceProvider` |
