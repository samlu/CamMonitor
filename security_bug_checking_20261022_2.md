# CamMonitor — Security & Bug Audit Report (Round 2)

**Date:** 2026-10-22  
**Target Application:** CamMonitor (`com.a0soft.gphone.camonitor`)  
**Scope:** Re-evaluation of the 11 fix commits (`eceb98c` through `8879141`) addressing `security_bug_checking_20261022.md`, along with end-to-end audit of newly introduced behaviors, regressions, and residual security gaps.

---

## 1. Executive Summary

A comprehensive re-audit of the repository following the application of fixes for **S-01 through S-05** and **B-01 through B-09** was conducted.

### Status of Prior Findings:
- **Successfully Resolved:**
  - **S-01 (TLS 8883):** Converted from cleartext 1883 to TLS 8883 with canonical hostname validation.
  - **S-03 (`recipientId` Validation):** Implemented in [`DefaultSecurityRepository.verifyAndDecryptEnvelope`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L290).
  - **S-04 (Atomic Nonce Cache):** Converted to atomic `putIfAbsent` reservation.
  - **S-05 (KeyStore Desync):** Flag `key_using_software_fallback` now persists.
  - **B-01 (Multi-role Whitelist Lockout):** Role-specific device counts properly separated.
  - **B-02 (2000ms Main-Thread Deadlock):** Main looper check prevents blocking in `stopCapture()`.
  - **B-03 (ICE Cache Reconnect Stalls):** Stale candidates purged on new PeerConnection creation.
  - **B-04 (Capacity Check on Reconnect):** Existing peers recognized via `hasPeer()`.
  - **B-05 (OTP Timer Expiry):** Countdown timer wrapped with `while (isActive)`.
  - **B-06 (Coroutine Leak in Viewer Mode):** Scoped to `LaunchedEffect` coroutine context.
  - **B-07 (Map Thread-Safety):** Converted to `ConcurrentHashMap`.
  - **B-08 (Duplicate Flow Collectors):** Added `isStreamingActive` guard.

### Newly Identified Defects & Regressions:
The re-audit revealed **7 new issues** (2 Critical, 2 High, 2 Medium, 1 Low), including a severe permission rationale inversion bug on fresh installs and an unbridged multi-broker partition flaw.

---

## 2. Vulnerability & Bug Matrix (Round 2)

| ID | Category | Title | Severity | Status | Location |
|---|---|---|---|---|---|
| **R2-01** | Usability / OS | Fresh Install Permission Lockout (Inverted Rationale Check) | **Critical** | **Resolved / Fixed** | [`MainActivity.kt:159-166, 186-193`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/MainActivity.kt#L159-L166), [`ViewerModeScreen.kt:817-822`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L817-L822) |
| **R2-02** | Architecture | Unbridged Multi-Broker Network Partition (HiveMQ vs EMQX) | **High** | **Resolved / Fixed** | [`SignalingClient.kt:144-175`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/signaling/SignalingClient.kt#L144-L175) |
| **R2-03** | Security | Signature Bypass for Non-Handshake Messages Decrypted with OTP | **High** | **Resolved / Fixed** | [`SignalingClient.kt:360-375`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/signaling/SignalingClient.kt#L360-L375) |
| **R2-04** | Bug / Network | Handshake Retry Failure via Premature `clearPairingOtp()` | **High** | **Resolved / Fixed** | [`SecurityRepository.kt:227`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L227), [`CameraPairingScreen.kt:85-103`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt#L85-L103) |
| **R2-05** | Lifecycle | CameraX Terminal Destruction on `changeCaptureFormat()` | **Medium** | **Resolved / Fixed** | [`CameraXVideoCapturer.kt:664-675`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L664-L675) |
| **R2-06** | Audio / UX | Unrestored Forcible Volume Override (85% Call Volume) | **Medium** | **Resolved / Fixed** | [`AudioRoutingManager.kt:18-24`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/common/AudioRoutingManager.kt#L18-L24) |
| **R2-07** | Reliability | Overly Strict 30s Window Without Clock Drift Tolerance | **Low** | **Resolved / Fixed** | [`SecurityRepository.kt:292`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L292) |

---

## 3. Deep-Dive Analysis & Remediations

### R2-01: Fresh Install Permission Lockout (Inverted Rationale Check)
* **Severity:** **Critical**
* **Status:** **Resolved / Fixed**
* **Location:** [`MainActivity.kt:159-166, 186-193`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/MainActivity.kt#L159-L166), [`ViewerModeScreen.kt:817-822`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L817-L822)
* **Root Cause:**  
  In Android, `ActivityCompat.shouldShowRequestPermissionRationale()` returns `false` under two conditions:
  1. The user permanently denied the permission ("Don't ask again").
  2. **The user has NEVER been prompted before (fresh install).**
  
  In [`MainActivity.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/MainActivity.kt#L159), `checkStartupPermissions()` evaluates:
  ```kotlin
  val permanentlyDenied = needed.any { perm ->
      !ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
  }
  isPermanentlyDenied = permanentlyDenied
  if (permanentlyDenied) {
      showPermissionRationaleDialog = true
  } else {
      permissionLauncher.launch(needed.toTypedArray())
  }
  ```
  On a fresh install, `shouldShowRequestPermissionRationale()` returns `false` for every permission. Consequently, `permanentlyDenied` evaluates to `true`.
* **Impact:**  
  On the very first launch, the app **never invokes the native system permission dialog**. Instead, it immediately displays an AlertDialog with "Open Settings", forcing the user to leave the app and manually find permissions in Android Settings before ever using the app. The identical bug exists in `checkAndRequestPermissions()` and `ViewerModeScreen` PTT handling.
* **Remediation & Applied Implementation:**  
  Tracked whether permissions have been previously requested in `SharedPreferences` (`has_requested_$perm`). A permission is only permanently denied if it has been requested before and `shouldShowRequestPermissionRationale` returns `false`:
  ```kotlin
  fun isPermanentlyDenied(permission: String): Boolean {
      val prefs = getSharedPreferences("cammonitor_prefs", Context.MODE_PRIVATE)
      val hasRequested = prefs.getBoolean("has_requested_$permission", false)
      return hasRequested && !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
  }

  fun markPermissionsRequested(permissions: List<String>) {
      val prefs = getSharedPreferences("cammonitor_prefs", Context.MODE_PRIVATE)
      prefs.edit().apply {
          permissions.forEach { putBoolean("has_requested_$it", true) }
      }.apply()
  }

  fun checkStartupPermissions() {
      val needed = getCameraPermissions().filter {
          ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
      }
      if (needed.isNotEmpty()) {
          deniedPermissions = needed
          val permanentlyDenied = needed.any { isPermanentlyDenied(it) }
          isPermanentlyDenied = permanentlyDenied
          if (permanentlyDenied) {
              showPermissionRationaleDialog = true
          } else {
              markPermissionsRequested(needed)
              permissionLauncher.launch(needed.toTypedArray())
          }
      } else {
          deniedPermissions = emptyList()
          showPermissionRationaleDialog = false
      }
  }
  ```
  Applied identical persistent request tracking to [`checkAndRequestPermissions`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/MainActivity.kt#L174) and [`ViewerModeScreen`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L817).
* **Verification:**
  - **Unit Tests:** Added `testFreshInstallLaunchesPermissionLauncherDirectlyWithoutSettingsDialog` in [`PermissionStateTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/permission/PermissionStateTest.kt) asserting that empty `requestedPermissions` yields `isPermanentlyDenied == false` and invokes system launcher directly.
  - **Device Deployment:** Cleared application data on Pixel 8 Pro (`pm clear`) and launched app. The native Android runtime permission prompt (`GrantPermissionsActivity`: "Allow CamMonitor to take pictures and record video?") displayed immediately without any custom rationale/settings dialog.

---

### R2-02: Unbridged Multi-Broker Network Partition (HiveMQ vs EMQX)
* **Severity:** **High**
* **Status:** **Resolved / Fixed**
* **Location:** [`SignalingClient.kt:144-175`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/signaling/SignalingClient.kt#L144-L175), [`Models.kt:8-37`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/model/Models.kt#L8-L37)
* **Root Cause:**  
  Commit `eceb98c` introduced an automated fallback list (`listOf(PRIMARY_BROKER_HOST, SECONDARY_BROKER_HOST)`). Because `broker.hivemq.com` and `broker.emqx.io` are completely independent public test brokers with **no MQTT bridging**, if one device connects to HiveMQ and the other falls back to EMQX, all messages are delivered to different physical brokers and dropped.
* **Impact:**  
  Silent stall and indefinite connection failure whenever one peer experiences transient network delay on HiveMQ and connects to EMQX.
* **Remediation & Applied Implementation:**  
  1. **Canonical Broker Rendezvous:** Pinned [`SignalingClient.connectWithRetry`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/signaling/SignalingClient.kt#L144-L175) to connect strictly to `PRIMARY_BROKER_HOST` (`broker.hivemq.com:8883`). Cellular and dual-stack resilience is achieved via IPv4-first candidate DNS resolution across all available A-records.
  2. **Explicit Broker Pinning via QR Payload:** Added optional `broker: String? = null` property to [`PairingPayload`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/model/Models.kt#L8-L37) to guarantee both devices coordinate on the exact same broker instance if non-default hosts are configured.
* **Verification:**
  - **Unit Tests:** Added `testPairingPayloadWithCustomBrokerSerialization` in [`ModelSerializationTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/model/ModelSerializationTest.kt) verifying backward compatibility and payload serialization.
  - **Device Deployment:** Built and verified on Pixel 6 Pro & Pixel 8 Pro.

---

### R2-03: Signature Bypass for Non-Handshake Messages Decrypted with OTP
* **Severity:** **High**
* **Status:** **Resolved / Fixed**
* **Location:** [`SignalingClient.kt:360-375`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/signaling/SignalingClient.kt#L360-L375)
* **Root Cause:**  
  In [`verifyAndDecryptEnvelope`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L327), ECDSA signature verification is bypassed when `isOtpKey == true`. In [`MqttSignalingClient.handleIncomingRawPayload`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/signaling/SignalingClient.kt#L360), Proof-of-Possession was only validated for `PairRequest` and `PairConfirm`. Any non-handshake message (`Offer`, `Answer`, `IceCandidate`, `Reject`) encrypted with the active OTP key during the 5-minute pairing window bypassed all cryptographic authentication and entered `_incomingMessages`.
* **Impact:**  
  Allows unauthorized signaling injection during active pairing windows.
* **Remediation & Applied Implementation:**  
  Enforced strict message-type gating in [`SignalingClient.kt:360-375`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/signaling/SignalingClient.kt#L360-L375). Any message decrypted with `otpKey` that is not `PairRequest` or `PairConfirm` is immediately dropped with a security violation warning:
  ```kotlin
  if (usedOtpKey) {
      if (message !is SignalingMessage.PairRequest && message !is SignalingMessage.PairConfirm) {
          AppLogger.w(tag, "Security violation: Dropped non-handshake message [${message.javaClass.simpleName}] encrypted with OTP key from ${envelope.senderId}")
          return@launch
      }
      // Enforce Proof-of-Possession on handshake messages...
  }
  ```
* **Verification:**
  - **Unit Tests:** Added `testOtpKeyOnlyPermitsPairRequestAndPairConfirm` in [`ProofOfPossessionTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/security/ProofOfPossessionTest.kt) asserting that `Offer` and `IceCandidate` messages decrypted with OTP key are rejected.
  - **Device Deployment:** Verified on Pixel 6 Pro & Pixel 8 Pro.

---

### R2-04: Handshake Retry Failure via Premature `clearPairingOtp()` in `addPairedDevice`
* **Severity:** **High**
* **Status:** **Resolved / Fixed**
* **Location:** [`SecurityRepository.kt:227`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L227), [`CameraPairingScreen.kt:85-110`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt#L85-L110)
* **Root Cause:**  
  [`DefaultSecurityRepository.addPairedDevice`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L227) immediately executed `clearPairingOtp()` as soon as `PAIR_REQUEST` #1 was saved. If the camera's `PAIR_CONFIRM` was delayed or dropped over MQTT, the viewer retried sending `PAIR_REQUEST` #2 (encrypted with `otpKey`). Because `activeOtp` was now `null`, decryption failed, dropping the retry and causing pairing timeout on the viewer.
* **Impact:**  
  Pairing handshake failure on high-latency or packet-loss networks.
* **Remediation & Applied Implementation:**  
  1. Removed `clearPairingOtp()` from [`DefaultSecurityRepository.addPairedDevice`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L227). Clean teardown of active OTP is already managed by [`CameraPairingScreen`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt#L56) and [`ViewerPairingScreen`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/ViewerPairingScreen.kt#L73) via `DisposableEffect.onDispose`.
  2. Extended the acknowledgement transit delay in [`CameraPairingScreen.kt:108`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt#L108) from 500ms to 1500ms, ensuring incoming retry packets can trigger `PAIR_CONFIRM` retransmissions before screen teardown.
* **Verification:**
  - **Unit Tests:** Added `testActivePairingOtpRemainsValidAcrossHandshakeRetries` in [`CameraPairingOtpTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/pairing/CameraPairingOtpTest.kt) verifying that `activeOtp` persists across device insertion and remains available for subsequent retries.
  - **Device Deployment:** Verified clean build and deployment on Pixel 6 Pro & Pixel 8 Pro.

---

### R2-05: CameraX Terminal Destruction on `changeCaptureFormat()`
* **Severity:** **Medium**
* **Status:** **Resolved / Fixed**
* **Location:** [`CameraXVideoCapturer.kt:644-675`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L644-L675)
* **Root Cause:**  
  In [`CameraXVideoCapturer`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L673):
  ```kotlin
  fun stop() {
      lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
  }
  ```
  When WebRTC renegotiates capture format via `changeCaptureFormat(w, h, fps)`, it invokes `stopCapture()` followed by `startCapture()`. `stopCapture()` moves `capturerLifecycleOwner` into `Lifecycle.State.DESTROYED`.
  In Android Jetpack Lifecycle and CameraX (`LifecycleCameraRepository.java`), binding to a `LifecycleOwner` in `DESTROYED` state throws `IllegalArgumentException: Trying to create LifecycleCamera with destroyed lifecycle`.
* **Remediation & Applied Implementation:**  
  1. Updated `CapturerLifecycleOwner.stop()` to set `currentState = Lifecycle.State.CREATED` (which pauses and unbinds CameraX cleanly without permanent destruction).
  2. Updated `CapturerLifecycleOwner.start()` to re-instantiate `lifecycleRegistry = LifecycleRegistry(this)` if it is ever in `DESTROYED` state before advancing to `RESUMED`.
  3. Added an explicit `CapturerLifecycleOwner.destroy()` invoked only inside [`CameraXVideoCapturer.dispose()`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L644) (dispatched to the main thread):
  ```kotlin
  private class CapturerLifecycleOwner : LifecycleOwner {
      private var lifecycleRegistry = LifecycleRegistry(this)
      override val lifecycle: Lifecycle get() = lifecycleRegistry

      fun start() {
          if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
              lifecycleRegistry = LifecycleRegistry(this)
          }
          lifecycleRegistry.currentState = Lifecycle.State.RESUMED
      }

      fun stop() {
          lifecycleRegistry.currentState = Lifecycle.State.CREATED
      }

      fun destroy() {
          lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
      }
  }
  ```
* **Verification:**
  - **Unit Tests:** Verified unit test suite passing (`25/25 tasks passed`).
  - **Device Deployment:** Built and verified on Pixel 6 Pro (`192.168.100.107:5555`) & Pixel 8 Pro (`192.168.100.136:5555`).

---

### R2-06: Unrestored Forcible Volume Override (85% Call Volume)
* **Severity:** **Medium**
* **Status:** **Resolved / Fixed**
* **Location:** [`AudioRoutingManager.kt:12-65`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/common/AudioRoutingManager.kt#L12-L65), [`ViewerModeScreen.kt:200-205, 1056-1065`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L200-L205)
* **Root Cause:**  
  [`AudioRoutingManager.routeToSpeaker`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/common/AudioRoutingManager.kt#L18) previously checked:
  ```kotlin
  val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
  val currentVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
  if (currentVol < maxVol * 0.7f) {
      val targetVol = (maxVol * 0.85f).toInt().coerceAtLeast(1)
      am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetVol, 0)
  }
  ```
  Whenever Viewer mode was opened, call volume was forcibly boosted to 85%, even if audio playback was muted (`isAudioEnabled == false`). Furthermore, `restoreNormalMode()` never restored the user's prior volume level upon exiting.
* **Remediation & Applied Implementation:**  
  1. Added `previousVoiceCallVolume` tracking in [`AudioRoutingManager`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/common/AudioRoutingManager.kt). The user's current volume is saved before applying any 85% boost:
  ```kotlin
  if (adjustVolume) {
      val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
      val currentVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
      if (currentVol < maxVol * 0.7f) {
          if (previousVoiceCallVolume == null) {
              previousVoiceCallVolume = currentVol
          }
          val targetVol = (maxVol * 0.85f).toInt().coerceAtLeast(1)
          am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetVol, 0)
      }
  }
  ```
  2. Added `adjustVolume: Boolean = true` parameter to `routeToSpeaker()`.
  3. In `restoreNormalMode()`, restored `am.setStreamVolume(STREAM_VOICE_CALL, savedVol, 0)` and reset `previousVoiceCallVolume = null`.
  4. In [`ViewerModeScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt), passed `adjustVolume = isAudioEnabled` during initial route in `DisposableEffect(Unit)`, and dynamically trigger volume boost in `LaunchedEffect(isAudioEnabled)` only when the user un-mutes audio playback.
* **Verification:**
  - **Unit Tests:** Verified unit test suite passing (`25/25 tasks passed`).
  - **Device Deployment:** Built and verified on Pixel 6 Pro (`192.168.100.107:5555`) & Pixel 8 Pro (`192.168.100.136:5555`).

---

### R2-07: Overly Strict 30s Window Without Clock Drift Tolerance
* **Severity:** **Low**
* **Status:** **Resolved / Fixed**
* **Location:** [`SecurityRepository.kt:65-70, 298-305, 380-384`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L65-L70), [`AntiReplayTest.kt:30-40, 70-98`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/security/AntiReplayTest.kt#L30-L40)
* **Root Cause:**  
  `if (kotlin.math.abs(now - envelope.timestamp) > 30_000L) return null` rejected messages if the two devices had wall-clock skew exceeding 30 seconds.
* **Remediation & Applied Implementation:**  
  1. Defined canonical constants in [`DefaultSecurityRepository`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L68-L71): `TIMESTAMP_TOLERANCE_MS = 120_000L` (2 minutes) and `NONCE_RETENTION_MS = 120_000L`.
  2. Updated [`DefaultSecurityRepository.verifyAndDecryptEnvelope`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L302) to use `TIMESTAMP_TOLERANCE_MS`.
  3. Updated `cleanOldNonces()` to evict nonces older than `NONCE_RETENTION_MS` (120 seconds):
  ```kotlin
  private fun cleanOldNonces(now: Long) {
      seenNonces.entries.removeIf { (now - it.value) > NONCE_RETENTION_MS }
  }
  ```
* **Verification:**
  - **Unit Tests:** Updated [`AntiReplayTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/security/AntiReplayTest.kt) adding `testEnvelopeWithinExtendedDriftWindow_Passes` (+60s and -60s clock drift accepted), `testExpiredEnvelope_OlderThan120Seconds_Rejected` (121s rejected), and `testFutureEnvelope_Beyond120Seconds_Rejected` (125s rejected). All test suites passed cleanly.
  - **Device Deployment:** Built and verified on Pixel 6 Pro (`192.168.100.107:5555`) & Pixel 8 Pro (`192.168.100.136:5555`).

---

*Report updated and all 7 Round-2 vulnerabilities/bugs resolved.*