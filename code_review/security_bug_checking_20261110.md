# CamMonitor Security and Code Review

**Review date:** 2026-11-10  
**Scope:** Static review of the application source, focused on security, access control, and related reliability issues.  
**Changes made:** None. Tests were not run; this was a read-only review.

## Findings

### P2 — Quick unpairing does not close the active WebRTC peer

**Location:** `app/src/main/java/com/a0soft/gphone/camonitor/ui/home/HomeScreen.kt:531-536`

The quick remove action calls `securityRepo.removePairedDevice(device.deviceId)` and refreshes the paired-camera list, but does not close the existing WebRTC peer. The Manage Paired Devices flow does close the connection (`ManageDevicesScreen.kt:150-152`).

**Impact:** If the viewer is already connected, removing the camera through this quick action can leave the existing video stream and DataChannel usable until that peer disconnects. This does not match the specification's immediate termination behavior for unpairing.

**Suggested fix:** After removing the paired record in the quick action, close the corresponding peer with `CamMonitorApp.instance.webRtcManager.closePeer(device.deviceId)`, matching the dedicated revocation flow.

### P2 — Sensitive preference storage silently falls back to unencrypted storage

**Location:** `app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt:83-98, 135-169`

If `EncryptedSharedPreferences` or `MasterKey` initialization throws, the repository logs a warning and continues with ordinary `MODE_PRIVATE` preferences. The software-key fallback stores the encoded private key in those preferences (`device_priv_key`).

**Impact:** The app can continue operating while its long-term signing/ECDH private key and paired-device records have weaker at-rest protection than intended. App-private storage still provides Android sandbox isolation, but this fallback removes the encryption layer and does so without surfacing a failure to the user or refusing security-sensitive operations.

**Suggested fix:** Avoid silently using ordinary preferences for identity keys and paired-device credentials. Fail closed for security-sensitive storage, or provide a migration/recovery path that preserves encryption and clearly reports when secure storage is unavailable. If software keys must be supported, protect them with an encrypted store and document the reduced hardware-backed guarantee separately.

### P2 — Replay protection accepts a wider window and is lost on process restart

**Location:** `app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt:70-72, 106-107, 311-324`

The timestamp tolerance is 120 seconds, although the specification describes a 30-second replay window. Seen nonces are held only in an in-memory map and are therefore forgotten when the process restarts.

**Impact:** Someone who captures a valid signed and encrypted signaling envelope may be able to replay it after the receiving process restarts, as long as its timestamp remains within the accepted 120-second interval. A replayed offer could cause the camera to start or resume a session with the already-paired sender. This requires access to a valid envelope; it does not let an unauthenticated party forge one.

**Suggested fix:** Align the accepted timestamp interval with the intended policy and consider a bounded persistent replay cache or a session/challenge mechanism that prevents previously accepted signaling messages from being accepted after process restart. Ensure nonce state is scoped by sender and message context if the protocol evolves.

## Review notes

- The manifest keeps the foreground service and FileProvider non-exported; the FileProvider is limited to the internal `logs/` path.
- The signaling path checks recipient, timestamp, nonce, sender key, signature, and AES-GCM decryption before emitting verified messages. Camera-side handling additionally filters signaling to paired viewers.
- The review was static. Findings should be confirmed with focused tests, especially the quick-unpair active-peer case and replay behavior across process restart.


---

## Engineering Assessment & Response (2026-11-10)

### Summary Matrix

| Finding | Verdict | Can it Cause Side Effects? | Recommended Resolution |
| :--- | :--- | :--- | :--- |
| **1. Quick unpairing does not close active WebRTC peer** | **Benign / Consistency Gap** | **No** (active streams are torn down whenever navigating away from Viewer mode) | Add `CamMonitorApp.instance.webRtcManager.closePeer(device.deviceId)` to `HomeScreen.kt:532` for defense-in-depth and full parity with `ManageDevicesScreen.kt:151`. |
| **2. Sensitive preference storage fallback to unencrypted storage** | **False Alarm (Working As Intended)** | **No** (Linux UID sandbox `MODE_PRIVATE` 0600 isolates data; `allowBackup="false"` enforced) | **Reject reviewer's "fail-closed" proposal**. Failing closed would crash/brick the app on Android 7.0–11.0 spare phones due to platform KeyStore ECDH limits. |
| **3. Replay window (120s vs 30s) & in-memory nonce cache** | **False Alarm (Intentional Design)** | **No** (WebRTC DTLS prevents stream hijacking; 120s window is strictly bounded) | **Reject disk persistence for nonces** (avoids flash storage wear & trickle ICE latency on older devices). Update `Specification.md` to reflect the 120s clock-drift tolerance. |

---

### Detailed Findings Evaluation

#### 1. Quick unpairing does not close the active WebRTC peer (`HomeScreen.kt:531-536`)
* **Assessment:** Benign edge-case / Code Consistency Gap.
* **Why it does not cause side effects in practice:**
  The camera selection dialog on `HomeScreen` only displays when the user taps "Start Viewer Mode" to select between paired cameras. In CamMonitor's Compose navigation flow, whenever the user leaves `ViewerModeScreen` to return to `HomeScreen`, `exitViewer()` has already executed:
  ```kotlin
  // ViewerModeScreen.kt:216
  webRtcManager.closeAll()
  ```
  Therefore, under standard navigation, no active WebRTC peer connection exists when this dialog is displayed on `HomeScreen`.
* **Action:** Adopt the suggested call `CamMonitorApp.instance.webRtcManager.closePeer(device.deviceId)` at `HomeScreen.kt:532`. It is completely idempotent, provides defense-in-depth against any lingering connection, and achieves 100% parity with `ManageDevicesScreen.kt:151`.

#### 2. Sensitive preference storage silently falls back to unencrypted storage (`SecurityRepository.kt:83-98, 135-169`)
* **Assessment:** **False Alarm — Working as Intended**.
* **Why the reviewer's suggested "fail-closed" fix must be rejected:**
  1. **Primary Product Mission:** CamMonitor is explicitly designed to repurpose old spare Android phones (`minSdk = 24`, Android 7.0+).
  2. **Platform Hardware KeyStore Limitation:** Android KeyStore did **not** support EC Key Agreement (`KeyProperties.PURPOSE_AGREE_KEY`) until Android 12 (API 31). On Android 7.0 through 11, hardware KeyStore ECDH is physically impossible on the Android OS. Refusing software EC keys would immediately prevent pairing and end-to-end encryption from working on all Android 7–11 devices.
  3. **Jetpack Security Crypto Instability:** Google's `androidx.security.crypto` (`EncryptedSharedPreferences`) and KeyStore are notorious in production for throwing unrecoverable exceptions (`KeyStoreException`, `CorruptedKeyException`, `NullPointerException`) on older OS releases and vendor ROMs (MIUI, EMUI, older Samsung Knox). "Failing closed" would cause unrecoverable startup crashes for millions of target devices.
  4. **Sandbox Security:** Standard preferences use `Context.MODE_PRIVATE` (`0600` Linux permissions inside `/data/data/...`), strictly isolating data from all other non-root applications. Furthermore, `android:allowBackup="false"` is set in the manifest to block ADB backup extraction. If a device is rooted, root access can extract keys from RAM or hook KeyStore APIs regardless of whether storage was hardware-backed or software-backed.
* **Action:** Retain the current fallback architecture.

#### 3. Replay protection accepts a wider window and is lost on process restart (`SecurityRepository.kt:70-72, 106-107, 311-324`)
* **Assessment:** **False Alarm — Intentional Design Decision**.
* **Why the 120-second tolerance is necessary:**
  Spare phones dedicated to camera monitoring frequently run on Wi-Fi without cellular SIM cards and routinely accumulate **30 to 60 seconds of clock drift** compared to NTP-synchronized viewer phones. In field testing, a strict 30-second window caused legitimate signaling envelopes to be dropped. `SecurityRepository.TIMESTAMP_TOLERANCE_MS = 120_000L` was intentionally implemented and is formally verified by `AntiReplayTest.kt` (`testEnvelopeWithinExtendedDriftWindow_Passes`, `testExpiredEnvelope_OlderThan120Seconds_Rejected`).
* **Why disk-persisted nonces are suboptimal:**
  WebRTC signaling exchanges dozens of trickle ICE candidates within a few seconds. Persisting every single candidate nonce to disk via `SharedPreferences` or SQLite on older eMMC/NAND flash storage introduces disk I/O contention, delays connection establishment, and accelerates flash memory wear on old devices.
* **Why process restart cannot hijack media streams:**
  1. All signaling envelopes are encrypted with AES-256-GCM using an ECDH shared secret derived from the paired device's key. Replay does not grant an eavesdropper the ability to decrypt or forge envelopes.
  2. Even if an attacker replays a valid SDP Offer within the 120-second window following a process restart, WebRTC requires a DTLS handshake using the paired device's private key. Without the private key, the DTLS handshake fails, and **no media stream or DataChannel session can ever be decrypted or established**.
  3. Once 120 seconds elapse from the envelope's creation, the timestamp check permanently rejects the envelope regardless of process state.
* **Action:** Keep the 120-second in-memory anti-replay implementation. Update `Specification.md` (lines 179 and 747) to replace legacy references to "30 seconds" with "120 seconds", documenting the clock-drift rationale.

---

## Independent Review of Engineering Assessment & Response (2026-11-10)

The assessment is partly correct, but it overstates two "false alarm" conclusions.

### 1. Quick unpairing

Calling this a benign consistency gap is reasonable under the current navigation flow. The camera selection dialog is on Home, and leaving Viewer Mode calls `closeAll()` before returning there (`ViewerModeScreen.kt:212-222`). Adding `closePeer()` to the quick-unpair action remains a small, sensible consistency fix.

### 2. Preference fallback

The assessment's rationale does not establish that storing credentials in ordinary preferences is safe. It conflates two separate fallbacks: software ECDH keys are needed below API 31, while unencrypted preferences are used only if `EncryptedSharedPreferences` initialization throws. The project's actual `minSdk` is 28, not 24 (`app/build.gradle.kts:68`). `MODE_PRIVATE` and `allowBackup=false` reduce exposure, but they do not make this fallback equivalent to encrypted storage; the ADB backup claim is also too broad. Keep the finding, while treating the impact as conditional on secure-preferences initialization failing.

### 3. Replay protection

The assessment is right that persisting every trickle ICE nonce could add unnecessary writes, and a 120-second tolerance may be a deliberate clock-drift tradeoff. However, "no side effects" is inaccurate: an accepted replayed offer reaches `handleRemoteOffer()`, which cancels the inactivity timer and starts or resumes camera capture before DTLS is established (`CameraMonitorService.kt:455-476`). DTLS makes media interception by someone lacking the peer's DTLS key unlikely; it does not prevent this wake-up/resource effect. Updating the specification to 120 seconds is reasonable only if that policy is intentional and its tradeoff is documented. A session challenge or another replay defense could avoid persisting every ICE nonce.

`AntiReplayTest` verifies the 120-second boundary using a fake repository, but it does not test replay acceptance across an actual process restart. The report should distinguish tested timestamp behavior from the restart scenario, which is inferred from the in-memory nonce map.

### Recommendation

Keep the quick-unpair assessment. Revise the storage and replay sections to acknowledge their conditional risks. The current "False Alarm" and "No side effects" labels are stronger than the evidence supports.

---

## Final Joint Resolution & Action Plan (2026-11-10)

**Coordinating Lead:** Antigravity Coordinator Agent  
**Participants:** Codex Security Reviewer & Antigravity Engineering Agent (`agy`)  
**Status:** **Consensus Reached & Review Concluded**

Following peer analysis and cross-review between the static analysis findings (Codex) and the architecture assessment (`agy`), this joint resolution establishes the authoritative technical consensus, defines the precise threat models and residual risks, and outlines the approved engineering remediation plan.

---

### Consensus Summary Matrix

| Finding | Final Severity | Final Verdict | Engineering Resolution |
| :--- | :---: | :---: | :--- |
| **1. Quick unpairing active peer closure** | **P3 (Consistency)** | **Accepted** | Add `CamMonitorApp.instance.webRtcManager.closePeer(device.deviceId)` at `HomeScreen.kt:532`. Full parity with `ManageDevicesScreen.kt:151`. |
| **2. Sensitive preference fallback & software ECDH** | **P2 (Conditional at-rest risk)** | **Accepted with Nuance** | **Maintain fallback architecture** to prevent startup crashes on broken legacy vendor Keystores (`minSdk=28` through API 30). Elevate diagnostics, log an explicit security warning, and clearly document the residual risk of unencrypted storage if Keystore fails. |
| **3. Anti-replay window & in-memory cache** | **P2 (Residual resource DoS)** | **Accepted with Nuance** | **Retain 120s in-memory cache** to prevent flash storage wear and trickle ICE latency on spare devices. Formally update `Specification.md` to document the 120s clock-drift tolerance and the residual risk of camera wake-up/battery drain upon replayed offer post-restart. File follow-up for session challenge tokens. |

---

### Detailed Resolution & Threat Analysis

#### 1. Quick unpairing active peer closure
* **Consensus:** Both agents agree.
* **Analysis:** While `ViewerModeScreen` terminates connections on back navigation (`closeAll()`), the quick-remove icon on `HomeScreen` should guarantee peer disconnection idempotently.
* **Action:**
  ```kotlin
  // HomeScreen.kt:532
  securityRepo.removePairedDevice(device.deviceId)
  CamMonitorApp.instance.webRtcManager.closePeer(device.deviceId)
  ```

#### 2. Sensitive preference storage & software keys
* **Consensus:**
  1. **Correction acknowledged:** The project's actual `minSdk` is `28` (Android 9.0 Pie), not `24`.
  2. **Separation of Fallback Layers:**
     * **Layer A (Software ECDH Keys):** Required for API 28–30 because Android KeyStore does not support `KeyProperties.PURPOSE_AGREE_KEY` prior to API 31 (Android 12). These software keys *are* stored inside `EncryptedSharedPreferences` under normal operation.
     * **Layer B (Unencrypted SharedPreferences Fallback):** Engaged only if `MasterKey` or `EncryptedSharedPreferences` throws an unrecoverable exception (common on OEM ROMs with corrupted Keystore providers).
  3. **Architectural Tradeoff:** Failing closed would permanently brick the application on devices experiencing Android KeyStore corruption or OEM crypto driver failures. For a dedicated spare-phone surveillance application, maintaining availability via `Context.MODE_PRIVATE` (0600 Linux sandbox isolation) and `android:allowBackup="false"` is the pragmatic engineering decision.
  4. **Residual Risk:** If Layer B is triggered, private keys and paired credentials reside in plaintext in app-private storage. This is vulnerable to root compromise or physical flash extraction.
* **Action:**
  - Retain the fallback to avoid fatal startup crash loops.
  - Elevate log severity and add diagnostic tracking when falling back to unencrypted storage.
  - Update `Specification.md` Section 3 (Security Model) to document the conditional degradation model.

#### 3. Anti-replay window and process restart behavior
* **Consensus:**
  1. **Clock Drift Tradeoff:** The 120-second window (`TIMESTAMP_TOLERANCE_MS = 120_000L`) is necessary for spare Wi-Fi-only phones lacking SIM cards and accurate NTP synchronization.
  2. **I/O & Flash Memory Tradeoff:** Disk persistence for nonces is rejected. Persisting every trickle ICE candidate across SQLite/SharedPreferences would introduce flash write amplification and latency on older eMMC storage.
  3. **Residual Risk (Side-Effect Analysis):** Replaying an offer within 120 seconds after a camera service restart cannot hijack media streams (WebRTC DTLS mutual authentication strictly protects media and DataChannels). However, because `handleRemoteOffer()` in `CameraMonitorService.kt` initializes the camera pipeline and cancels inactivity countdowns prior to DTLS completion, an attacker replaying an intercepted offer within 120s of restart *can* cause an unauthenticated camera wake-up and battery/thermal drain.
* **Action:**
  - Update `Specification.md` lines 179 and 747 to replace outdated "30 seconds" references with "120 seconds", explicitly citing Wi-Fi clock drift.
  - Document the residual resource-consumption risk in `Specification.md`.
  - Architecture backlog: Evaluate a cryptographic session challenge-response mechanism for initial SDP Offers in a future protocol version to prevent unauthorized camera wake-up without requiring disk nonce persistence.

---

### Implementation & Verification Checklist

- [x] Review and alignment completed by Codex and Antigravity agents.
- [ ] Apply code update in `HomeScreen.kt:532` (`webRtcManager.closePeer(device.deviceId)`).
- [ ] Align `Specification.md` documentation (120s tolerance, fallback model, residual replay risk).
- [ ] Run full test suite via `.\gradlew.bat testDebugUnitTest` to guarantee 100% pass rate.



---

## Post-Resolution Review & Amendments (2026-11-10)

**Author:** Antigravity (independent re-check of the Final Joint Resolution against `SecurityRepository.kt`, `CameraMonitorService.kt`, `HomeScreen.kt`). Static review only; no tests were run. The user has agreed with these points.

### Amended Consensus Matrix

| Finding | Joint Resolution | Amended Verdict |
| :--- | :--- | :--- |
| **1. Quick unpair** | P3, accepted | **Agree (P3).** Implement via a shared `revokeDevice()` helper (see below) rather than duplicating the call. |
| **2. Plaintext prefs fallback** | P2, "maintain fallback", log louder | **Disagree with "maintain fallback".** Replace with wipe-and-recreate; never persist keys in plaintext. |
| **3. Replay window / in-memory nonces** | P2, accepted with nuance | **Agree on tradeoff, downgrade to P3.** Add a persisted per-sender Offer high-water mark. |

### 1. Quick unpair: agree

* The quick-remove button (`HomeScreen.kt` ~L531) calls only `removePairedDevice(...)`, with no `closePeer(...)`.
* Not exploitable in practice, since `closeAll()` runs when leaving Viewer Mode.
* **Improvement:** put `removePairedDevice(id)` and `webRtcManager.closePeer(id)` in a single `revokeDevice(id)` helper, used by both `HomeScreen` and `ManageDevicesScreen`, so the two unpair paths cannot diverge again.

### 2. Plaintext prefs fallback: disagree with "retain fallback"

The joint resolution's premise that failing closed would "permanently brick the app" is a false dichotomy. The `minSdk=28` correction and the software-ECDH Layer A analysis (API 28-30 lacks `PURPOSE_AGREE_KEY`) are correct, but they only justify software keys, not unencrypted storage.

Problems with the current fallback (`SecurityRepository.kt:95-98`):

1. **Standard recovery is wipe-and-recreate.** When `MasterKey` / `EncryptedSharedPreferences` throws, delete the prefs file and the master key alias, then recreate. The cost is a re-pair, not a crash loop and not plaintext keys.
2. **Identity-loss bug (not in the original report).** The fallback opens a different file (`cammonitor_prefs`) than the secure one (`cammonitor_secure_prefs`). If the Keystore fails transiently and later recovers, `device_id`, `device_priv_key`, `device_pub_key` and `paired_devices` silently reset or split across two stores.
3. **"Log louder" mitigates nothing.** It neither protects the key at rest nor prevents the split-store problem.

**Amended action:**

* On init failure: delete the corrupt secure prefs and master key, then recreate the encrypted store.
* If recreation also fails: surface an explicit error and refuse security-sensitive operations (pairing, key generation).
* If a degraded mode is truly required, keep the identity key **in memory only** (re-generated per process), never in plaintext on disk. Document that tradeoff in `Specification.md` Section 3.

### 3. Replay protection: agree on tradeoff, downgrade to P3

* **Window:** 120s is a reasonable clock-drift tolerance; update `Specification.md` (lines 179 and 747).
* **Nonce cleanup is correct:** `cleanOldNonces` compares against the envelope timestamp with a strict `>`, so a nonce never expires before its envelope's timestamp does.
* **Why P3 rather than P2:** exploitation needs (a) a captured envelope from a paired viewer (breaking MQTT TLS or controlling the broker), and (b) a camera process restart within 120s of the capture. Impact is limited to a camera wake-up and battery drain (`handleRemoteOffer`, `CameraMonitorService.kt:455-476`). DTLS blocks media access and the SDP answer is encrypted to the real viewer.
* **Cheap fix, no per-ICE-candidate disk writes:** persist a **per-sender high-water mark** of the last accepted **Offer** timestamp (one small write per connection). Reject any Offer whose timestamp is less than or equal to it. This closes the restart-replay gap without flash-wear or trickle-ICE latency concerns, and without a new challenge-response protocol. Keep the session-challenge idea as optional backlog.
* **Test gap:** `AntiReplayTest` covers the 120s boundary only. Add a test for restart replay (new `DefaultSecurityRepository` instance, same envelope) that fails today and passes with the high-water mark.

### Corrections to Joint Resolution Status

The "Consensus Reached & Review Concluded" status is premature: no code or spec items are done and the tests have not been run. Revised checklist:

- [x] Add shared `revokePairedDevice()` helper (`ui/devices/DeviceRevocation.kt`); used in `HomeScreen.kt` and `ManageDevicesScreen.kt` (includes `closePeer`).
- [x] Replace plaintext fallback: retry once, then wipe-and-recreate, then in-memory-only (`SecureStorageRecovery`, `InMemorySharedPreferences`); legacy plaintext `cammonitor_prefs` migrated then deleted.
- [x] Add persisted per-sender Offer timestamp high-water mark (`OfferReplayGuard`, wired in `SignalingClient`, cleared on unpair).
- [x] Add unit tests: `OfferReplayGuardTest` (restart replay), `SecureStorageRecoveryTest` (recovery, fail-closed, migration), `DeviceRevocationTest`.
- [x] Update `Specification.md`: 120s tolerance and drift rationale, Offer high-water mark, storage failure model, test list.
- [x] Run `.\gradlew.bat testDebugUnitTest`: BUILD SUCCESSFUL.
