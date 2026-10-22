# CamMonitor — Security & Bug Audit Report

**Date:** 2026-10-22  
**Target Application:** CamMonitor (`com.a0soft.gphone.camonitor`)  
**Version:** 1.0.3 (`versionCode = 4`)  
**Audit Scope:** Full codebase audit covering cryptographic design, signaling security, WebRTC lifecycle, CameraX capturer, concurrency, and Android platform integrations.

---

## 1. Executive Summary

A comprehensive source code review of CamMonitor was conducted across networking, cryptography, Android services, and UI flows. While the core architecture leverages modern standards (ECDSA secp256r1, HKDF-SHA256, AES-256-GCM, WebRTC Unified Plan, and Jetpack Compose), **5 security vulnerabilities** and **9 functional/concurrency bugs** were identified.

Two critical defects cause immediate functional failures under standard usage:
1. **Multi-Role Whitelist Lockout (B-01):** Prevents a device that previously paired as a viewer from ever pairing as a camera due to global rather than role-scoped count limits.
2. **Main-Thread Deadlock in Camera Stop (B-02):** Causes a guaranteed 2000ms UI freeze / ANR every time camera capture pauses or stops.

Additionally, MQTT signaling currently operates over unencrypted TCP port 1883 instead of TLS port 8883 (violating [Specification.md](file:///E:/Antigravity/CamMonitor/Specification.md#L20)), and proof-of-possession verification is omitted during viewer enrollment.

---

## 2. Vulnerability & Bug Matrix

| ID | Category | Title | Severity | Location |
|---|---|---|---|---|
| **S-01** | Security | Plaintext Unencrypted MQTT Signaling over Port 1883 | **Fixed** (High) | [`SignalingClient.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/signaling/SignalingClient.kt#L56-L61) |
| **S-02** | Security | Missing Proof-of-Possession in `PAIR_REQUEST` Handshake | **Fixed** (High) | [`SecurityRepository.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L45-L55), [`SignalingClient.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/signaling/SignalingClient.kt#L360-L380) |
| **S-03** | Security | Missing `recipientId` Validation in Envelope Verification | **Fixed** (Medium) | [`SecurityRepository.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L290-L294) |
| **S-04** | Security | Non-Atomic Nonce Cache Race Condition | **Fixed** (Medium) | [`SecurityRepository.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L304-L348) |
| **S-05** | Security | Key Pair Desync on Android 12+ KeyStore Fallback | **Fixed** (Medium) | [`SecurityRepository.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L115-L132) |
| **B-01** | Bug | Multi-Role Whitelist Count Lockout | **Fixed** (Critical) | [`SecurityRepository.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L212-L225), [`ManageDevicesScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/devices/ManageDevicesScreen.kt#L41-L42) |
| **B-02** | Concurrency | Main-Thread Deadlock / 2000ms Freeze in `stopCapture()` | **Fixed** (Critical) | [`CameraXVideoCapturer.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L597-L634) |
| **B-03** | Bug | WebRTC Reconnection Stalls via Stale Duplicate ICE Cache | **Fixed** (High) | [`WebRtcManager.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L746-L758) |
| **B-04** | Bug | Reconnecting Viewer Rejected via Stale Capacity Check | **Fixed** (High) | [`CameraMonitorService.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L306-L311), [`WebRtcManager.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L745) |
| **B-05** | Bug | Ephemeral OTP Timer Dies After Initial 5 Minutes | **Fixed** (Medium) | [`CameraPairingScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt#L60-L71) |
| **B-06** | Concurrency | Coroutine Leak in Viewer Mode Retries | **Fixed** (Medium) | [`ViewerModeScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L163-L320) |
| **B-07** | Concurrency | Unsynchronized Maps in `WebRtcManager` Callback Threads | **Fixed** (Medium) | [`WebRtcManager.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L37-L38), [`#L66`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L66) |
| **B-08** | Bug | Duplicate Flow Collectors on Service Re-Entry | **Fixed** (Medium) | [`CameraMonitorService.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L49-L220) |
| **B-09** | Reliability | Silent Permission Denial in `MainActivity` | **Low** | [`MainActivity.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/MainActivity.kt#L39-L43) |

---

## 3. Detailed Security Vulnerabilities

### S-01: Plaintext Unencrypted MQTT Signaling over Port 1883
* **Location:** [`SignalingClient.kt:56-61, 144-225`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/signaling/SignalingClient.kt#L56-L61)
* **Status:** **Resolved / Fixed**
* **Description:** [Specification.md Section 1.20](file:///E:/Antigravity/CamMonitor/Specification.md#L20) specifies TLS-encrypted signaling on port 8883 (`broker.hivemq.com:8883`). However, the implementation previously connected to port 1883 with `useSsl = false`.
* **Impact:** Observers on local Wi-Fi, ISP transit paths, or public MQTT brokers could sniff plaintext MQTT headers, topics (`cammonitor/<deviceId>/rx`), device IDs, timestamps, connection intervals, and packet metadata.
* **Remediation & Analysis:**
  * **Critical SNI / Hostname Verification Pitfall:** Simply changing the port to `8883` and setting `useSsl = true` when calling `attemptConnect(targetHost, ...)` fails when `targetHost` is an IP address returned by `resolveHostCandidates(PRIMARY_BROKER_HOST)`. When HiveMQ connects to a raw IP via `.serverHost(ip)` with default SSL config, Java's default `HostnameVerifier` checks the certificate SANs against the IP address, throwing `SSLHandshakeException: No subject alternative names matching IP address <IP> found` (the public cert covers `*.hivemq.com` and `broker.hivemq.com`, not dynamic cloud IPs).
  * **Resolution:**
    1. Switched primary broker to port `8883` (`broker.hivemq.com:8883`) with TLS enabled (`useSsl = true`).
    2. Added secondary broker fallback (`broker.emqx.io:8883`) with TLS enabled per [Specification.md Section 1.20](file:///E:/Antigravity/CamMonitor/Specification.md#L20).
    3. Configured `MqttClientSslConfig` with a canonical `HostnameVerifier` validating the peer certificate against the canonical hostname (`expectedHost`) rather than the raw IP candidate, ensuring dual-stack IP resolution and TLS verification work simultaneously without certificate mismatches.
* **Applied Implementation:**
  ```kotlin
  // In SignalingClient.kt
  companion object {
      const val PRIMARY_BROKER_HOST = "broker.hivemq.com"
      const val PRIMARY_BROKER_PORT = 8883
      const val SECONDARY_BROKER_HOST = "broker.emqx.io"
      const val SECONDARY_BROKER_PORT = 8883
  }

  // In attemptConnect:
  val newClient = if (useSsl) {
      val sslConfig = MqttClientSslConfig.builder()
          .hostnameVerifier { _, session ->
              HttpsURLConnection.getDefaultHostnameVerifier().verify(expectedHost, session)
          }
          .build()
      builder.sslConfig(sslConfig).buildAsync()
  } else {
      builder.buildAsync()
  }
  ```
* **Verification:**
  - **Unit Tests:** `testBrokerTlsConfiguration` in [`SignalingStateTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/signaling/SignalingStateTest.kt) passed.
  - **Live Device Run (Pixel 6 Pro & Pixel 8 Pro):** Verified TLS handshake and subscription in < 2 seconds:
    ```log
    I MqttSignalingClient: Attempting TLS connection to broker.hivemq.com:8883 (candidates=[54.93.55.163, 63.177.46.210], gen=1)...
    I MqttSignalingClient: MQTT connected callback (gen=1, host=54.93.55.163)
    I MqttSignalingClient: Subscribed successfully to cammonitor/4d1a5e23-16e1-49e8-81e7-307142198f4c/rx on 54.93.55.163:8883 (gen=1)
    I MqttSignalingClient: Successfully connected via TLS to 54.93.55.163:8883 (host=broker.hivemq.com, gen=1)
    ```

---

### S-02: Missing Proof-of-Possession in `PAIR_REQUEST` Handshake
* **Location:** [`SecurityRepository.kt:45-55`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L45-L55), [`SignalingClient.kt:360-380`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/signaling/SignalingClient.kt#L360-L380), [`CameraPairingScreen.kt:74-78`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt#L74-L78), [`ViewerPairingScreen.kt:330-334`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/ViewerPairingScreen.kt#L330-L334)
* **Status:** **Resolved / Fixed**
* **Description:** In [`DefaultSecurityRepository.verifyAndDecryptEnvelope`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L301), signature verification was bypassed when `isOtpKey == true` because the sender was not yet known in the whitelist. When receiving a `PairRequest`, the camera saved `msg.pbPublicKeyBase64` to the device whitelist without validating whether `envelope.signature` was generated by that public key.
* **Impact:** An attacker observing or brute-forcing an OTP could supply an arbitrary public key without owning the corresponding private key, subverting mutual authentication.
* **Remediation & Analysis:**
  * **Architectural Pitfall in Proposed Snippet:** The initial audit recommendation suggested adding signature verification inside `CameraPairingScreen.kt` using `envelope.signature` and `envelope.ciphertext`. In reality, `signalingClient.incomingMessages` emits `Pair<String, SignalingMessage>`; `SignalingEnvelope` is decrypted and unpacked inside `SignalingClient.kt` and is not exposed to UI screens. Furthermore, placing verification in `CameraPairingScreen` would leave `PAIR_CONFIRM` unverified on the Viewer side.
  * **Resolution:**
    1. Added `verifyEnvelopeSignature(envelope, publicKeyBase64)` to [`SecurityRepository.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L45-L55) to verify ECDSA signatures over `"$senderId$recipientId$timestamp$nonce$ciphertext"`.
    2. Enforced Proof-of-Possession in [`SignalingClient.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/signaling/SignalingClient.kt#L360-L380) right after OTP decryption for both `PAIR_REQUEST` (Camera) and `PAIR_CONFIRM` (Viewer). Validates:
       - `envelope.senderId == claimedDeviceId`
       - `securityRepository.verifyEnvelopeSignature(envelope, claimedPubKey)`
       - Rejects and logs warnings for any forged or mismatched handshake before emitting to `incomingMessages`.
    3. Added defense-in-depth device ID assertions in [`CameraPairingScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt#L74-L78) and [`ViewerPairingScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/ViewerPairingScreen.kt#L330-L334).
* **Applied Implementation:**
  ```kotlin
  // In SecurityRepository.kt:
  fun verifyEnvelopeSignature(envelope: SignalingEnvelope, publicKeyBase64: String): Boolean {
      return try {
          val pubKey = CryptoUtils.publicKeyFromBase64(publicKeyBase64)
          val sigPayload = "${envelope.senderId}${envelope.recipientId}${envelope.timestamp}${envelope.nonce}${envelope.ciphertext}"
              .toByteArray(Charsets.UTF_8)
          CryptoUtils.verify(sigPayload, envelope.signature, pubKey)
      } catch (_: Exception) {
          false
      }
  }

  // In SignalingClient.kt (handleIncomingRawPayload):
  if (usedOtpKey && (message is SignalingMessage.PairRequest || message is SignalingMessage.PairConfirm)) {
      val (claimedDeviceId, claimedPubKey) = when (message) {
          is SignalingMessage.PairRequest -> message.pbDeviceId to message.pbPublicKeyBase64
          is SignalingMessage.PairConfirm -> message.paDeviceId to message.paPublicKeyBase64
      }
      if (claimedDeviceId != envelope.senderId) {
          AppLogger.w(tag, "Handshake dropped: deviceId mismatch (envelope=${envelope.senderId}, claimed=$claimedDeviceId)")
          return@launch
      }
      if (claimedPubKey.isNullOrEmpty() || !securityRepository.verifyEnvelopeSignature(envelope, claimedPubKey)) {
          AppLogger.w(tag, "Handshake dropped: Proof-of-Possession signature verification failed for ${envelope.senderId}")
          return@launch
      }
      AppLogger.i(tag, "Proof-of-Possession verified for ${message.javaClass.simpleName} from ${envelope.senderId}")
  }
  ```
* **Verification:**
  - **Unit Tests:** Created [`ProofOfPossessionTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/security/ProofOfPossessionTest.kt):
    - `testProofOfPossessionValidSignatureAccepted`: Verifies valid Proof-of-Possession handshakes pass.
    - `testProofOfPossessionSpoofedPublicKeyRejected`: Verifies attacker signing with their own key while specifying victim's public key is rejected.
    - `testProofOfPossessionCorruptedSignatureRejected`: Verifies tampered signatures are rejected.
  - **Device Deployment:** Tested and installed debug APK on both Pixel 6 Pro & Pixel 8 Pro.

---

### S-03: Missing `recipientId` Validation in Envelope Verification
* **Location:** [`SecurityRepository.kt:290-294`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L290-L294)
* **Status:** **Resolved / Fixed**
* **Description:** While `recipientId` was included in the signed payload, `verifyAndDecryptEnvelope` never asserted `envelope.recipientId == this.deviceId`.
* **Impact:** Envelopes reflected by the broker, cross-routed across topics, or replayed between multiple paired cameras would be accepted as long as the key matched.
* **Remediation & Applied Implementation:**
  Asserted recipient equality before processing anti-replay timestamps or nonces:
  ```kotlin
  // 0. Recipient ID Check (S-03)
  if (envelope.recipientId != deviceId) {
      AppLogger.w("SecurityRepository", "Envelope discarded: recipientId mismatch (${envelope.recipientId} != $deviceId)")
      return null
  }
  ```
* **Verification:**
  - **Unit Tests:** Added `testRecipientIdMismatch_Rejected` in [`AntiReplayTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/security/AntiReplayTest.kt).
  - **Device Deployment:** Built and verified on Pixel 6 Pro & Pixel 8 Pro.

---

### S-04: Non-Atomic Nonce Cache Race Condition
* **Location:** [`SecurityRepository.kt:304-348`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L304-L348)
* **Status:** **Resolved / Fixed**
* **Description:** The anti-replay check used a check-then-act pattern across separate lines:
  ```kotlin
  if (seenNonces.containsKey(envelope.nonce)) return null
  // ... verify and decrypt ...
  seenNonces[envelope.nonce] = envelope.timestamp
  ```
  Incoming MQTT messages are dispatched concurrently on `Dispatchers.IO`. Two identical messages arriving in close succession evaluated `containsKey == false`, allowing duplicate processing.
* **Remediation & Applied Implementation:**
  Atomically claimed the nonce using `putIfAbsent`. If whitelist check, signature verification, or decryption subsequently fails, released the reservation via `seenNonces.remove(envelope.nonce)`:
  ```kotlin
  // 2. Anti-Replay Nonce Check (S-04: Atomic reservation)
  cleanOldNonces(now)
  if (seenNonces.putIfAbsent(envelope.nonce, envelope.timestamp) != null) {
      AppLogger.d("SecurityRepository", "Envelope duplicate nonce (${envelope.nonce}) discarded (sender: ${envelope.senderId})")
      return null
  }
  ...
  // On signature/decryption failure:
  seenNonces.remove(envelope.nonce)
  ```
* **Verification:**
  - **Unit Tests:** Added `testConcurrentDuplicateNonce_AtomicDeduplication` in [`AntiReplayTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/security/AntiReplayTest.kt) running 8 concurrent threads contending for the same nonce; exactly 1 claimed the nonce successfully.
  - **Device Deployment:** Built and verified on Pixel 6 Pro & Pixel 8 Pro.

---

### S-05: Key Pair Desync on Android 12+ KeyStore Fallback
* **Location:** [`SecurityRepository.kt:115-132`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L115-L132)
* **Status:** **Resolved / Fixed**
* **Description:** If a device running Android 12+ failed ECDH in KeyStore during initialization (e.g. OEM-specific driver flaws with `PURPOSE_AGREE_KEY`), it fell back to software keys saved in `prefs`. On next launch, `SecurityRepository` checked `SDK_INT >= S` first and attempted KeyStore creation again. If KeyStore succeeded on reboot, the public key changed, breaking existing pairings.
* **Remediation & Applied Implementation:**
  Recorded `key_using_software_fallback = true` in `prefs` upon falling back, and checked `!usingSoftwareFallback` before attempting Android 12+ KeyStore key retrieval:
  ```kotlin
  val usingSoftwareFallback = prefs.getBoolean("key_using_software_fallback", false)
  keyPair = try {
      if (!usingSoftwareFallback && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
          CryptoUtils.getOrCreateDeviceKeyPair(alias)
      } else {
          getOrCreateSoftwareKeyPair()
      }
  } catch (e: Exception) {
      AppLogger.w("SecurityRepository", "KeyStore ECDH unavailable, using persistent software key: ${e.message}")
      prefs.edit().putBoolean("key_using_software_fallback", true).apply()
      getOrCreateSoftwareKeyPair()
  }
  ```
* **Verification:**
  - **Unit Tests:** Added `testSoftwareFallbackKeyPersistence` in [`AntiReplayTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/security/AntiReplayTest.kt) verifying that software fallback flag suppresses future KeyStore re-generation across simulated reboots.
  - **Device Deployment:** Built and verified on Pixel 6 Pro & Pixel 8 Pro.

---

## 4. Detailed Functional Bugs & Concurrency Issues

### B-01: Multi-Role Whitelist Count Lockout (Critical)
* **Location:** [`SecurityRepository.kt:212-225`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L212-L225), [`ManageDevicesScreen.kt:41-42`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/devices/ManageDevicesScreen.kt#L41-L42)
* **Status:** **Resolved / Fixed**
* **Description:** `addPairedDevice` previously checked the total whitelist size across all devices instead of role-scoped counts:
  ```kotlin
  val current = getPairedDevices().toMutableList()
  if (current.size >= maxLimit && current.none { it.deviceId == device.deviceId }) {
      return false
  }
  ```
  If a phone had paired with 2 cameras as a viewer (`current.size == 2`) and was then switched to Camera mode to pair a viewer (`maxLimit = MAX_PAIRED_VIEWERS = 2`), `current.size >= 2` evaluated to `true`. The device rejected all viewer pairing attempts. Similarly, `ManageDevicesScreen.kt` checked `pairedList.size` against role limits rather than role-specific device counts.
* **Remediation & Applied Implementation:**
  1. Updated [`DefaultSecurityRepository.addPairedDevice`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L212-L225) to scope whitelist count checks to `device.role`:
     ```kotlin
     val roleCount = when (device.role) {
         "CAMERA" -> current.count { it.role != "VIEWER" }
         "VIEWER" -> current.count { it.role != "CAMERA" }
         else -> current.size
     }
     if (roleCount >= maxLimit && current.none { it.deviceId == device.deviceId }) {
         AppLogger.w("SecurityRepository", "Cannot add paired device: Max $maxLimit devices reached for role ${device.role ?: "ALL"}")
         return false
     }
     ```
  2. Updated [`ManageDevicesScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/devices/ManageDevicesScreen.kt#L41-L42) to calculate pairing availability using role-filtered queries:
     ```kotlin
     val canPairViewer = pairedList.count { it.role != "CAMERA" } < SecurityRepository.MAX_PAIRED_VIEWERS
     val canPairCamera = pairedList.count { it.role != "VIEWER" } < SecurityRepository.MAX_PAIRED_CAMERAS
     ```
* **Verification:**
  - **Unit Tests:** Updated fake repository in [`MultiCameraPairingTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/security/MultiCameraPairingTest.kt#L29-L36) and added `testMultiRolePairingCoexistence()` verifying that 8 cameras and 2 viewers can pair and coexist concurrently, reject their respective 9th/3rd additions, and allow in-place device updates.
  - **Device Deployment:** Built and verified on Pixel 6 Pro & Pixel 8 Pro.

---

### B-02: Main-Thread Deadlock / 2000ms Freeze in `stopCapture()` (Critical)
* **Location:** [`CameraXVideoCapturer.kt:597-634`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L597-L634)
* **Status:** **Resolved / Fixed**
* **Description:**
  ```kotlin
  val latch = CountDownLatch(1)
  mainHandler.post {
      try { ... } finally { latch.countDown() }
  }
  latch.await(2, TimeUnit.SECONDS)
  ```
  [`CameraMonitorService`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L216) executes `stopCameraCapture()` on `Dispatchers.Main` when the 60-second inactivity timer fires or when service stops. Because `stopCapture()` was called directly on the Main Thread, `latch.await(2, TimeUnit.SECONDS)` blocked the Main Looper, preventing the posted block from executing. The UI was blocked for 2.0 seconds until `await()` timed out.
* **Remediation & Applied Implementation:**
  Moved stop operations into a dedicated `stopRunnable` and branched on `Looper.myLooper() == Looper.getMainLooper()`. When called from the Main thread, it executes synchronously with 0ms delay. When invoked from a background thread, it posts to `mainHandler` and awaits up to 2 seconds with `InterruptedException` handling:
  ```kotlin
  val stopRunnable = Runnable {
      try {
          capturerLifecycleOwner.stop()
          cameraProvider?.unbindAll()
          camera = null
          preview = null
          surfaceTextureHelper?.stopListening()
          surface?.release()
          surface = null
          _isNightModeActiveFlow.value = false
          onNightModeChanged?.invoke(false)
          capturerObserver?.onCapturerStopped()
          AppLogger.i(tag, "CameraX capture stopped cleanly")
      } catch (e: Exception) {
          AppLogger.e(tag, "Error stopping CameraX capture", e)
      }
  }

  if (Looper.myLooper() == Looper.getMainLooper()) {
      stopRunnable.run()
  } else {
      val latch = CountDownLatch(1)
      mainHandler.post {
          try {
              stopRunnable.run()
          } finally {
              latch.countDown()
          }
      }
      try {
          latch.await(2, TimeUnit.SECONDS)
      } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
      }
  }
  ```
* **Verification:**
  - **Unit Tests:** `testDebugUnitTest` passed cleanly.
  - **Device Deployment:** Installed and validated on Pixel 6 Pro & Pixel 8 Pro.

---

### B-03: WebRTC Reconnection Stalls via Stale Duplicate ICE Cache
* **Location:** [`WebRtcManager.kt:746-758`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L746-L758)
* **Status:** **Resolved / Fixed**
* **Description:** [`createPeerConnection`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L395) calls `closePeer(peerId, keepPendingCandidates = true)`. Previously, when `keepPendingCandidates == true`, `addedRemoteIceCandidates.remove(peerId)` was skipped. On reconnection over the same network interface, WebRTC generates candidates with identical host IP, port, and SDP strings. In `addRemoteIceCandidate`, `seen.add(candidateKey)` evaluated to `false`, silently dropping all candidates for the new session and causing reconnection to stall indefinitely.
* **Remediation & Applied Implementation:**
  Updated [`WebRtcManager.closePeer`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L742-L758). When `keepPendingCandidates == true`, stale keys from older connections are now purged. If candidates were buffered in `pendingRemoteIceCandidates` right before connection recreation, `addedRemoteIceCandidates` is synchronized to contain only those pending keys; otherwise it is wiped clean:
  ```kotlin
  fun closePeer(peerId: String, keepPendingCandidates: Boolean = false) {
      if (!keepPendingCandidates) {
          pendingRemoteIceCandidates.remove(peerId)
          addedRemoteIceCandidates.remove(peerId)
      } else {
          val pending = pendingRemoteIceCandidates[peerId]
          if (pending.isNullOrEmpty()) {
              addedRemoteIceCandidates.remove(peerId)
          } else {
              val freshSet = java.util.Collections.synchronizedSet(
                  pending.map { "${it.sdpMid}:${it.sdpMLineIndex}:${it.sdp}" }.toMutableSet()
              )
              addedRemoteIceCandidates[peerId] = freshSet
          }
      }
      dataChannels.remove(peerId)?.dispose()
      peerConnections.remove(peerId)?.dispose()
      remoteVideoTrack?.removeSink(remoteVideoSink)
      remoteVideoTrack = null
      remoteAudioTracks.remove(peerId)
      if (remoteAudioTrack == null || remoteAudioTracks.isEmpty()) {
          remoteAudioTrack = null
      }
      updateActiveViewersCount()
      AppLogger.i(tag, "Closed WebRTC connection with $peerId")
  }
  ```
* **Verification:**
  - **Unit Tests:** Created [`WebRtcIceCacheTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/webrtc/WebRtcIceCacheTest.kt) verifying that:
    - Intra-session candidate duplicates are dropped.
    - Inter-session identical candidates are accepted across reconnects.
    - Early-arrived pending candidates retain deduplication keys while stale session candidates are purged.
  - **Device Deployment:** Built and verified on Pixel 6 Pro & Pixel 8 Pro.

---

### B-04: Reconnecting Viewer Rejected via Stale Capacity Check
* **Location:** [`CameraMonitorService.kt:306-311`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L306-L311), [`WebRtcManager.kt:745`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L745)
* **Status:** **Resolved / Fixed**
* **Description:**
  ```kotlin
  if (app.webRtcManager.activeViewersCount.value >= 2) {
      sendSignalingMessageToViewer(senderId, SignalingMessage.Reject("MAX_CAPACITY_REACHED"))
      return
  }
  ```
  If Viewer 1 disconnected briefly and reconnected, its prior WebRTC peer connection often still registered as `CONNECTED` for several seconds before native ICE timeout. The camera dropped Viewer 1 with `MAX_CAPACITY_REACHED`.
* **Remediation & Applied Implementation:**
  1. Added [`WebRtcManager.hasPeer(peerId)`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L745) to detect if an allocated peer connection already exists for `peerId`.
  2. In [`CameraMonitorService.handleRemoteOffer`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L306-L311), bypass the capacity rejection when `hasPeer(senderId)` is true, allowing the reconnecting peer's prior connection to be cleanly replaced:
     ```kotlin
     val isExistingPeer = app.webRtcManager.hasPeer(senderId)
     if (app.webRtcManager.activeViewersCount.value >= 2 && !isExistingPeer) {
         AppLogger.w(tag, "Rejecting connection from $senderId: Max 2 viewers reached")
         sendSignalingMessageToViewer(senderId, SignalingMessage.Reject("MAX_CAPACITY_REACHED"))
         return
     }
     ```
* **Verification:**
  - **Unit Tests:** Added `testReconnectingViewerBypassesCapacityRejection` in [`CameraStandbyStateTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/service/CameraStandbyStateTest.kt#L152-L170).
  - **Device Deployment:** Built and verified on Pixel 6 Pro & Pixel 8 Pro.

---

### B-05: Ephemeral OTP Timer Dies After Initial 5 Minutes
* **Location:** [`CameraPairingScreen.kt:60-71`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt#L60-L71)
* **Status:** **Resolved / Fixed**
* **Description:**
  ```kotlin
  LaunchedEffect(Unit) {
      while (secondsLeft > 0) {
          delay(1000L)
          secondsLeft--
      }
      otp = securityRepo.generatePairingOtp()
      secondsLeft = 300
  }
  ```
  When `secondsLeft` reached 0 after the first 5 minutes, it regenerated the OTP once, reset `secondsLeft = 300`, and the `LaunchedEffect` coroutine completed permanently. For all subsequent time, `secondsLeft` was frozen at 300 ("05:00"), the timer never ticked again, and once the second OTP expired in [`SecurityRepository`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L187), the displayed QR code became permanently invalid.
* **Remediation & Applied Implementation:**
  Wrapped the countdown loop inside an outer `while (isActive)` loop so the countdown decrements continuously across all subsequent 5-minute cycles:
  ```kotlin
  LaunchedEffect(Unit) {
      while (isActive) {
          while (secondsLeft > 0) {
              delay(1000L)
              secondsLeft--
          }
          // Regenerate OTP if expired and restart 5-minute countdown
          otp = securityRepo.generatePairingOtp()
          secondsLeft = 300
      }
  }
  ```
* **Verification:**
  - **Unit Tests:** Created [`CameraPairingOtpTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/pairing/CameraPairingOtpTest.kt) verifying the countdown coroutine remains active and decrements continuously across multiple 5-minute cycles (T = 300s, T = 600s), generating new OTPs each cycle.
  - **Device Deployment:** Built and verified on Pixel 6 Pro & Pixel 8 Pro.

---

### B-06: Coroutine Leak in Viewer Mode Retries
* **Location:** [`ViewerModeScreen.kt:163-320`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L163-L320)
* **Status:** **Resolved / Fixed**
* **Description:** Inside `LaunchedEffect(targetCamera, reconnectTrigger)`, asynchronous jobs for periodic offer retries (`retryJob`), initial offer dispatch, and ICE candidate trickles were launched using `coroutineScope.launch { ... }` instead of the `LaunchedEffect` coroutine scope. When `reconnectTrigger` incremented or `targetCamera` changed, Compose cancelled the `LaunchedEffect`, but the old `retryJob` remained active on the screen-level scope, continuing to loop for up to 45 seconds sending outdated offers and candidates from dead peer connections.
* **Remediation & Applied Implementation:**
  Scoped all session-bound child jobs within `LaunchedEffect(targetCamera, reconnectTrigger)` to `effectScope = this` rather than `coroutineScope`:
  ```kotlin
  LaunchedEffect(targetCamera, reconnectTrigger) {
      val effectScope = this
      ...
      val pc = webRtcManager.createPeerConnection(targetCamera.deviceId) { candidate ->
          ...
          effectScope.launch {
              signalingClient.sendSignalingMessage(targetCamera.deviceId, iceMsg, sharedKey)
          }
      }
      ...
      val retryJob = effectScope.launch {
          var attempt = 1
          while (isActive && pc.remoteDescription == null) {
              delay(3000L)
              ...
          }
      }
  ```
  When `reconnectTrigger` increments or the screen is disposed, `effectScope` cancellation immediately kills the retry loop and pending trickles from the old session.
* **Verification:**
  - **Unit Tests:** Created [`ViewerRetryLifecycleTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/viewer/ViewerRetryLifecycleTest.kt) verifying that cancelling the parent effect scope terminates child retry loops immediately and that reconnect triggers spawn clean sessions without old loop interference.
  - **Device Deployment:** Built and verified on Pixel 6 Pro & Pixel 8 Pro.

---

### B-07: Unsynchronized Maps in `WebRtcManager` Callback Threads
* **Location:** [`WebRtcManager.kt:37-38, 66`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L37-L38)
* **Status:** **Resolved / Fixed**
* **Description:** `peerConnections`, `dataChannels`, and `remoteAudioTracks` were standard non-thread-safe `mutableMapOf` (`LinkedHashMap`) collections accessed concurrently by WebRTC native callback threads (`onDataChannel`, `onConnectionChange`, `onTrack`), background coroutine dispatchers (`broadcastDataChannelCommand`, FPS adjustments), and main thread teardown (`closePeer`, `closeAll`). Concurrent reads during writes presented race conditions and risk of `ConcurrentModificationException`.
* **Remediation & Applied Implementation:**
  Replaced all three collections with thread-safe `ConcurrentHashMap`:
  ```kotlin
  // Thread-safe concurrent collections for multi-threaded WebRTC callback & coroutine access
  private val peerConnections = java.util.concurrent.ConcurrentHashMap<String, PeerConnection>()
  private val dataChannels = java.util.concurrent.ConcurrentHashMap<String, DataChannel>()
  ...
  private val remoteAudioTracks = java.util.concurrent.ConcurrentHashMap<String, AudioTrack>()
  ```
* **Verification:**
  - **Unit Tests:** Created [`WebRtcConcurrentMapTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/webrtc/WebRtcConcurrentMapTest.kt) running 1,000 rapid concurrent `put`/`remove` operations from 4 parallel threads while simultaneously running map iterations (`values.forEach`, `keys.count`) without throwing.
  - **Device Deployment:** Built and verified on Pixel 6 Pro & Pixel 8 Pro.

---

### B-08: Duplicate Flow Collectors on Service Re-Entry
* **Location:** [`CameraMonitorService.kt:49-220`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L49-L220)
* **Status:** **Resolved / Fixed**
* **Description:** If `startStreaming()` was called while the service was already running (such as on Activity recreation, screen rotation, or `ServiceConnection` re-binds), new coroutine collectors for `signalingClient.incomingMessages`, `dataChannelCommands`, and `activeViewersCount` were spawned without cancelling previous collectors, duplicating all event handlers.
* **Remediation & Applied Implementation:**
  1. Added `isStreamingActive` guard in [`CameraMonitorService.startStreaming`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L143-L148) returning early if streaming is already active while ensuring camera capture is alive.
  2. Retained individual `Job?` references (`signalingJob`, `signalingClientJob`, `dataChannelJob`, `viewersCountJob`) and cancelled previous instances prior to re-launching.
  3. Added [`stopStreaming()`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L206) to cancel all collection jobs and reset service state cleanly upon teardown or `onDestroy()`.
* **Verification:**
  - **Unit Tests:** Added `testStartStreamingIdempotencyPreventsDuplicateCollectors` in [`CameraStandbyStateTest.kt`](file:///E:/Antigravity/CamMonitor/app/src/test/java/com/a0soft/gphone/camonitor/service/CameraStandbyStateTest.kt#L172-L199).
  - **Device Deployment:** Built and verified on Pixel 6 Pro & Pixel 8 Pro.

---

### B-09: Silent Permission Denial in `MainActivity`
* **Location:** [`MainActivity.kt:39-43`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/MainActivity.kt#L39-L43)
* **Description:** The permission callback leaves handling empty (`// Permissions handled`). Denying `CAMERA` or `RECORD_AUDIO` produces no feedback, resulting in crashes or black screens when entering Camera or Viewer modes.
* **Remediation:** Track denied permissions in state and prompt the user before opening camera or microphone streams.

---

## 5. Architectural & Reliability Recommendations

1. **Broker Redundancy (HiveMQ + EMQX):**
   [Specification.md Section 1.20](file:///E:/Antigravity/CamMonitor/Specification.md#L20) states `broker.hivemq.com:8883` and `broker.emqx.io:8883`. The code only knows about HiveMQ. Adding `broker.emqx.io:8883` as a secondary host in `resolveHostCandidates` will prevent total signaling outages when HiveMQ encounters downtime.
2. **Clock Skew Tolerance in Anti-Replay:**
   The current 30-second window (`|currentTime - timestamp| > 30_000L`) will reject legitimate signaling packets if two phones have slight wall-clock drift (common on manual time settings). Adding a relative sequence counter or expanding the window to 60–120 seconds with nonce deduplication improves cellular reliability without reducing replay resistance.

---

*Report written to `security_bug_checking_20261022.md`.*