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
}

class DefaultSecurityRepository(
    context: Context,
    private val timeProvider: TimeProvider
) : SecurityRepository {

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "cammonitor_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            AppLogger.w("SecurityRepository", "Falling back to standard preferences: ${e.message}")
            context.getSharedPreferences("cammonitor_prefs", Context.MODE_PRIVATE)
        }
    }

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

    override fun getPairedDevices(): List<PairedDevice> {
        val jsonStr = prefs.getString("paired_devices", "[]") ?: "[]"
        val list = mutableListOf<PairedDevice>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(PairedDevice.fromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            AppLogger.e("SecurityRepository", "Failed to parse paired devices", e)
        }
        return list
    }

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

    override fun removePairedDevice(deviceId: String) {
        val current = getPairedDevices().toMutableList()
        current.removeAll { it.deviceId == deviceId }
        savePairedDevices(current)
        AppLogger.i("SecurityRepository", "Removed paired device: $deviceId")
    }

    override fun isDeviceWhitelisted(deviceId: String): Boolean {
        return getPairedDevices().any { it.deviceId == deviceId }
    }

    override fun getPairedDevice(deviceId: String): PairedDevice? {
        return getPairedDevices().firstOrNull { it.deviceId == deviceId }
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

        // 1. Anti-Replay Timestamp Check (30 seconds window)
        if (kotlin.math.abs(now - envelope.timestamp) > 30_000L) {
            AppLogger.w("SecurityRepository", "Envelope discarded: Timestamp expired (delta: ${now - envelope.timestamp}ms)")
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

    private fun cleanOldNonces(now: Long) {
        seenNonces.entries.removeIf { now - it.value > 60_000L }
    }
}
