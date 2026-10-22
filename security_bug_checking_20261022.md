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
| **S-03** | Security | Missing `recipientId` Validation in Envelope Verification | **Medium** | [`SecurityRepository.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L267-L327) |
| **S-04** | Security | Non-Atomic Nonce Cache Race Condition | **Medium** | [`SecurityRepository.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L282-L325) |
| **S-05** | Security | Key Pair Desync on Android 12+ KeyStore Fallback | **Medium** | [`SecurityRepository.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L109-L119) |
| **B-01** | Bug | Multi-Role Whitelist Count Lockout | **Critical** | [`SecurityRepository.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L202-L206), [`ManageDevicesScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/devices/ManageDevicesScreen.kt#L41-L42) |
| **B-02** | Concurrency | Main-Thread Deadlock / 2000ms Freeze in `stopCapture()` | **Critical** | [`CameraXVideoCapturer.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L597-L621) |
| **B-03** | Bug | WebRTC Reconnection Stalls via Stale Duplicate ICE Cache | **High** | [`WebRtcManager.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L400), [`#L699-L704`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L699-L704) |
| **B-04** | Bug | Reconnecting Viewer Rejected via Stale Capacity Check | **High** | [`CameraMonitorService.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L274-L278) |
| **B-05** | Bug | Ephemeral OTP Timer Dies After Initial 5 Minutes | **Medium** | [`CameraPairingScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt#L60-L68) |
| **B-06** | Concurrency | Coroutine Leak in Viewer Mode Retries | **Medium** | [`ViewerModeScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L257-L281) |
| **B-07** | Concurrency | Unsynchronized Maps in `WebRtcManager` Callback Threads | **Medium** | [`WebRtcManager.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L37-L39), [`#L66`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L66) |
| **B-08** | Bug | Duplicate Flow Collectors on Service Re-Entry | **Medium** | [`CameraMonitorService.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L135-L188) |
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
* **Location:** [`SecurityRepository.kt:267-327`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L267-L327)
* **Description:** While `recipientId` is included in the signed payload, `verifyAndDecryptEnvelope` never asserts `envelope.recipientId == this.deviceId`.
* **Impact:** Envelopes reflected by the broker, cross-routed across topics, or replayed between multiple paired cameras will be accepted as long as the key matches.
* **Remediation:**
  ```kotlin
  // In SecurityRepository.kt: verifyAndDecryptEnvelope
  if (envelope.recipientId != deviceId) {
      AppLogger.w("SecurityRepository", "Envelope discarded: recipientId mismatch (${envelope.recipientId} != $deviceId)")
      return null
  }
  ```

---

### S-04: Non-Atomic Nonce Cache Race Condition
* **Location:** [`SecurityRepository.kt:282-325`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L282-L325)
* **Description:** The anti-replay check uses a check-then-act pattern across separate lines:
  ```kotlin
  if (seenNonces.containsKey(envelope.nonce)) return null
  // ... verify and decrypt ...
  seenNonces[envelope.nonce] = envelope.timestamp
  ```
  Incoming MQTT messages are dispatched concurrently on `Dispatchers.IO`. Two identical messages arriving in close succession will both evaluate `containsKey == false`, allowing duplicate processing.
* **Remediation:**
  ```kotlin
  // Reserve nonce atomically
  if (seenNonces.putIfAbsent(envelope.nonce, envelope.timestamp) != null) {
      AppLogger.d("SecurityRepository", "Envelope duplicate nonce discarded: ${envelope.nonce}")
      return null
  }
  ```
  If signature verification or decryption subsequently throws, remove the nonce reservation via `seenNonces.remove(envelope.nonce)`.

---

### S-05: Key Pair Desync on Android 12+ KeyStore Fallback
* **Location:** [`SecurityRepository.kt:109-119`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L109-L119)
* **Description:** If a device running Android 12+ fails ECDH in KeyStore during initialization (e.g. OEM-specific driver flaws with `PURPOSE_AGREE_KEY`), it falls back to software keys saved in `prefs`. On next launch, `SecurityRepository` checks `SDK_INT >= S` first and attempts KeyStore creation again. If KeyStore succeeds on reboot, the public key changes, breaking existing pairings.
* **Remediation:** Record a flag in SharedPreferences (`key_using_software_fallback = true`) when falling back to software keys, and skip KeyStore generation if the flag is set.

---

## 4. Detailed Functional Bugs & Concurrency Issues

### B-01: Multi-Role Whitelist Count Lockout (Critical)
* **Location:** [`SecurityRepository.kt:202-206`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt#L202-L206), [`ManageDevicesScreen.kt:41-42`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/devices/ManageDevicesScreen.kt#L41-L42)
* **Description:** `addPairedDevice` checks the total device count instead of the role-specific count:
  ```kotlin
  val current = getPairedDevices().toMutableList()
  if (current.size >= maxLimit && current.none { it.deviceId == device.deviceId }) {
      return false
  }
  ```
  If a phone has paired with 2 cameras as a viewer (`current.size == 2`) and is then started in Camera mode to pair a viewer (`maxLimit = MAX_PAIRED_VIEWERS = 2`), `current.size >= 2` evaluates to `true`. The device rejects all viewer pairing attempts.
* **Remediation:**
  ```kotlin
  // In SecurityRepository.kt
  override fun addPairedDevice(device: PairedDevice, maxLimit: Int): Boolean {
      val current = getPairedDevices().toMutableList()
      val roleCount = if (device.role != null) current.count { it.role == device.role } else current.size
      if (roleCount >= maxLimit && current.none { it.deviceId == device.deviceId }) {
          AppLogger.w("SecurityRepository", "Cannot add paired device: Max limit $maxLimit reached for role ${device.role}")
          return false
      }
      current.removeAll { it.deviceId == device.deviceId }
      current.add(device)
      savePairedDevices(current)
      clearPairingOtp()
      return true
  }

  // In ManageDevicesScreen.kt
  val canPairViewer = securityRepo.getPairedViewers().size < SecurityRepository.MAX_PAIRED_VIEWERS
  val canPairCamera = securityRepo.getPairedCameras().size < SecurityRepository.MAX_PAIRED_CAMERAS
  ```

---

### B-02: Main-Thread Deadlock / 2000ms Freeze in `stopCapture()` (Critical)
* **Location:** [`CameraXVideoCapturer.kt:597-621`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/camera/CameraXVideoCapturer.kt#L597-L621)
* **Description:**
  ```kotlin
  val latch = CountDownLatch(1)
  mainHandler.post {
      try { ... } finally { latch.countDown() }
  }
  latch.await(2, TimeUnit.SECONDS)
  ```
  [`CameraMonitorService`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L216) executes `stopCameraCapture()` on `Dispatchers.Main` when the 60-second inactivity timer fires or when `onDestroy()` runs. Because `stopCapture()` is called on the Main Thread, `latch.await()` blocks the Main Looper, preventing the posted block from running. The UI freezes for exactly 2.0 seconds until `await()` times out.
* **Remediation:**
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
      } catch (e: Exception) {
          AppLogger.e(tag, "Error stopping CameraX capture", e)
      }
  }

  if (Looper.myLooper() == Looper.getMainLooper()) {
      stopRunnable.run()
  } else {
      val latch = CountDownLatch(1)
      mainHandler.post {
          try { stopRunnable.run() } finally { latch.countDown() }
      }
      latch.await(2, TimeUnit.SECONDS)
  }
  ```

---

### B-03: WebRTC Reconnection Stalls via Stale Duplicate ICE Cache
* **Location:** [`WebRtcManager.kt:400`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L400), [`#L699-L704`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L699-L704)
* **Description:** [`createPeerConnection`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L395) calls `closePeer(peerId, keepPendingCandidates = true)`. This preserves `addedRemoteIceCandidates`. On reconnect, the camera re-sends the same candidate strings (same host/srflx IP:port). The viewer checks `seen.add(candidateKey)`, sees `false`, and drops all candidates, causing reconnection to fail.
* **Remediation:** Clear `addedRemoteIceCandidates` for the peer whenever a new `PeerConnection` is created:
  ```kotlin
  addedRemoteIceCandidates.remove(peerId)
  ```

---

### B-04: Reconnecting Viewer Rejected via Stale Capacity Check
* **Location:** [`CameraMonitorService.kt:274-278`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L274-L278)
* **Description:**
  ```kotlin
  if (app.webRtcManager.activeViewersCount.value >= 2) {
      sendSignalingMessageToViewer(senderId, SignalingMessage.Reject("MAX_CAPACITY_REACHED"))
      return
  }
  ```
  If Viewer 1 disconnects briefly and reconnects, its prior WebRTC peer connection may still register as `CONNECTED` for several seconds before ICE timeout. The camera drops Viewer 1 with `MAX_CAPACITY_REACHED`.
* **Remediation:** Check whether the connecting viewer already holds an active peer connection:
  ```kotlin
  val isExistingPeer = app.webRtcManager.hasPeer(senderId)
  if (app.webRtcManager.activeViewersCount.value >= 2 && !isExistingPeer) {
      sendSignalingMessageToViewer(senderId, SignalingMessage.Reject("MAX_CAPACITY_REACHED"))
      return
  }
  ```

---

### B-05: Ephemeral OTP Timer Dies After Initial 5 Minutes
* **Location:** [`CameraPairingScreen.kt:60-68`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt#L60-L68)
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
  When `secondsLeft` reaches 0, it regenerates the OTP once, resets `secondsLeft = 300`, and the `LaunchedEffect` terminates permanently.
* **Remediation:** Wrap in an outer loop checking `isActive`:
  ```kotlin
  LaunchedEffect(Unit) {
      while (isActive) {
          while (secondsLeft > 0) {
              delay(1000L)
              secondsLeft--
          }
          otp = securityRepo.generatePairingOtp()
          secondsLeft = 300
      }
  }
  ```

---

### B-06: Coroutine Leak in Viewer Mode Retries
* **Location:** [`ViewerModeScreen.kt:257-281`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt#L257-L281)
* **Description:** Inside `LaunchedEffect(targetCamera, reconnectTrigger)`, retries are launched via `coroutineScope.launch { ... }` instead of `launch { ... }`. When `reconnectTrigger` increments, previous retry jobs keep running for up to 45 seconds, sending old offers and old ICE candidates.
* **Remediation:** Launch child jobs on the `CoroutineScope` of the `LaunchedEffect` block (`this.launch`), ensuring automatic cancellation when keys change.

---

### B-07: Unsynchronized Maps in `WebRtcManager` Callback Threads
* **Location:** [`WebRtcManager.kt:37-39, 66`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/webrtc/WebRtcManager.kt#L37-L39)
* **Description:** `peerConnections`, `dataChannels`, and `remoteAudioTracks` are standard `mutableMapOf` collections accessed concurrently by WebRTC native threads (`onDataChannel`, `onConnectionChange`, `onTrack`) and main/coroutine threads (`broadcastDataChannelCommand`, `closePeer`).
* **Remediation:** Use thread-safe `ConcurrentHashMap`:
  ```kotlin
  private val peerConnections = java.util.concurrent.ConcurrentHashMap<String, PeerConnection>()
  private val dataChannels = java.util.concurrent.ConcurrentHashMap<String, DataChannel>()
  private val remoteAudioTracks = java.util.concurrent.ConcurrentHashMap<String, AudioTrack>()
  ```

---

### B-08: Duplicate Flow Collectors on Service Re-Entry
* **Location:** [`CameraMonitorService.kt:135-188`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/service/CameraMonitorService.kt#L135-L188)
* **Description:** If `startStreaming()` is called while the service is already running, new flow collectors for `incomingMessages`, `dataChannelCommands`, and `activeViewersCount` are created without cancelling previous collectors, duplicating all event handlers.
* **Remediation:** Guard `startStreaming()` with an `isStreamingActive` boolean flag or manage collection jobs in structured `Job?` references.

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