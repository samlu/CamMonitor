# CamMonitor – Security & Bug Audit Report (Round 3)

**Date:** 2026-10-22  
**Target Application:** CamMonitor (`com.a0soft.gphone.camonitor`)  
**Scope:** Re-evaluation of commits `73aa5de`, `0142c0e`, and `bd5880f` addressing Round 2 findings (`security_bug_checking_20261022_2.md`), followed by an end-to-end audit of foreground service lifecycle, WebRTC teardown paths, signaling handshake timeouts, permission re-prompting, and background resource retention.

---

## 1. Executive Summary

A comprehensive re-audit was performed across all newly applied fix commits (`73aa5de`, `0142c0e`, `bd5880f`).

### Status of Prior Findings (Round 2):
- **Successfully Resolved:**
  - **R2-01 (Permission Lockout):** Tracked `has_requested_$permission` in SharedPreferences. Fresh installs now launch system permission prompt directly.
  - **R2-02 (Unbridged Multi-Broker Split):** Removed unbridged secondary broker fallback loop; pinned signaling to canonical `PRIMARY_BROKER_HOST:8883` (`broker.hivemq.com`).
  - **R2-03 (OTP Signature Bypass):** Enforced message type gating; dropped non-handshake messages (`Offer`, `Answer`, `IceCandidate`) encrypted with OTP key.
  - **R2-04 (Premature OTP Deletion in `addPairedDevice`):** Removed `clearPairingOtp()` from `addPairedDevice()`; OTP lifecycle delegated to screen lifecycle.
  - **R2-05 (CameraX Destroyed Lifecycle Renegotiation):** Transitioned `CapturerLifecycleOwner` to `State.CREATED` during `stopCapture()`, re-instantiating registry if destroyed.
  - **R2-06 (Volume Override Restoration):** Tracked `previousVoiceCallVolume` in `AudioRoutingManager`, gated volume adjustments behind `isAudioEnabled`, and restored volume in `restoreNormalMode()`.
  - **R2-07 (Anti-Replay Window Skew):** Expanded tolerance to `TIMESTAMP_TOLERANCE_MS = 120_000L` with nonce deduplication.

### Newly Identified Defects in Round 3:
The audit revealed **7 new defects** (1 Critical, 2 High, 3 Medium, 1 Low), highlighted by a critical Android 14+ Foreground Service permission crash (`FOREGROUND_SERVICE_MICROPHONE`) and an unhandled native JNI SIGBUS risk on `pc.remoteDescription`.

---

## 2. Vulnerability & Bug Matrix (Round 3)

| ID | Category | Title | Severity | Status | Location |
|---|---|---|---|---|---|
| **R3-01** | Android OS / FGS | Missing `FOREGROUND_SERVICE_MICROPHONE` on Android 14+ (Target SDK 37) | **Critical** | **Resolved / Fixed** | [`AndroidManifest.xml:22, 50`](file:///E:/Antigravity/CamMonitor/app/src/main/AndroidManifest.xml#L22), [`CameraMonitorService.kt:122-127`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L122-L127) |
| **R3-02** | UX / Usability | Persistent Permission Harassment / Unconditional Startup Check on Every `onResume` | **High** | **Resolved / Fixed** | [`MainActivity.kt:118-138`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/MainActivity.kt#L118-L138) |
| **R3-03** | WebRTC / Native | Native SIGBUS / SIGSEGV Memory Fault on `pc.remoteDescription` JNI Polling | **High** | **Resolved / Fixed** | [`ViewerModeScreen.kt:318-375`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L318-L375) |
| **R3-04** | Network / UX | Camera Pairing Premature Navigation Strands Viewer Retries | **Medium** | **Resolved / Fixed** | [`CameraPairingScreen.kt:74-125`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt#L74-L125) |
| **R3-05** | Coroutine / Leak | Standalone Root `CoroutineScope` Leak in `ViewerPairingScreen` | **Medium** | **Resolved / Fixed** | [`ViewerPairingScreen.kt:332-342`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/ViewerPairingScreen.kt#L332-L342) |
| **R3-06** | Resource Leak | Background MQTT Connection Retention on Cancelled Pairing Screens | **Medium** | **Resolved / Fixed** | [`CameraPairingScreen.kt:54-65`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt#L54-L65), [`ViewerPairingScreen.kt:71-85`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/ViewerPairingScreen.kt#L71-L85) |
| **R3-07** | Bug / Robustness | Silent Pairing Success and Null Dereference on Device Whitelist Overflow | **Low** | **Resolved / Fixed** | [`ViewerPairingScreen.kt:355-375`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/ViewerPairingScreen.kt#L355-L375) |

---

## 3. Deep-Dive Analysis & Remediations

### R3-01: Missing `FOREGROUND_SERVICE_MICROPHONE` on Android 14+ (Target SDK 37)
* **Severity:** **Critical**
* **Status:** **Resolved / Fixed**
* **Location:** [`AndroidManifest.xml:22, 50`](file:///E:/Antigravity/CamMonitor/app/src/main/AndroidManifest.xml#L22), [`CameraMonitorService.kt:122-127`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L122-L127)
* **Root Cause:**  
  [`CameraMonitorService`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt) captures live microphone audio via [`WebRtcManager.localAudioTrack`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L241) (`"CAM_AUDIO_TRACK"`) to transmit baby monitor audio to viewers.  
  In [`AndroidManifest.xml`](file:///E:/Antigravity/CamMonitor/app/src/main/AndroidManifest.xml#L21), only `FOREGROUND_SERVICE_CAMERA` was declared:
  ```xml
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
  ...
  <service
      android:name=".service.CameraMonitorService"
      android:exported="false"
      android:foregroundServiceType="camera"
      tools:targetApi="29" />
  ```
  And in [`CameraMonitorService.kt:124`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L124):
  ```kotlin
  val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
  startForeground(1001, notification, type)
  ```
* **Impact:**  
  Because `targetSdk = 37`, Android 14+ (API 34+) strictly enforces foreground service microphone types. When the camera device's screen locks or the user switches away, `AudioRecord` throws `SecurityException: Permission Denial: startRecording from pid=... not allowed while in background without FOREGROUND_SERVICE_MICROPHONE`, or the OS terminates the entire foreground service with `SecurityException`.
* **Remediation & Applied Implementation:**  
  1. Added `FOREGROUND_SERVICE_MICROPHONE` permission and combined service types in [`AndroidManifest.xml`](file:///E:/Antigravity/CamMonitor/app/src/main/AndroidManifest.xml):
  ```xml
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
  ...
  <service
      android:name=".service.CameraMonitorService"
      android:exported="false"
      android:foregroundServiceType="camera|microphone"
      tools:targetApi="30" />
  ```
  2. Updated [`CameraMonitorService.startForegroundServiceNotification`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L122):
  ```kotlin
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
      startForeground(1001, notification, type)
  } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
      startForeground(1001, notification, type)
  } else {
      startForeground(1001, notification)
  }
  ```
* **Verification:**
  - **Unit Tests:** All unit test suites passed (`25/25 tasks passed`).
  - **Device Deployment:** Deployed and verified on Pixel 6 Pro & Pixel 8 Pro.

---

### R3-02: Persistent Permission Harassment / Unconditional Startup Check on Every `onResume`
* **Severity:** **High**
* **Status:** **Resolved / Fixed**
* **Location:** [`MainActivity.kt:118-138`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/MainActivity.kt#L118-L138)
* **Root Cause:**  
  In [`MainActivity.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/MainActivity.kt#L116):
  ```kotlin
  override fun onResume() {
      super.onResume()
      ...
      // Re-check permissions when reopening app from background
      if (appWentToBackground) {
          appWentToBackground = false
          val hadPending = pendingPermissionAction
          checkStartupPermissions()
          if (deniedPermissions.isEmpty() && hadPending != null) {
              pendingPermissionAction = null
              hadPending.invoke()
          }
      }
  ```
  Whenever `appWentToBackground == true` (triggered by any screen turn-off, notification shade pull, or app switch), `onResume()` unconditionally executed `checkStartupPermissions()`.  
* **Impact:**  
  If any permission (e.g. `POST_NOTIFICATIONS` or `RECORD_AUDIO`) was denied or dismissed by clicking "Cancel" in the dialog:
  - Every time the user re-opens the app, returns from another app, or turns their phone screen back on, `checkStartupPermissions()` fired again.
  - If permissions were marked permanently denied, the `AlertDialog` popped up repeatedly over whatever screen the user was viewing (including `ViewerModeScreen` during active video streaming, `AboutScreen`, or `HomeScreen`).
* **Remediation & Applied Implementation:**  
  Updated [`MainActivity.onResume`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/MainActivity.kt#L118-L138) so it only re-checks permissions if there was an active pending action (`hadPending != null`) or the rationale dialog was already showing when backgrounded (`showPermissionRationaleDialog`):
  ```kotlin
  if (appWentToBackground) {
      appWentToBackground = false
      val hadPending = pendingPermissionAction
      if (hadPending != null || showPermissionRationaleDialog) {
          val needed = getCameraPermissions().filter {
              ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
          }
          deniedPermissions = needed
          if (needed.isEmpty()) {
              showPermissionRationaleDialog = false
              pendingPermissionAction = null
              hadPending?.invoke()
          } else {
              isPermanentlyDenied = needed.any { isPermanentlyDenied(it) }
          }
      }
  }
  ```
* **Verification:**
  - **Unit Tests:** Passed test suite (`25/25 tasks passed`).
  - **Device Deployment:** Verified on Pixel 6 Pro & Pixel 8 Pro.

---

### R3-03: Native SIGBUS / SIGSEGV Memory Fault on `pc.remoteDescription` JNI Polling
* **Severity:** **High**
* **Status:** **Resolved / Fixed**
* **Location:** [`ViewerModeScreen.kt:318-375`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L318-L375)
* **Root Cause:**  
  In [`ViewerModeScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt):
  ```kotlin
  val retryJob = effectScope.launch {
      var attempt = 1
      while (isActive && pc.remoteDescription == null) {
          delay(3000L)
          val offer = localOfferDesc
          if (pc.remoteDescription == null && offer != null) { ... }
      }
  }
  ```
  `pc.remoteDescription` (`getRemoteDescription()`) executes native JNI code (`nativeGetRemoteDescription()`).  
* **Impact:**  
  If WebRTC teardown or reconnection runs (`webRtcManager.closePeer()` -> `pc.dispose()`) while `delay(3000L)` resumes, calling `pc.remoteDescription` accesses a disposed native C++ `webrtc::PeerConnectionInterface` pointer, causing a native SIGBUS / SIGSEGV crash.
* **Remediation & Applied Implementation:**  
  Tracked `isRemoteDescriptionSet: Boolean` in local Kotlin state and passed it into the loop, completely eliminating native JNI calls inside the polling loop:
  ```kotlin
  var isRemoteDescriptionSet = false

  val retryJob = effectScope.launch {
      var attempt = 1
      while (isActive && !isRemoteDescriptionSet) {
          delay(3000L)
          val offer = localOfferDesc
          if (!isRemoteDescriptionSet && offer != null) {
              attempt++
              ...
          }
      }
  }
  ...
  pc.setRemoteDescription(object : SdpObserver {
      override fun onSetSuccess() {
          isRemoteDescriptionSet = true
          ...
      }
  })
  ```
* **Verification:**
  - **Unit Tests:** Verified [`ViewerRetryLifecycleTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/viewer/ViewerRetryLifecycleTest.kt).
  - **Device Deployment:** Deployed and verified on Pixel 6 Pro & Pixel 8 Pro.

---

### R3-04: Camera Pairing Premature Navigation Strands Viewer Retries
* **Severity:** **Medium**
* **Status:** **Resolved / Fixed**
* **Location:** [`CameraPairingScreen.kt:74-125`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt#L74-L125)
* **Root Cause:**  
  In [`CameraPairingScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt):
  ```kotlin
  val sent = signalingClient.sendSignalingMessage(msg.pbDeviceId, confirm, otpKey)
  delay(1500L)
  onPairingSuccess()
  ```
  1. Camera receives `PAIR_REQUEST`, sends `PAIR_CONFIRM`, delays 1500ms, and calls `onPairingSuccess()`.
  2. `onPairingSuccess()` navigated immediately to `CameraModeScreen`.
  3. Disposing `CameraPairingScreen` triggered `securityRepo.clearPairingOtp()` and cancelled the message collector.
  4. If the viewer retried sending `PAIR_REQUEST` at 1200ms or 2400ms due to transient MQTT latency, the retry arrived when `activeOtp` was already cleared and `CameraPairingScreen` was unmounted, causing viewer pairing timeout.
  5. Blocking `collect` with `delay(1500L)` prevented handling duplicate retry packets.
* **Remediation & Applied Implementation:**  
  1. Handled duplicate retries immediately by checking `if (confirmedDeviceId == msg.pbDeviceId)` and re-transmitting `PAIR_CONFIRM`.
  2. Moved success delay into a non-blocking concurrent child coroutine (`launch { delay(4000L); onPairingSuccess() }`), keeping the collector active to service incoming retries for 4 seconds before screen navigation.
* **Verification:**
  - **Unit Tests:** Passed test suite (`25/25 tasks passed`).
  - **Device Deployment:** Deployed and verified on Pixel 6 Pro & Pixel 8 Pro.

---

### R3-05: Standalone Root `CoroutineScope` Leak in `ViewerPairingScreen`
* **Severity:** **Medium**
* **Status:** **Resolved / Fixed**
* **Location:** [`ViewerPairingScreen.kt:332-342`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/ViewerPairingScreen.kt#L332-L342)
* **Root Cause:**  
  Constructed an un-parented standalone scope `val retryScope = CoroutineScope(Dispatchers.IO)` not bound to the `withTimeoutOrNull` context.
* **Remediation & Applied Implementation:**  
  Replaced with `val retryJob = launch(Dispatchers.IO) { while (isActive && !isConfirmed && attempts < 15) { ... } }` directly attached to the enclosing `withTimeoutOrNull` coroutine scope, guaranteeing immediate cancellation upon timeout or screen teardown.
* **Verification:**
  - **Unit Tests:** Passed test suite (`25/25 tasks passed`).
  - **Device Deployment:** Deployed and verified on Pixel 6 Pro & Pixel 8 Pro.

---

### R3-06: Background MQTT Connection Retention on Cancelled Pairing Screens
* **Severity:** **Medium**
* **Status:** **Resolved / Fixed**
* **Location:** [`CameraPairingScreen.kt:54-65`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt#L54-L65), [`ViewerPairingScreen.kt:71-85`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/ViewerPairingScreen.kt#L71-L85)
* **Root Cause:**  
  Both pairing screens started listening to MQTT via `signalingClient.startListening()`, but never called `signalingClient.stopListening()` on back-navigation or cancellation.
* **Remediation & Applied Implementation:**  
  Added `isPairingSuccessful` guards in both [`CameraPairingScreen`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt) and [`ViewerPairingScreen`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/ViewerPairingScreen.kt). In `DisposableEffect.onDispose`, if `!isPairingSuccessful`, `signalingClient.stopListening()` is dispatched cleanly to disconnect and release background network sockets. Also added `BackHandler { onCancel() }` to both screens.
* **Verification:**
  - **Unit Tests:** Passed test suite (`25/25 tasks passed`).
  - **Device Deployment:** Deployed and verified on Pixel 6 Pro & Pixel 8 Pro.

---

### R3-07: Silent Pairing Success and Null Dereference on Device Whitelist Overflow
* **Severity:** **Low**
* **Status:** **Resolved / Fixed**
* **Location:** [`ViewerPairingScreen.kt:355-375`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/ViewerPairingScreen.kt#L355-L375), [`strings.xml`](file:///E:/Antigravity/CamMonitor/app/src/main/res/values/strings.xml#L101)
* **Root Cause:**  
  The boolean result of `securityRepo.addPairedDevice(...)` was ignored. If the camera whitelist was full (8 cameras), it returned `false`, but the screen still navigated to `ViewerModeScreen` where `targetCamera` resolved to `null`.
* **Remediation & Applied Implementation:**  
  Checked `val added = securityRepo.addPairedDevice(...)`. If `false`, displays localized `viewer_pairing_limit_reached` message, clears OTP, stops signaling listener, and triggers `onRetry()` instead of transitioning to viewer mode. Added localized strings to both English and Traditional Chinese resources.
* **Verification:**
  - **Unit Tests:** Verified string parity via [`LocaleResourcesTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/i18n/LocaleResourcesTest.kt).
  - **Device Deployment:** Deployed and verified on Pixel 6 Pro & Pixel 8 Pro.

---

*Report updated and all 7 Round-3 vulnerabilities and bugs resolved.*

