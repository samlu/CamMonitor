package com.a0soft.gphone.camonitor.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.model.SignalingEnvelope
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.time.TimeProvider
import org.json.JSONArray
import java.security.KeyPair
import java.util.UUID

interface SecurityRepository {
    fun getDeviceId(): String
    fun getDeviceName(): String
    fun setDeviceName(name: String)
    fun getDevicePublicKeyBase64(): String
    fun getKeyPair(): KeyPair

    // Pairing OTP
    fun generatePairingOtp(): String
    fun setActivePairingOtp(otp: String)
    fun getActivePairingOtp(): String?
    fun clearPairingOtp()

    // Whitelist
    companion object {
        const val MAX_PAIRED_CAMERAS = 8
        const val MAX_PAIRED_VIEWERS = 2
    }

    fun getPairedDevices(): List<PairedDevice>
    fun getPairedCameras(): List<PairedDevice> = getPairedDevices().filter { it.role != "VIEWER" }
    fun getPairedViewers(): List<PairedDevice> = getPairedDevices().filter { it.role != "CAMERA" }
    fun addPairedDevice(device: PairedDevice, maxLimit: Int = MAX_PAIRED_CAMERAS): Boolean
    fun removePairedDevice(deviceId: String)
    fun isDeviceWhitelisted(deviceId: String): Boolean
    fun getPairedDevice(deviceId: String): PairedDevice?

    // Envelope Security
    fun createEnvelope(recipientId: String, plaintextJson: String, secretOrDerivedKey: ByteArray): SignalingEnvelope
    fun verifyAndDecryptEnvelope(envelope: SignalingEnvelope, secretOrDerivedKey: ByteArray, isOtpKey: Boolean = false): String?

    /**
     * Persisted anti-replay for SDP Offers: returns false if [timestamp] is not strictly newer than
     * the last accepted Offer from [senderId]. Survives process restarts.
     */
    fun acceptOfferTimestamp(senderId: String, timestamp: Long): Boolean = true

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

    // Preferences / Settings
    fun isViewerAudioPlaybackEnabled(): Boolean
    fun setViewerAudioPlaybackEnabled(enabled: Boolean)
    fun getNightModeSetting(): com.a0soft.gphone.camonitor.data.model.NightModeSetting = com.a0soft.gphone.camonitor.data.model.NightModeSetting.AUTO
    fun setNightModeSetting(setting: com.a0soft.gphone.camonitor.data.model.NightModeSetting) {}
    fun getTypicalConnectDurationMs(cameraDeviceId: String): Long? = null
    fun recordConnectDurationMs(cameraDeviceId: String, durationMs: Long) {}
}

class DefaultSecurityRepository(
    context: Context,
    private val timeProvider: TimeProvider
) : SecurityRepository {

    companion object {
        const val TIMESTAMP_TOLERANCE_MS = 120_000L // 2 minutes window for clock drift tolerance
        const val NONCE_RETENTION_MS = 120_000L
        const val SECURE_PREFS_NAME = "cammonitor_secure_prefs"
        const val MAX_CONNECT_DURATION_SAMPLES = 3
        const val MIN_VALID_CONNECT_DURATION_MS = 100L
        const val MAX_VALID_CONNECT_DURATION_MS = 30_000L

        fun updateConnectDurationSamples(existing: List<Long>, newDurationMs: Long): List<Long> {
            val clamped = newDurationMs.coerceIn(MIN_VALID_CONNECT_DURATION_MS, MAX_VALID_CONNECT_DURATION_MS)
            return (existing + clamped).takeLast(MAX_CONNECT_DURATION_SAMPLES)
        }
    }

    /** True when the encrypted store could not be opened and identity/pairings are memory-only. */
    @Volatile
    var isStorageEphemeral: Boolean = false
        private set

    private val prefs: SharedPreferences by lazy { openSecurePrefs(context) }

    private fun openSecurePrefs(context: Context): SharedPreferences {
        val result = SecureStorageRecovery.open(
            create = {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            },
            wipe = {
                AppLogger.e("SecurityRepository", "Secure storage unreadable twice; wiping it. Paired devices must be re-paired.")
                context.deleteSharedPreferences(SECURE_PREFS_NAME)
                pairedDevicesCache.clear()
                isCacheLoaded = true
                try {
                    java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                        .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                } catch (e: Exception) {
                    AppLogger.w("SecurityRepository", "Failed deleting master key alias: ${e.message}")
                }
            },
            onAttemptFailed = { attempt, e ->
                AppLogger.e("SecurityRepository", "Secure storage attempt #$attempt failed: ${e.message}", e)
            }
        )
        val secure = result.value
        if (secure == null) {
            // Fail closed: never persist the identity key or pairings in plaintext.
            isStorageEphemeral = true
            AppLogger.e("SecurityRepository", "SECURE STORAGE UNAVAILABLE: identity and pairings are memory-only for this process")
            return InMemorySharedPreferences()
        }
        migrateLegacyPlaintextPrefs(context, secure)
        return secure
    }

    /** Moves (or at least scrubs) the plaintext file left behind by earlier builds' fallback. */
    private fun migrateLegacyPlaintextPrefs(context: Context, secure: SharedPreferences) {
        val legacy = context.getSharedPreferences(LegacyPrefsMigration.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyAll = legacy.all
        if (legacyAll.isEmpty() || !LegacyPrefsMigration.hasLegacyIdentity(legacyAll)) return
        val entries = LegacyPrefsMigration.entriesToMigrate(legacyAll, secure.contains("device_id"))
        if (entries.isNotEmpty()) {
            val editor = secure.edit()
            entries.forEach { (k, v) ->
                when (v) {
                    is String -> editor.putString(k, v)
                    is Boolean -> editor.putBoolean(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Float -> editor.putFloat(k, v)
                    is Set<*> -> editor.putStringSet(k, v.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
        }
        context.deleteSharedPreferences(LegacyPrefsMigration.LEGACY_PREFS_NAME)
        AppLogger.i("SecurityRepository", "Legacy plaintext preferences scrubbed (migrated=${entries.size} entries)")
    }

    private val offerReplayGuard = OfferReplayGuard(object : OfferTimestampStore {
        override fun get(senderId: String): Long? =
            prefs.getLong("offer_hwm_$senderId", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
        override fun put(senderId: String, timestamp: Long) {
            prefs.edit().putLong("offer_hwm_$senderId", timestamp).apply()
        }
        override fun remove(senderId: String) {
            prefs.edit().remove("offer_hwm_$senderId").apply()
        }
    })

    override fun acceptOfferTimestamp(senderId: String, timestamp: Long): Boolean =
        offerReplayGuard.accept(senderId, timestamp)

    private val deviceId: String
    private var keyPair: KeyPair
    private var activeOtp: String? = null
    private var otpExpiresAt: Long = 0L

    // In-memory nonce cache with timestamps for anti-replay
    private val seenNonces = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        deviceId = id

        val alias = "cammonitor_device_key_$deviceId"

        // Ensure migration from old keys that lacked PURPOSE_AGREE_KEY
        val keyMigrated = prefs.getBoolean("key_ecdh_capable_v2", false)
        if (!keyMigrated) {
            try {
                val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                ks.deleteEntry(alias)
            } catch (_: Exception) {}
            prefs.edit()
                .remove("paired_devices")
                .remove("device_priv_key")
                .remove("device_pub_key")
                .remove("key_using_software_fallback")
                .putBoolean("key_ecdh_capable_v2", true)
                .apply()
        }

        val usingSoftwareFallback = prefs.getBoolean("key_using_software_fallback", false)
        keyPair = try {
            if (!usingSoftwareFallback && !isStorageEphemeral && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                CryptoUtils.getOrCreateDeviceKeyPair(alias)
            } else {
                getOrCreateSoftwareKeyPair()
            }
        } catch (e: Exception) {
            AppLogger.w("SecurityRepository", "KeyStore ECDH unavailable, using persistent software key: ${e.message}")
            prefs.edit().putBoolean("key_using_software_fallback", true).apply()
            getOrCreateSoftwareKeyPair()
        }
    }

    private fun getOrCreateSoftwareKeyPair(): KeyPair {
        val storedPriv = prefs.getString("device_priv_key", null)
        val storedPub = prefs.getString("device_pub_key", null)
        if (storedPriv != null && storedPub != null) {
            return try {
                CryptoUtils.softwareKeyPairFromBase64(storedPriv, storedPub)
            } catch (e: Exception) {
                AppLogger.w("SecurityRepository", "Failed decoding software key, generating new: ${e.message}")
                generateAndSaveSoftwareKeyPair()
            }
        }
        return generateAndSaveSoftwareKeyPair()
    }

    private fun generateAndSaveSoftwareKeyPair(): KeyPair {
        val swKp = CryptoUtils.generateSoftwareEcKeyPair()
        val (priv, pub) = CryptoUtils.softwareKeyPairToBase64(swKp)
        prefs.edit()
            .putString("device_priv_key", priv)
            .putString("device_pub_key", pub)
            .apply()
        return swKp
    }

    override fun getDeviceId(): String = deviceId

    override fun getDeviceName(): String {
        val defaultName = "CamMonitor-" + deviceId.takeLast(4).uppercase()
        return prefs.getString("device_name", defaultName) ?: defaultName
    }

    override fun setDeviceName(name: String) {
        prefs.edit().putString("device_name", name.trim()).apply()
        AppLogger.i("SecurityRepository", "Device name updated: $name")
    }

    override fun getDevicePublicKeyBase64(): String {
        return CryptoUtils.publicKeyToBase64(keyPair.public)
    }

    override fun getKeyPair(): KeyPair = keyPair

    override fun generatePairingOtp(): String {
        val otp = CryptoUtils.generateOtp()
        activeOtp = otp
        otpExpiresAt = timeProvider.currentTimeMillis() + 5 * 60 * 1000L // 5 mins
        return otp
    }

    override fun setActivePairingOtp(otp: String) {
        activeOtp = otp
        otpExpiresAt = timeProvider.currentTimeMillis() + 5 * 60 * 1000L // 5 mins
        AppLogger.i("SecurityRepository", "Active pairing OTP set for handshake")
    }

    override fun getActivePairingOtp(): String? {
        if (activeOtp != null && timeProvider.currentTimeMillis() > otpExpiresAt) {
            activeOtp = null
        }
        return activeOtp
    }

    override fun clearPairingOtp() {
        activeOtp = null
        otpExpiresAt = 0L
    }

    private val pairedDevicesCache = java.util.concurrent.ConcurrentHashMap<String, PairedDevice>()
    @Volatile private var isCacheLoaded = false

    private fun ensureCacheLoaded(): java.util.concurrent.ConcurrentHashMap<String, PairedDevice> {
        if (!isCacheLoaded) {
            synchronized(pairedDevicesCache) {
                if (!isCacheLoaded) {
                    val jsonStr = prefs.getString("paired_devices", "[]") ?: "[]"
                    try {
                        val arr = JSONArray(jsonStr)
                        for (i in 0 until arr.length()) {
                            val dev = PairedDevice.fromJson(arr.getJSONObject(i))
                            pairedDevicesCache[dev.deviceId] = dev
                        }
                    } catch (e: Exception) {
                        AppLogger.e("SecurityRepository", "Failed to parse paired devices into cache", e)
                    }
                    isCacheLoaded = true
                }
            }
        }
        return pairedDevicesCache
    }

    override fun getPairedDevices(): List<PairedDevice> {
        return ensureCacheLoaded().values.toList()
    }

    override fun addPairedDevice(device: PairedDevice, maxLimit: Int): Boolean {
        val cache = ensureCacheLoaded()
        val current = cache.values.toMutableList()
        val roleCount = when (device.role) {
            "CAMERA" -> current.count { it.role != "VIEWER" }
            "VIEWER" -> current.count { it.role != "CAMERA" }
            else -> current.size
        }
        if (roleCount >= maxLimit && !cache.containsKey(device.deviceId)) {
            AppLogger.w("SecurityRepository", "Cannot add paired device: Max $maxLimit devices reached for role ${device.role ?: "ALL"}")
            return false
        }
        cache[device.deviceId] = device
        savePairedDevices(cache.values.toList())
        AppLogger.i("SecurityRepository", "Paired new device: ${device.name} (${device.deviceId}) [roleCount=${roleCount + 1}/$maxLimit]")
        return true
    }

    override fun removePairedDevice(deviceId: String) {
        val cache = ensureCacheLoaded()
        cache.remove(deviceId)
        savePairedDevices(cache.values.toList())
        prefs.edit().remove("connect_durations_$deviceId").apply()
        offerReplayGuard.forget(deviceId)
        AppLogger.i("SecurityRepository", "Removed paired device: $deviceId")
    }

    override fun isDeviceWhitelisted(deviceId: String): Boolean {
        return ensureCacheLoaded().containsKey(deviceId)
    }

    override fun getPairedDevice(deviceId: String): PairedDevice? {
        return ensureCacheLoaded()[deviceId]
    }

    private fun savePairedDevices(list: List<PairedDevice>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("paired_devices", arr.toString()).apply()
    }

    override fun createEnvelope(
        recipientId: String,
        plaintextJson: String,
        secretOrDerivedKey: ByteArray
    ): SignalingEnvelope {
        val timestamp = timeProvider.currentTimeMillis()
        val nonce = CryptoUtils.generateNonce()

        val (ciphertext, iv, tag) = CryptoUtils.encryptAesGcm(
            plaintextJson.toByteArray(Charsets.UTF_8),
            secretOrDerivedKey
        )

        // Signature input: senderId + recipientId + timestamp + nonce + ciphertext
        val sigPayload = "$deviceId$recipientId$timestamp$nonce$ciphertext".toByteArray(Charsets.UTF_8)
        val signature = CryptoUtils.sign(sigPayload, keyPair.private)

        return SignalingEnvelope(
            senderId = deviceId,
            recipientId = recipientId,
            timestamp = timestamp,
            nonce = nonce,
            ciphertext = ciphertext,
            iv = iv,
            tag = tag,
            signature = signature
        )
    }

    override fun verifyAndDecryptEnvelope(
        envelope: SignalingEnvelope,
        secretOrDerivedKey: ByteArray,
        isOtpKey: Boolean
    ): String? {
        // 0. Recipient ID Check (S-03)
        if (envelope.recipientId != deviceId) {
            AppLogger.w("SecurityRepository", "Envelope discarded: recipientId mismatch (${envelope.recipientId} != $deviceId)")
            return null
        }

        val now = timeProvider.currentTimeMillis()

        // 1. Anti-Replay Timestamp Check (120 seconds window for clock drift tolerance, overflow-safe)
        if (envelope.timestamp <= 0L || envelope.timestamp < now - TIMESTAMP_TOLERANCE_MS || envelope.timestamp > now + TIMESTAMP_TOLERANCE_MS) {
            AppLogger.w("SecurityRepository", "Envelope discarded: Timestamp expired or invalid (timestamp: ${envelope.timestamp}, now: $now)")
            return null
        }

        // 2. Anti-Replay Nonce Check (S-04: Atomic reservation)
        cleanOldNonces(now)
        if (seenNonces.putIfAbsent(envelope.nonce, envelope.timestamp) != null) {
            AppLogger.d("SecurityRepository", "Envelope duplicate nonce (${envelope.nonce}) discarded (sender: ${envelope.senderId})")
            return null
        }

        // 3. Sender Verification (Check Whitelist or OTP)
        val senderDevice = getPairedDevice(envelope.senderId)
        val senderPublicKey = if (senderDevice != null) {
            CryptoUtils.publicKeyFromBase64(senderDevice.publicKeyBase64)
        } else {
            // Unpaired sender is only permitted if an active OTP is set (handshake flow)
            if (activeOtp == null) {
                AppLogger.w("SecurityRepository", "Envelope discarded: Sender not in whitelist (${envelope.senderId})")
                seenNonces.remove(envelope.nonce)
                return null
            }
            null
        }

        // 4. Signature Verification (if public key is known and not in OTP handshake flow)
        if (senderPublicKey != null && !isOtpKey) {
            val sigPayload = "${envelope.senderId}${envelope.recipientId}${envelope.timestamp}${envelope.nonce}${envelope.ciphertext}"
                .toByteArray(Charsets.UTF_8)
            val isValid = CryptoUtils.verify(sigPayload, envelope.signature, senderPublicKey)
            if (!isValid) {
                AppLogger.w("SecurityRepository", "Envelope discarded: Invalid signature from ${envelope.senderId}")
                seenNonces.remove(envelope.nonce)
                return null
            }
        }

        // 5. Decrypt
        val decryptedBytes = try {
            CryptoUtils.decryptAesGcm(
                envelope.ciphertext,
                envelope.iv,
                envelope.tag,
                secretOrDerivedKey
            )
        } catch (e: Exception) {
            AppLogger.e("SecurityRepository", "Decryption failed for envelope from ${envelope.senderId}", e)
            seenNonces.remove(envelope.nonce)
            return null
        }

        return String(decryptedBytes, Charsets.UTF_8)
    }

    override fun isViewerAudioPlaybackEnabled(): Boolean {
        return prefs.getBoolean("viewer_audio_playback_enabled", false)
    }

    override fun setViewerAudioPlaybackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("viewer_audio_playback_enabled", enabled).apply()
        AppLogger.i("SecurityRepository", "Viewer audio playback enabled: $enabled")
    }

    override fun getNightModeSetting(): com.a0soft.gphone.camonitor.data.model.NightModeSetting {
        val name = prefs.getString("night_mode_setting", com.a0soft.gphone.camonitor.data.model.NightModeSetting.AUTO.name)
            ?: com.a0soft.gphone.camonitor.data.model.NightModeSetting.AUTO.name
        return try {
            com.a0soft.gphone.camonitor.data.model.NightModeSetting.valueOf(name)
        } catch (_: Exception) {
            com.a0soft.gphone.camonitor.data.model.NightModeSetting.AUTO
        }
    }

    override fun setNightModeSetting(setting: com.a0soft.gphone.camonitor.data.model.NightModeSetting) {
        prefs.edit().putString("night_mode_setting", setting.name).apply()
        AppLogger.i("SecurityRepository", "Night mode setting persisted: $setting")
    }

    override fun getTypicalConnectDurationMs(cameraDeviceId: String): Long? {
        val raw = prefs.getString("connect_durations_$cameraDeviceId", null) ?: return null
        val samples = raw.split(",").mapNotNull { it.trim().toLongOrNull() }
        if (samples.isEmpty()) return null
        return samples.sum() / samples.size
    }

    override fun recordConnectDurationMs(cameraDeviceId: String, durationMs: Long) {
        val raw = prefs.getString("connect_durations_$cameraDeviceId", null)
        val existing = raw?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
        val updated = updateConnectDurationSamples(existing, durationMs)
        prefs.edit().putString("connect_durations_$cameraDeviceId", updated.joinToString(",")).apply()
        AppLogger.i("SecurityRepository", "Recorded connect duration for $cameraDeviceId: ${durationMs}ms (rolling avg=${updated.sum() / updated.size}ms)")
    }

    private fun cleanOldNonces(now: Long) {
        seenNonces.entries.removeIf { (now - it.value) > NONCE_RETENTION_MS }
    }
}
