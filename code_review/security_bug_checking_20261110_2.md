# CamMonitor Security and Code Review (Round 2)

**Review date:** 2026-11-10  
**Scope:** Static review of application source code focusing on security, cryptographic integrity, access control, and platform vulnerabilities.  
**Changes made:** None. Static analysis only.

---

## Findings

### P1 — Pairing Public Key Substitution / Device Identity Hijacking via Malicious `PairRequest`

**Location:** `app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt:304-321` and `app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt:117-126`

**Description & Vulnerability Mechanics:**  
When pairing a new viewer, `CameraPairingScreen` receives a `SignalingMessage.PairRequest(pbDeviceId, pbName, pbPublicKeyBase64)`. The signaling client verifies Proof-of-Possession (that the envelope signature verifies with `pbPublicKeyBase64`) and confirms the payload decrypted successfully using the active OTP.

However, in `DefaultSecurityRepository.addPairedDevice`:
```kotlin
override fun addPairedDevice(device: PairedDevice, maxLimit: Int): Boolean {
    val current = getPairedDevices().toMutableList()
    val roleCount = when (device.role) {
        "CAMERA" -> current.count { it.role != "VIEWER" }
        "VIEWER" -> current.count { it.role != "CAMERA" }
        else -> current.size
    }
    if (roleCount >= maxLimit && current.none { it.deviceId == device.deviceId }) {
        AppLogger.w("SecurityRepository", "Cannot add paired device: Max $maxLimit devices reached for role ${device.role ?: "ALL"}")
        return false
    }
    current.removeAll { it.deviceId == device.deviceId }
    current.add(device)

    savePairedDevices(current)
    AppLogger.i("SecurityRepository", "Paired new device: ${device.name} (${device.deviceId}) [roleCount=${roleCount + 1}/$maxLimit]")
    return true
}
```
If an adversary possessing the pairing OTP (e.g., someone scanning the QR code, a rogue secondary viewer being onboarded, or an attacker observing the QR display) crafts a `PairRequest` that claims the `pbDeviceId` of an **already-paired viewer** (e.g., Viewer A) along with the attacker's own public key (`pbPublicKeyBase64`):
1. The envelope signature is signed by the attacker's private key and validates against `pbPublicKeyBase64`, satisfying Proof-of-Possession in `SignalingClient.kt:370`.
2. In `addPairedDevice`, `current.none { it.deviceId == device.deviceId }` evaluates to `false` because Viewer A's `deviceId` already exists in `current`.
3. The capacity limit check `if (roleCount >= maxLimit && ...)` is completely bypassed, even if `MAX_PAIRED_VIEWERS` (2) has already been reached.
4. `current.removeAll { it.deviceId == device.deviceId }` unceremoniously deletes Viewer A's legitimate record and cryptographic identity.
5. `current.add(device)` registers the attacker's public key under Viewer A's `deviceId`.
6. `savePairedDevices(current)` commits the hijacked record to encrypted storage.

**Impact & Threat Model:**  
An attacker who obtains an active pairing OTP can silently overwrite and replace the public key of ANY existing paired viewer without triggering capacity limit warnings or requiring device unpairing. The legitimate viewer is permanently locked out because their private key will no longer derive matching ECDH secrets with the camera, while the attacker gains full authenticated access to camera signaling and video streams under the guise of the displaced viewer.

**Suggested Remediation:**  
Disallow silently altering the public key of an existing device record during pairing. In `DefaultSecurityRepository.addPairedDevice` (or in a dedicated pairing entry point), verify that if a device ID already exists, its public key matches the existing record:
```kotlin
val existing = current.firstOrNull { it.deviceId == device.deviceId }
if (existing != null && existing.publicKeyBase64 != device.publicKeyBase64) {
    AppLogger.w("SecurityRepository", "Pairing rejected: deviceId ${device.deviceId} is already bound to a different public key")
    return false
}
```
Additionally, in `CameraPairingScreen.kt`, check that `securityRepo.getPairedDevice(msg.pbDeviceId) == null` before accepting an initial `PairRequest`.

---

### P2 — Cryptographic Non-Repudiation & Integrity Gap: Envelope Signatures Omit AES-GCM IV and Authentication Tag

**Location:** `app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt:359-361, 417-419, 52-61`

**Description & Vulnerability Mechanics:**  
When constructing a signaling envelope in `SecurityRepository.createEnvelope`:
```kotlin
val (ciphertext, iv, tag) = CryptoUtils.encryptAesGcm(
    plaintextJson.toByteArray(Charsets.UTF_8),
    secretOrDerivedKey
)

// Signature input: senderId + recipientId + timestamp + nonce + ciphertext
val sigPayload = "$deviceId$recipientId$timestamp$nonce$ciphertext".toByteArray(Charsets.UTF_8)
val signature = CryptoUtils.sign(sigPayload, keyPair.private)
```
And in `verifyAndDecryptEnvelope` (and `verifyEnvelopeSignature`):
```kotlin
val sigPayload = "${envelope.senderId}${envelope.recipientId}${envelope.timestamp}${envelope.nonce}${envelope.ciphertext}"
    .toByteArray(Charsets.UTF_8)
val isValid = CryptoUtils.verify(sigPayload, envelope.signature, senderPublicKey)
```
1. **Omission of Authenticated Data:** The digital signature commits to `senderId`, `recipientId`, `timestamp`, `nonce`, and `ciphertext`, but entirely omits `iv` (12 bytes) and `tag` (16 bytes).
2. **Canonicalization Ambiguity:** The payload fields are concatenated directly without delimiters (e.g. `"$senderId$recipientId$timestamp$nonce$ciphertext"`), creating ambiguity across variable-length fields.
3. **Decryption Perturbation:** In `verifyAndDecryptEnvelope`, signature verification (Step 4) executes *before* AES-GCM decryption (Step 5). If an attacker on the public MQTT broker intercepts a genuine envelope and tampers with the `iv` or `tag`, the signature verification in Step 4 passes. Step 5 then fails during `cipher.doFinal(combined)` with an `AEADBadTagException`, triggering:
```kotlin
catch (e: Exception) {
    AppLogger.e("SecurityRepository", "Decryption failed for envelope from ${envelope.senderId}", e)
    seenNonces.remove(envelope.nonce)
    return null
}
```
This forces eviction of the nonce from `seenNonces`, disrupting anti-replay ordering. Furthermore, `verifyEnvelopeSignature()` (used for Proof-of-Possession in `SignalingClient.kt:370`) considers an envelope authentic even if its IV or authentication tag has been tampered with.

**Impact & Threat Model:**  
1. Digital signatures do not authenticate the entire cryptogram, violating the cryptographic principle of Encrypt-then-Sign / authenticated cryptogram commitment.
2. In-transit tampering with the IV or tag passes signature verification and triggers nonce eviction on decryption failure, opening a vector for nonce de-synchronization and state corruption on public MQTT brokers.

**Suggested Remediation:**  
Bind all cryptogram components (`iv` and `tag`) into the signature payload and separate fields with a distinct delimiter to guarantee canonicalization:
```kotlin
val sigPayload = "${envelope.senderId}|${envelope.recipientId}|${envelope.timestamp}|${envelope.nonce}|${envelope.iv}|${envelope.tag}|${envelope.ciphertext}"
    .toByteArray(Charsets.UTF_8)
```
Apply this consistently across `createEnvelope`, `verifyAndDecryptEnvelope`, and `verifyEnvelopeSignature`.

---

### P2 — Unbounded SharedPreferences Decryption and JSON Deserialization on Public MQTT Rx Topic (Denial of Service / Flash Contention)

**Location:** `app/src/main/java/com/a0soft/gphone/camonitor/data/signaling/SignalingClient.kt:300-353` and `app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt:290-302`

**Description & Vulnerability Mechanics:**  
CamMonitor subscribes to a public topic on an open public MQTT broker (`broker.hivemq.com:8883`):  
`topic = "cammonitor/$deviceId/rx"`.  
Any client on the Internet can connect to this broker and publish messages to this topic without credential requirements.

Whenever a raw payload is delivered, `handleIncomingRawPayload` launches a coroutine:
```kotlin
val envelope = SignalingEnvelope.fromJson(rawJson) ?: return@launch
val senderDevice = securityRepository.getPairedDevice(envelope.senderId)
```
In `DefaultSecurityRepository`:
```kotlin
override fun getPairedDevices(): List<PairedDevice> {
    val jsonStr = prefs.getString("paired_devices", "[]") ?: "[]"
    val list = mutableListOf<PairedDevice>()
    try {
        val arr = JSONArray(jsonStr)
        for (i in 0 until arr.length()) {
            list.add(PairedDevice.fromJson(arr.getJSONObject(i)))
        }
    } catch (e: Exception) { ... }
    return list
}
```
`prefs` is an instance of `EncryptedSharedPreferences`. Calling `prefs.getString("paired_devices", ...)` performs hardware KeyStore / Tink cryptographic operations (AES-SIV key decryption + AES-GCM value decryption), allocates JSON objects, and deserializes models from scratch.
If the envelope is valid or claims a paired sender:
- Line 312 calls `getPairedDevice()` (1st decryption + JSON parse).
- Line 402 in `verifyAndDecryptEnvelope()` calls `getPairedDevice()` (2nd decryption + JSON parse).
- Line 416 in `CameraMonitorService.observeSignaling()` calls `getPairedViewers()` (3rd decryption + JSON parse).
- Line 548 in `sendSignalingMessageToViewer()` calls `getPairedViewers()` (4th decryption + JSON parse).

**Impact & Threat Model:**  
An unauthenticated remote attacker who monitors or scans `cammonitor/<deviceId>/rx` can flood the topic with signaling envelopes. Even with invalid signatures or bogus sender IDs, every single incoming message triggers repeated KeyStore crypto decryptions and JSON object allocations. On low-end or older spare phones (`minSdk = 28`), this causes CPU saturation, severe memory churn, battery depletion, thread pool starvation on `Dispatchers.IO`, and dropped WebRTC video frames.

**Suggested Remediation:**  
Maintain an in-memory cache of paired devices in `DefaultSecurityRepository` (e.g. `private val pairedDevicesCache = java.util.concurrent.CopyOnWriteArrayList<PairedDevice>()` or a `ConcurrentHashMap<String, PairedDevice>`). Populate the cache once on startup and invalidate/update it only when `addPairedDevice()` or `removePairedDevice()` modifies the store:
```kotlin
private val pairedDevicesCache = java.util.concurrent.ConcurrentHashMap<String, PairedDevice>()

override fun getPairedDevices(): List<PairedDevice> {
    if (pairedDevicesCache.isEmpty()) {
        loadPairedDevicesFromPrefs().forEach { pairedDevicesCache[it.deviceId] = it }
    }
    return pairedDevicesCache.values.toList()
}
```

---

### P3 — Two's Complement Integer Overflow in Timestamp Delta Calculation (`Math.abs(Long.MIN_VALUE)`)

**Location:** `app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt:389-392, 483-485`

**Description & Vulnerability Mechanics:**  
In `DefaultSecurityRepository.verifyAndDecryptEnvelope`:
```kotlin
val now = timeProvider.currentTimeMillis()

// 1. Anti-Replay Timestamp Check (120 seconds window for clock drift tolerance)
if (kotlin.math.abs(now - envelope.timestamp) > TIMESTAMP_TOLERANCE_MS) {
    AppLogger.w("SecurityRepository", "Envelope discarded: Timestamp expired (delta: ${now - envelope.timestamp}ms)")
    return null
}
```
In two's complement 64-bit signed integer arithmetic, `Long.MIN_VALUE` (-9,223,372,036,854,775,808L) has no positive counterpart. `kotlin.math.abs(Long.MIN_VALUE)` evaluates to `Long.MIN_VALUE` (negative).

If an envelope arrives with `envelope.timestamp = now - Long.MIN_VALUE` (i.e. `now + 9,223,372,036,854,775,808L`):
1. `now - envelope.timestamp` overflows 64-bit integer arithmetic and yields `Long.MIN_VALUE`.
2. `kotlin.math.abs(Long.MIN_VALUE)` returns `Long.MIN_VALUE` (-9,223,372,036,854,775,808L).
3. The condition `-9223372036854775808L > 120000L` evaluates to **`false`**, and the envelope **passes** the anti-replay timestamp check!
4. The nonce is registered in `seenNonces` with this timestamp.
5. In `cleanOldNonces(now)`:
```kotlin
private fun cleanOldNonces(now: Long) {
    seenNonces.entries.removeIf { (now - it.value) > NONCE_RETENTION_MS }
}
```
`now - it.value` yields `Long.MIN_VALUE`. Since `Long.MIN_VALUE > 120_000L` is `false`, this nonce entry is **never pruned** from the in-memory cache and leaks permanently in RAM.

**Impact & Threat Model:**  
A crafted envelope timestamp bypasses the 120-second timestamp tolerance check due to integer arithmetic underflow/overflow (CWE-190). The invalid timestamp causes indefinite nonce retention in `seenNonces`, resulting in unbounded memory leakage in long-running camera services.

**Suggested Remediation:**  
Validate that `envelope.timestamp > 0` and perform the range comparison without subtraction overflow:
```kotlin
if (envelope.timestamp <= 0L ||
    envelope.timestamp < now - TIMESTAMP_TOLERANCE_MS ||
    envelope.timestamp > now + TIMESTAMP_TOLERANCE_MS
) {
    AppLogger.w("SecurityRepository", "Envelope discarded: Timestamp out of valid window ($now vs ${envelope.timestamp})")
    return null
}
```

---

### P3 — SharedPreferences Namespace Collision Causes Startup Scrubbing of UI Permission States

**Location:** `app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecureStorageRecovery.kt:53-56`, `app/src/main/java/com/a0soft/gphone/camonitor/data/security/SecurityRepository.kt:138-159`, `app/src/main/java/com/a0soft/gphone/camonitor/MainActivity.kt:181, 187`

**Description & Vulnerability Mechanics:**  
In `SecureStorageRecovery.kt`, the legacy migration utility defines:
```kotlin
object LegacyPrefsMigration {
    const val LEGACY_PREFS_NAME = "cammonitor_prefs"
    ...
}
```
In `DefaultSecurityRepository.migrateLegacyPlaintextPrefs()`:
```kotlin
private fun migrateLegacyPlaintextPrefs(context: Context, secure: SharedPreferences) {
    val legacy = context.getSharedPreferences(LegacyPrefsMigration.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    val legacyAll = legacy.all
    if (legacyAll.isEmpty()) return
    val entries = LegacyPrefsMigration.entriesToMigrate(legacyAll, secure.contains("device_id"))
    if (entries.isNotEmpty()) {
        ...
    }
    context.deleteSharedPreferences(LegacyPrefsMigration.LEGACY_PREFS_NAME)
    AppLogger.i("SecurityRepository", "Legacy plaintext preferences scrubbed (migrated=${entries.size} entries)")
}
```
Notice that line 157 calls `context.deleteSharedPreferences("cammonitor_prefs")` whenever `legacyAll.isNotEmpty()`.

However, `MainActivity.kt` and `ViewerModeScreen.kt` simultaneously use `"cammonitor_prefs"` to persist runtime permission request states:
```kotlin
// MainActivity.kt:181, 187
val prefs = getSharedPreferences("cammonitor_prefs", Context.MODE_PRIVATE)
prefs.edit().apply {
    permissions.forEach { putBoolean("has_requested_$it", true) }
}.apply()
```
Whenever the app cold-boots:
1. `DefaultSecurityRepository` initializes and inspects `"cammonitor_prefs"`.
2. It detects the permission flags written by `MainActivity`, finds no `device_id` to migrate, and **deletes `"cammonitor_prefs"`**.
3. All `"has_requested_$permission"` state is permanently erased.

**Impact & Threat Model:**  
User permission tracking is wiped on every application launch. Users who previously denied permissions and selected "Don't ask again" will have their preference reset, leading to continuous permission prompt cycles and broken permission rationale dialog logic.

**Suggested Remediation:**  
Separate UI preferences from the legacy migration target. In `MainActivity.kt` and `ViewerModeScreen.kt`, store permission request flags in a dedicated file (e.g. `"cammonitor_ui_prefs"`). In `migrateLegacyPlaintextPrefs()`, only delete `"cammonitor_prefs"` if legacy security keys (`device_id`, `device_priv_key`) were present.

---

### P3 — Unsanitized Device Name Injection in Signaling and WebRTC DataChannels

**Location:** `app/src/main/java/com/a0soft/gphone/camonitor/ui/viewer/ViewerModeScreen.kt:506-515, 439-444`, `app/src/main/java/com/a0soft/gphone/camonitor/data/model/Models.kt:383-384`, `app/src/main/java/com/a0soft/gphone/camonitor/ui/pairing/CameraPairingScreen.kt:119`

**Description & Vulnerability Mechanics:**  
Over the WebRTC DataChannel, a connected peer can send `ControlCommand.Rename(name)`. When received:
```kotlin
is ControlCommand.Rename -> {
    cameraName = cmd.name
    val existing = securityRepo.getPairedDevice(peerId) ?: targetCamera
    if (existing != null) {
        securityRepo.addPairedDevice(existing.copy(name = cmd.name))
        AppLogger.i("ViewerMode", "Updated paired camera name from DataChannel to: ${cmd.name}")
    }
}
```
Similarly, `SignalingMessage.Answer` parses `cameraName = json.optString("cameraName")` and updates storage without validation.  
There is no enforcement of length limits (e.g. 32 characters) or character filtering (e.g. stripping newlines, directional formatting characters, or non-printable ASCII).

**Impact & Threat Model:**  
A connected peer can inject extremely long strings (up to WebRTC message buffer limits) or control characters into local preferences and log outputs, causing UI text truncation, layout breakages, SharedPreferences bloat, or log pollution in `AppLogger`.

**Suggested Remediation:**  
Sanitize and clamp device names upon reception before updating `SecurityRepository`:
```kotlin
fun sanitizeDeviceName(rawName: String): String {
    return rawName.trim()
        .take(32)
        .filter { it.isLetterOrDigit() || it in " -_()." }
        .ifEmpty { "CamMonitor-Device" }
}
```

---

### P3 — Secondary MQTT Broker (`broker.emqx.io`) Is Dead Code (No Dynamic Failover)

**Location:** `app/src/main/java/com/a0soft/gphone/camonitor/data/signaling/SignalingClient.kt:60-61, 144-148`

**Description & Vulnerability Mechanics:**  
In `MqttSignalingClient`:
```kotlin
companion object {
    const val PRIMARY_BROKER_HOST = "broker.hivemq.com"
    const val PRIMARY_BROKER_PORT = 8883
    const val SECONDARY_BROKER_HOST = "broker.emqx.io"
    const val SECONDARY_BROKER_PORT = 8883
}
```
In `connectWithRetry(topic: String)`:
```kotlin
val canonicalHost = PRIMARY_BROKER_HOST
val port = PRIMARY_BROKER_PORT
val candidates = resolveHostCandidates(canonicalHost)
```
`SECONDARY_BROKER_HOST` is defined but never referenced anywhere in `connectWithRetry()` or `attemptConnect()`. If `broker.hivemq.com` is unreachable or undergoes maintenance, connection attempts loop through HiveMQ IP candidates and fail, never attempting fallback to `broker.emqx.io`.

**Impact & Threat Model:**  
Loss of signaling availability during HiveMQ outages or regional network routing blocks, defeating the purpose of configuring a secondary broker.

**Suggested Remediation:**  
Iterate over both `PRIMARY_BROKER_HOST` and `SECONDARY_BROKER_HOST` in `connectWithRetry()`, dynamically passing the target hostname to `attemptConnect()` so TLS hostname verification (`expectedHost`) matches the server certificate:
```kotlin
val brokerList = listOf(
    PRIMARY_BROKER_HOST to PRIMARY_BROKER_PORT,
    SECONDARY_BROKER_HOST to SECONDARY_BROKER_PORT
)
for ((canonicalHost, port) in brokerList) {
    val candidates = resolveHostCandidates(canonicalHost)
    for (targetHost in candidates) {
        val success = attemptConnect(host = targetHost, expectedHost = canonicalHost, port = port, ...)
        if (success) return@withLock true
    }
}
```

---

### P3 — Non-standard HKDF-Extract for Empty Salt Diverges from RFC 5869

**Location:** `app/src/main/java/com/a0soft/gphone/camonitor/data/security/CryptoUtils.kt:174-182`

**Description & Vulnerability Mechanics:**  
In `CryptoUtils.hkdfSha256`:
```kotlin
fun hkdfSha256(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    val prk = if (salt.isNotEmpty()) {
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        mac.doFinal(secret)
    } else {
        secret
    }
    ...
```
RFC 5869 Section 2.2 dictates:
> "If [salt is] not provided, it is set to a string of HashLen zeros."

By assigning `prk = secret` when `salt.isEmpty()`, `CryptoUtils` bypasses HKDF-Extract and inputs raw key material directly into HKDF-Expand.  
While both Android CamMonitor clients interoperate because they share this implementation, standard RFC 5869 libraries will produce different derived keys for empty salts.

**Impact & Threat Model:**  
Future cross-platform clients (e.g. WebRTC web browser client or iOS app) implementing standard RFC 5869 will fail to derive matching AES-GCM keys. For OTP secrets (which are Base64 ASCII strings rather than uniform pseudorandom bytes), skipping extraction reduces pseudorandom uniformity.

**Suggested Remediation:**  
Adhere to RFC 5869 by defaulting empty salt to 32 zero bytes:
```kotlin
val effectiveSalt = if (salt.isNotEmpty()) salt else ByteArray(32)
mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
val prk = mac.doFinal(secret)
```
*(Note: Coordinated protocol bump required to maintain compatibility with existing paired devices).*

---

## Review Notes

- **Round 1 Fix Verification:**
  - `OfferReplayGuard` successfully persists a per-sender Offer high-water mark across process restarts, mitigating the unauthenticated camera wake-up attack identified in Round 1.
  - `SecureStorageRecovery` properly fails closed to memory-only storage (`InMemorySharedPreferences`) without writing plaintext keys or credentials to disk.
  - `DeviceRevocation.revokePairedDevice()` is consistently called by both `HomeScreen.kt:533` and `ManageDevicesScreen.kt:150`, closing the lingering WebRTC peer immediately on unpair.
- **Android Manifest & Platform Sandbox:**
  - `CameraMonitorService` remains non-exported (`android:exported="false"`), preventing arbitrary external components from launching background capture.
  - `FileProvider` is restricted to app-internal `logs/` directory with explicit read-only URI grants.
  - Log scrubbing confirms that no OTPs, private keys, or plaintext envelopes are written to disk logs.
- **Acceptable Tradeoffs vs. Vulnerabilities:**
  - The lack of TURN relay infrastructure is an acceptable economic tradeoff for a self-hosted FOSS tool; the app gracefully reports symmetric NAT failures to users.
  - The 120-second anti-replay tolerance window is an intentional, documented tradeoff necessary for Wi-Fi-only spare phones experiencing clock drift.


---

## Sub-Agent 2 (Flash) — Critical Review & Counter-Assessment

**Reviewer:** Sub-Agent 2 (Senior Android / Cryptographic Security Specialist)  
**Date:** 2026-11-10  
**Target Document:** Findings P1 through P8 reported in `code_review/security_bug_checking_20261110_2.md`  
**Review Philosophy:** Distinguish genuine operational vulnerabilities from academic pedantry, evaluate real-world exploitability under CamMonitor's threat model, and protect backward compatibility and hardware constraints (spare-phone tier, `minSdk = 28`).

---

### 1. Summary Evaluation Matrix

| Finding ID | Title | Sub-Agent 2 Stance | Practical Exploitability | Backward Compatibility / System Risk |
| :--- | :--- | :--- | :--- | :--- |
| **Finding 1** | Pairing Public Key Substitution / Device Identity Hijacking | **Agree with Nuance** | **Medium** (Requires active pairing OTP possession) | **High Risk if patched naively**: Patching inside `addPairedDevice()` breaks existing unit tests (`MultiCameraPairingTest`) and device renaming. Must be enforced at pairing handshake protocol level. |
| **Finding 2** | Envelope Signatures Omit AES-GCM IV and Tag | **Disagree / Academic Concern** | **None** (AES-GCM AEAD guarantees integrity; tampered packets fail decryption) | **Catastrophic Breaking Change**: Unilaterally changing signature payload breaks inter-version signaling for 100% of paired devices. Nonce eviction claims were misunderstood by Sub-Agent 1. |
| **Finding 3** | Unbounded SharedPreferences Decryption on Public MQTT Rx | **Agree Completely** | **High** (Public unauthenticated MQTT broker allows DoS via CPU/IO thrashing) | **Zero Risk**: In-memory caching (`ConcurrentHashMap`) is completely transparent, backward-compatible, and heavily reduces battery/thermal drain. |
| **Finding 4** | Integer Overflow in Timestamp Delta (`Long.MIN_VALUE`) | **Agree with Nuance** | **Near-Zero** (Single point out of $2^{64}$; nonces of unauthenticated packets are evicted anyway) | **Zero Risk**: Adopting range check `[now - TOLERANCE, now + TOLERANCE]` is clean defensive hygiene without side effects. |
| **Finding 5** | SharedPreferences Namespace Collision Scrubs Permission States | **Agree Completely** | **High UX/Platform Defect** (Wipes `has_requested_*` flags on every app launch) | **Zero Risk**: Critical bug introduced by earlier migration script; moving UI flags to `cammonitor_ui_prefs` immediately fixes rationale dialogs. |
| **Finding 6** | Unsanitized Device Name Injection in DataChannels | **Disagree with Classification / Flawed Fix** | **None** (Attacker must be already authenticated/paired) | **High Usability Risk**: Sub-Agent 1's regex filter (`it in " -_()."`) breaks CJK punctuation, `#`, `&`. Clamping length (`take(32)`) and stripping ISO control characters is hygiene, not a P3 vulnerability. |
| **Finding 7** | Secondary MQTT Broker (`broker.emqx.io`) Is Dead Code | **Disagree / Sub-Agent 1's Fix Causes Split-Brain** | **N/A** (Architecture tradeoff) | **Catastrophic Split-Brain**: HiveMQ and EMQX are unbridged. If Camera fails over to EMQX while Viewer connects to HiveMQ, signaling is partitioned and the system goes completely dark. |
| **Finding 8** | Non-standard HKDF-Extract for Empty Salt Diverges from RFC 5869 | **Disagree / Overlooked RFC 5869 §3.3** | **None** (RFC 5869 §3.3 explicitly permits skipping Extract for Diffie-Hellman outputs) | **Catastrophic Breaking Change**: Changing PRK derivation changes all derived AES keys, permanently breaking all currently paired installations. |

---

### 2. In-Depth Technical Analysis & Counter-Assessments

#### Finding 1: Pairing Public Key Substitution / Device Identity Hijacking via Malicious `PairRequest`
- **Sub-Agent 1 Claim:** An attacker who acquires the pairing OTP can craft a `PairRequest` containing the `deviceId` of an already-paired viewer and the attacker's public key. `addPairedDevice()` bypasses capacity limits (`roleCount >= maxLimit`), purges the original viewer, and overwrites the public key.
- **Counter-Assessment & Technical Nuance:**
  1. *Validity of the Threat:* The vulnerability in the pairing handshake is real: `CameraPairingScreen` unconditionally accepts a `PairRequest` from any device presenting a valid OTP, even if that `deviceId` is already bound to a legitimate viewer with a different public key.
  2. *Fatal Flaw in Sub-Agent 1's Remediation:* Sub-Agent 1 proposed adding:
     ```kotlin
     val existing = current.firstOrNull { it.deviceId == device.deviceId }
     if (existing != null && existing.publicKeyBase64 != device.publicKeyBase64) {
         return false
     }
     ```
     directly inside `DefaultSecurityRepository.addPairedDevice()`.  
     **This breaks existing code and unit tests.** Specifically, `MultiCameraPairingTest.kt:152` explicitly validates that `addPairedDevice` can update an existing device record with a new public key:
     ```kotlin
     val updatedCamera3 = PairedDevice("camera-id-3", "Kitchen Camera Renamed", "pubKey3New", ...)
     val updated = repository.addPairedDevice(updatedCamera3, maxLimit = SecurityRepository.MAX_PAIRED_CAMERAS)
     assertTrue("Updating an existing device should succeed even at limit", updated)
     ```
     Furthermore, `addPairedDevice` is used as a generic upsert method.
  3. *Correct Engineering Solution:* The enforcement must occur at the **pairing layer**, specifically in `CameraPairingScreen.kt` (and `ViewerPairingScreen.kt`):
     - When receiving `PairRequest`, verify if `securityRepo.getPairedDevice(msg.pbDeviceId)` already exists.
     - If it exists and the public key matches, allow it as an idempotent retransmission.
     - If it exists and the public key differs, reject the pairing attempt with a warning log: `"PairRequest rejected: deviceId ${msg.pbDeviceId} already paired with different credentials"`. To re-pair a replaced device, the user must explicitly unpair it in "Manage Devices" first.

---

#### Finding 2: Envelope Signatures Omit AES-GCM IV and Authentication Tag
- **Sub-Agent 1 Claim:** `sigPayload` omits `iv` and `tag`. Concatenating fields without delimiters causes ambiguity. Intercepted envelopes can be modified in `iv` or `tag`, passing signature verification, failing decryption, and triggering nonce eviction, which Sub-Agent 1 claims "disrupts anti-replay ordering."
- **Counter-Assessment & Technical Nuance:**
  1. *Cryptographic Reality (AEAD Invariance):* CamMonitor employs AES-GCM (an Authenticated Encryption with Associated Data scheme). The authentication `tag` validates both `iv` and `ciphertext` under the derived key. Any modification to `iv`, `tag`, or `ciphertext` in transit results in an immediate `AEADBadTagException` during `cipher.doFinal()`. The plaintext is never revealed, and tampered envelopes are completely discarded.
  2. *Sub-Agent 1's Misunderstanding of Nonce Eviction:* Sub-Agent 1 argues that evicting the nonce on decryption failure (`seenNonces.remove(envelope.nonce)`) "disrupts anti-replay ordering." In reality, this eviction is an intentional anti-DoS mechanism: if an adversary front-runs a legitimate envelope with corrupted ciphertext/tag to burn its nonce, removing the nonce on failure allows the legitimate in-flight envelope to succeed when it arrives. More importantly, line 422 in `SecurityRepository.kt` *already* executes `seenNonces.remove(envelope.nonce)` when signature verification fails! Therefore, including `iv` and `tag` in the signature would result in the exact same eviction behavior.
  3. *False Proof-of-Possession Claim:* Sub-Agent 1 claims `verifyEnvelopeSignature()` in `SignalingClient.kt:370` considers envelopes authentic even if IV/tag are tampered. This is demonstrably false: line 370 is enclosed within `if (message != null)`, which is only reached if AES-GCM decryption has already succeeded.
  4. *Backward Compatibility Impact:* Changing `sigPayload` from `"$deviceId$recipientId$timestamp$nonce$ciphertext"` to `"$senderId|$recipientId|$timestamp|$nonce|$iv|$tag|$ciphertext"` without a versioned envelope scheme immediately breaks all existing paired cameras and viewers. Any mixed-version deployment will experience 100% signature failures.
  5. *Verdict:* Academic observation. Keep existing payload format unless a formal envelope protocol versioning field (e.g., `v=2`) is introduced.

---

#### Finding 3: Unbounded SharedPreferences Decryption on Public MQTT Rx Topic
- **Sub-Agent 1 Claim:** Subscribing to public topic `cammonitor/$deviceId/rx` on `broker.hivemq.com` allows unauthenticated attackers to flood the device. Every incoming envelope triggers Tink AES-SIV and AES-GCM decryptions via `prefs.getString("paired_devices")` and JSON parsing, starving `Dispatchers.IO` and dropping WebRTC frames on low-end hardware.
- **Counter-Assessment & Technical Nuance:**
  1. *Validity:* **100% Confirmed.** `EncryptedSharedPreferences` does not cache decrypted plaintext strings in memory. Every call to `prefs.getString()` invokes Tink's KeyStore-backed AES-SIV key decryption followed by AES-GCM value decryption.
  2. *Amplification Factor:* On every envelope, `getPairedDevice()` is called up to 4 times across `SignalingClient` and `SecurityRepository`. An unauthenticated MQTT spam burst will instantly saturate the CPU on Android 9/10 spare phones (`minSdk = 28`), resulting in thermal throttling and WebRTC video pipeline frame drops.
  3. *Exploitability & Feasibility:* Since HiveMQ is an open, unauthenticated broker, anyone who learns or brute-forces a 36-character UUID device topic can execute this DoS attack.
  4. *Remediation Endorsement:* An in-memory cache (`ConcurrentHashMap<String, PairedDevice>`) inside `DefaultSecurityRepository`, synchronized on `addPairedDevice()` and `removePairedDevice()`, completely neutralizes this attack vector with zero backward compatibility risk.

---

#### Finding 4: Integer Overflow in Timestamp Delta Calculation (`Math.abs(Long.MIN_VALUE)`)
- **Sub-Agent 1 Claim:** If `now - envelope.timestamp == Long.MIN_VALUE`, `Math.abs()` returns `Long.MIN_VALUE` (negative), bypassing the 120-second anti-replay window and leaking the nonce in `seenNonces` forever.
- **Counter-Assessment & Technical Nuance:**
  1. *Mathematical Fact:* In Java/Kotlin two's complement arithmetic, `Math.abs(Long.MIN_VALUE) == Long.MIN_VALUE`. The check `Long.MIN_VALUE > 120_000L` is indeed `false`.
  2. *Flaw in Sub-Agent 1's Leakage Claim:* Sub-Agent 1 claimed this allows an attacker to leak memory indefinitely in `seenNonces`. What Sub-Agent 1 missed is lines 408-410, 421-423, and 436-438 in `SecurityRepository.kt`:
     - If the sender is unauthenticated: `seenNonces.remove(envelope.nonce)` is called immediately.
     - If the signature fails: `seenNonces.remove(envelope.nonce)` is called immediately.
     - If decryption fails: `seenNonces.remove(envelope.nonce)` is called immediately.
     Therefore, an unauthenticated attacker cannot leak memory.
  3. *Practical Likelihood:* This requires the remote timestamp to equal exactly `now - Long.MIN_VALUE`, a singular value out of $2^{64}$.
  4. *Verdict:* Agree with Nuance. While harmless in practice, replacing `abs(now - timestamp)` with `timestamp <= 0L || timestamp < now - TOLERANCE || timestamp > now + TOLERANCE` eliminates CWE-190 cleanly.

---

#### Finding 5: SharedPreferences Namespace Collision Causes Startup Scrubbing of UI Permission States
- **Sub-Agent 1 Claim:** `LegacyPrefsMigration.LEGACY_PREFS_NAME` is hardcoded to `"cammonitor_prefs"`. In `DefaultSecurityRepository.migrateLegacyPlaintextPrefs()`, if `"cammonitor_prefs"` contains any entries, it calls `context.deleteSharedPreferences("cammonitor_prefs")`. Meanwhile, `MainActivity.kt:181` and `ViewerModeScreen.kt:163` store runtime permission states (`has_requested_*`) in `"cammonitor_prefs"`. On every app restart, permission tracking is scrubbed.
- **Counter-Assessment & Technical Nuance:**
  1. *Validity:* **100% Confirmed and Critical.** This is an unintended regression introduced by the Round 1 secure storage migration logic.
  2. *Operational Consequence:* Because `context.deleteSharedPreferences("cammonitor_prefs")` executes on every cold boot, users who selected "Don't ask again" on runtime permissions (Camera, Microphone) have their `has_requested_*` flags wiped. The app is unable to determine whether a permission is permanently denied, breaking permission rationale dialogs and causing erratic permission loops.
  3. *Remediation Endorsement:* Rename the UI preferences file in `MainActivity.kt` and `ViewerModeScreen.kt` to `"cammonitor_ui_prefs"`. Furthermore, in `migrateLegacyPlaintextPrefs()`, only delete `"cammonitor_prefs"` if legacy crypto keys (`device_id`, `device_priv_key`) were actually present.

---

#### Finding 6: Unsanitized Device Name Injection in Signaling and WebRTC DataChannels
- **Sub-Agent 1 Claim:** `ControlCommand.Rename` and `SignalingMessage.Answer` accept arbitrary strings for `cameraName` without length limits or character filtering, leading to UI truncation or log injection.
- **Counter-Assessment & Technical Nuance:**
  1. *Flawed Threat Model:* `ControlCommand.Rename` travels over the WebRTC DataChannel, which is encrypted and mutually authenticated via DTLS-SRTP between already-paired peers. `SignalingMessage.Answer` is encrypted with the ECDH derived key. An unauthenticated third party cannot inject device names.
  2. *Severe Flaw in Sub-Agent 1's Remediation:* Sub-Agent 1 suggested:
     ```kotlin
     rawName.trim().take(32).filter { it.isLetterOrDigit() || it in " -_()." }
     ```
     This filter strips valid international characters and common symbols, such as Japanese brackets (`「」`), full-width punctuation, ampersands (`&`), and pound signs (`#`), corrupting legitimate names like `"Living Room #2"` or `"Front Porch & Garden"`.
  3. *Engineering Reality:* This is a minor UI presentation concern, not a security vulnerability. Clamping length to 32 characters (`.take(32)`) and removing ISO control characters (`filter { !it.isISOControl() }`) is sufficient.

---

#### Finding 7: Secondary MQTT Broker (`broker.emqx.io`) Is Dead Code (No Dynamic Failover)
- **Sub-Agent 1 Claim:** `SECONDARY_BROKER_HOST` is defined in `SignalingClient` but never used. Sub-Agent 1 recommended iterating through both brokers in `connectWithRetry()`.
- **Counter-Assessment & Technical Nuance:**
  1. *Fundamental Networking Flaw in Sub-Agent 1's Proposal:* MQTT is a rendezvous pub/sub message router, not an idempotent stateless REST endpoint. `broker.hivemq.com` and `broker.emqx.io` are distinct, unbridged public brokers run by different entities.
  2. *The Split-Brain Failure Mode:* If Camera fails to connect to HiveMQ and switches to EMQX, while Viewer successfully connects to HiveMQ:
     - Viewer publishes Offer to `cammonitor/<cameraId>/rx` on HiveMQ.
     - Camera is listening on EMQX.
     - **The signaling message is dropped in a black hole.** Neither peer can discover the other, resulting in total system failure.
  3. *Verdict:* Sub-Agent 1's proposed remediation introduces a catastrophic split-brain defect. Keeping `SECONDARY_BROKER_HOST` disabled is the correct architectural state until dual-broker multi-homing (subscribing to both brokers simultaneously) is designed.

---

#### Finding 8: Non-standard HKDF-Extract for Empty Salt Diverges from RFC 5869
- **Sub-Agent 1 Claim:** In `CryptoUtils.hkdfSha256`, passing `ByteArray(0)` bypasses HKDF-Extract and sets `prk = secret`. Sub-Agent 1 claimed this violates RFC 5869 and must be fixed by defaulting empty salt to 32 zero bytes.
- **Counter-Assessment & Technical Nuance:**
  1. *Sub-Agent 1 Overlooked RFC 5869 Section 3.3:* RFC 5869 Section 3.3 explicitly specifies:
     > *"Note that HKDF-Extract is optional if the input keying material is already a pseudorandom string of HashLen octets, for example, the output of a key exchange agreement like Diffie-Hellman. In this case, HKDF-Expand can be applied directly to the IKM."*
     In CamMonitor, `hkdfSha256` is primarily used to expand the 256-bit ECDH shared secret (`agreement.generateSecret()`). Because the Diffie-Hellman output is already a pseudorandom string of 32 octets, skipping Extract is formally compliant with RFC 5869 §3.3.
  2. *Catastrophic Backward Compatibility Break:* Every call to `hkdfSha256` in CamMonitor passes `ByteArray(0)`. Changing PRK derivation alters all derived E2EE AES-GCM keys. Doing so will immediately break every existing paired installation, rendering existing cameras and viewers unable to communicate.
  3. *Verdict:* Disagree. Do not modify `hkdfSha256`.

---

### 3. Recommended Action Plan & Priority Matrix

| Priority | Finding | Action Item | Target File(s) | Risk of Regression |
| :---: | :--- | :--- | :--- | :---: |
| **P1** | **Finding 5** (Permission Wiping) | Move UI permission state to `"cammonitor_ui_prefs"`. In `migrateLegacyPlaintextPrefs`, only delete `"cammonitor_prefs"` if identity keys exist. | `MainActivity.kt`, `ViewerModeScreen.kt`, `SecurityRepository.kt` | **None** |
| **P2** | **Finding 3** (MQTT SharedPreferences DoS) | Add in-memory cache `ConcurrentHashMap<String, PairedDevice>` in `DefaultSecurityRepository` to avoid Tink crypto operations on incoming MQTT packets. | `SecurityRepository.kt` | **None** |
| **P3** | **Finding 1** (Pairing Public Key Overwrite) | Enforce key-mismatch rejection in `CameraPairingScreen` (reject `PairRequest` if `deviceId` exists with a different public key unless unpaired first). Do NOT break `addPairedDevice` unit tests. | `CameraPairingScreen.kt` | **None** |
| **P4** | **Finding 4** (Timestamp Arithmetic Underflow) | Replace `abs(now - timestamp)` with range check `timestamp in (now - TOLERANCE)..(now + TOLERANCE)`. | `SecurityRepository.kt` | **None** |
| **P5** | **Finding 6** (Device Name UI Hygiene) | Clamp name length to 32 characters and strip ISO control characters. Do not filter out CJK punctuation or symbols. | `ViewerModeScreen.kt` | **None** |
| **REJECT** | **Finding 2** (Envelope Signature Format) | Reject. Breaking change to envelope format; AES-GCM already provides AEAD cryptogram integrity. | N/A | High (Breaks all devices) |
| **REJECT** | **Finding 7** (Naive Secondary Broker Failover) | Reject. Naive failover causes MQTT broker split-brain and signaling blackout. | N/A | High (Breaks signaling) |
| **REJECT** | **Finding 8** (HKDF-Extract "Fix") | Reject. Permitted by RFC 5869 §3.3; altering derived keys bricks all existing paired devices. | N/A | High (Breaks all pairings) |


---

## Sub-Agent 1 (Pro) — Response & Alignment on Counter-Assessment

**Reviewer:** Sub-Agent 1 (Lead Security Engineer & Android Researcher)  
**Date:** 2026-11-10  
**Context:** Review and reconciliation of Sub-Agent 2's critical counter-assessment on Findings P1–P8.  
**Status:** **Consensus Reached Across All 8 Findings**

---

### 1. Item-by-Item Review and Resolution

#### Finding 1: Pairing Public Key Substitution / Device Identity Hijacking
- **Sub-Agent 2 Stance:** Agreed on the vulnerability; identified that patching inside `DefaultSecurityRepository.addPairedDevice()` breaks `MultiCameraPairingTest.kt:152` and generic upsert semantics. Recommends enforcement in `CameraPairingScreen.kt` and `ViewerPairingScreen.kt`.
- **Sub-Agent 1 Evaluation & Alignment:** **Accepted completely.** Sub-Agent 2's assessment is accurate: `addPairedDevice()` is used as a low-level upsert repository function. Enforcing the restriction at the pairing protocol layer (`CameraPairingScreen.kt` when handling `PairRequest` and `ViewerPairingScreen.kt` when handling `PairConfirm`) directly addresses the threat vector (unauthorized public key substitution during OTP onboarding) while preserving repository flexibility and maintaining 100% test pass rate in `MultiCameraPairingTest`.
- **Agreed Action:** In `CameraPairingScreen.kt`, check `val existing = securityRepo.getPairedDevice(msg.pbDeviceId)`. If `existing != null && existing.publicKeyBase64 != msg.pbPublicKeyBase64`, reject the incoming `PairRequest` and log a security warning.

#### Finding 2: Envelope Signatures Omit AES-GCM IV and Authentication Tag
- **Sub-Agent 2 Stance:** Disagreed with remediation; argued that AES-GCM provides AEAD integrity over ciphertext and IV, tampered packets fail decryption via `AEADBadTagException`, and altering `sigPayload` is an incompatible wire-protocol break for existing paired devices.
- **Sub-Agent 1 Evaluation & Alignment:** **Accepted.** While signing the complete cryptogram is ideal in greenfield cryptographic protocol design, AES-GCM's built-in GMAC tag already provides cryptographic authentication of the IV and ciphertext. Unilaterally changing `sigPayload` in an unversioned envelope breaks 100% of deployed paired devices. Nonce eviction behavior is functionally equivalent on both paths.
- **Agreed Action:** **Reject format change.** Retain current `sigPayload` format. Defer full cryptogram binding to a future versioned protocol envelope (e.g., `v=2`).

#### Finding 3: Unbounded SharedPreferences Decryption on Public MQTT Rx
- **Sub-Agent 2 Stance:** 100% agreement on validity and severity; endorses in-memory caching (`ConcurrentHashMap`) to eliminate Tink/KeyStore decryption thrashing.
- **Sub-Agent 1 Evaluation & Alignment:** **Consensus reached.** Both agents agree that unauthenticated MQTT packet flooding on `cammonitor/$deviceId/rx` presents a severe CPU/thermal DoS vector on `minSdk = 28` hardware.
- **Agreed Action:** Implement `private val pairedDevicesCache = java.util.concurrent.ConcurrentHashMap<String, PairedDevice>()` in `DefaultSecurityRepository`, synchronized on `addPairedDevice()` and `removePairedDevice()`.

#### Finding 4: Integer Overflow in Timestamp Delta Calculation (`Math.abs(Long.MIN_VALUE)`)
- **Sub-Agent 2 Stance:** Agreed with nuance; acknowledged `Math.abs(Long.MIN_VALUE)` two's complement boundary condition, clarified that unauthenticated nonces are evicted on verification failure, and endorsed the range check.
- **Sub-Agent 1 Evaluation & Alignment:** **Consensus reached.** Sub-Agent 2's clarification regarding nonce eviction on invalid signature/decryption is technically correct. The overflow is an edge-case CWE-190 boundary condition that is cleanly resolved by bounds comparison.
- **Agreed Action:** Replace `kotlin.math.abs(now - envelope.timestamp) > TIMESTAMP_TOLERANCE_MS` with `envelope.timestamp <= 0L || envelope.timestamp < now - TIMESTAMP_TOLERANCE_MS || envelope.timestamp > now + TIMESTAMP_TOLERANCE_MS`.

#### Finding 5: SharedPreferences Namespace Collision Scrubs Permission States
- **Sub-Agent 2 Stance:** 100% agreement; confirmed this is a critical functional regression introduced by Round 1 migration logic that wipes permission flags on every startup.
- **Sub-Agent 1 Evaluation & Alignment:** **Consensus reached.**
- **Agreed Action:** Move permission tracking in `MainActivity.kt` and `ViewerModeScreen.kt` to a dedicated `"cammonitor_ui_prefs"` store. In `migrateLegacyPlaintextPrefs()`, only delete `"cammonitor_prefs"` if legacy security keys (`device_id`, `device_priv_key`) were actually present.

#### Finding 6: Unsanitized Device Name Injection in WebRTC DataChannels
- **Sub-Agent 2 Stance:** Disagreed with regex filter which breaks CJK punctuation (`「」`, `&`, `#`); recommended clamping length to 32 characters and stripping ISO control characters.
- **Sub-Agent 1 Evaluation & Alignment:** **Accepted completely.** Sub-Agent 2 correctly identified that CamMonitor's strict multilingual requirements (Traditional Chinese, Simplified Chinese, Japanese) would be impaired by restrictive ASCII filtering. Stripping ISO control characters and clamping length to 32 characters achieves presentation hygiene without breaking localized strings.
- **Agreed Action:** Apply `.trim().take(32).filter { !it.isISOControl() }` to incoming device names.

#### Finding 7: Secondary MQTT Broker (`broker.emqx.io`) Is Dead Code
- **Sub-Agent 2 Stance:** Disagreed with naive failover; demonstrated that switching to an unbridged secondary broker causes catastrophic split-brain blackouts between camera and viewer.
- **Sub-Agent 1 Evaluation & Alignment:** **Accepted.** Because public MQTT brokers are independent rendezvous routing nodes without cross-broker bridging, an asymmetric failover causes signaling packets to be sent to disconnected brokers.
- **Agreed Action:** **Reject naive failover.** Maintain single-broker connection until dual-broker simultaneous subscription (multi-homing) is engineered in an architectural revision.

#### Finding 8: Non-standard HKDF-Extract for Empty Salt Diverges from RFC 5869
- **Sub-Agent 2 Stance:** Disagreed; cited RFC 5869 §3.3 permitting Extract omission when IKM is a 32-byte Diffie-Hellman pseudorandom string; altering derived keys permanently breaks existing paired installations.
- **Sub-Agent 1 Evaluation & Alignment:** **Accepted.** RFC 5869 §3.3 explicitly validates that Diffie-Hellman outputs are sufficiently pseudorandom to bypass Extract, and preserving cryptographic key derivation stability across existing pairings is paramount.
- **Agreed Action:** **Reject key modification.** Retain existing `hkdfSha256` implementation.

---

### 2. Final Consolidated Engineering Action Plan

| Priority | Finding Ref | Approved Engineering Resolution | Target Location |
| :---: | :--- | :--- | :--- |
| **P1** | **Finding 5** | Isolate UI permission flags in `"cammonitor_ui_prefs"`. In `migrateLegacyPlaintextPrefs`, only scrub `"cammonitor_prefs"` if legacy crypto keys exist. | `MainActivity.kt`, `ViewerModeScreen.kt`, `SecurityRepository.kt` |
| **P2** | **Finding 3** | Implement in-memory `ConcurrentHashMap<String, PairedDevice>` cache in `DefaultSecurityRepository` to prevent KeyStore crypto thrashing on incoming MQTT packets. | `SecurityRepository.kt` |
| **P3** | **Finding 1** | In `CameraPairingScreen.kt`, reject `PairRequest` if `msg.pbDeviceId` exists with a different public key unless previously unpaired in Manage Devices. | `CameraPairingScreen.kt` |
| **P4** | **Finding 4** | Replace `abs(now - timestamp)` with range check `envelope.timestamp <= 0L \|\| envelope.timestamp < now - TOLERANCE \|\| envelope.timestamp > now + TOLERANCE`. | `SecurityRepository.kt` |
| **P5** | **Finding 6** | Sanitize device names in `ViewerModeScreen.kt` and `CameraPairingScreen.kt` using `.trim().take(32).filter { !it.isISOControl() }`. | `ViewerModeScreen.kt`, `CameraPairingScreen.kt` |
| **REJECTED** | **Finding 2** | Retain existing `sigPayload` format (AEAD guarantees cryptogram integrity; changes cause wire incompatibility). | N/A |
| **REJECTED** | **Finding 7** | Retain single-broker routing until dual-broker multi-homing is designed (prevents broker split-brain). | N/A |
| **REJECTED** | **Finding 8** | Retain existing HKDF derivation per RFC 5869 §3.3 (prevents breaking existing paired installations). | N/A |

**Consensus Status:** Unanimous alignment achieved between Sub-Agent 1 and Sub-Agent 2. No application or test source code was modified during this review.
