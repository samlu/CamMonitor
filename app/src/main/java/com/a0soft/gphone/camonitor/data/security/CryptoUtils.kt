package com.a0soft.gphone.camonitor.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val EC_CURVE = "secp256r1"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    private val secureRandom = SecureRandom()

    /**
     * Serialises ECDH derivations. Declared before any method uses it so the
     * object is fully initialised before the first call.
     */
    private val ecdhLock = Any()

    private const val ECDH_MAX_ATTEMPTS = 2

    fun getOrCreateDeviceKeyPair(alias: String): KeyPair {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            throw UnsupportedOperationException("KeyStore PURPOSE_AGREE_KEY requires Android 12 (API 31)+")
        }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            try {
                val privateKey = keyStore.getKey(alias, null) as? PrivateKey
                val certificate = keyStore.getCertificate(alias)
                if (privateKey != null && certificate != null) {
                    val kp = KeyPair(certificate.publicKey, privateKey)
                    // Verify if this key can actually be used for ECDH
                    val agreement = KeyAgreement.getInstance("ECDH")
                    agreement.init(kp.private)
                    return kp
                }
            } catch (e: Exception) {
                try { keyStore.deleteEntry(alias) } catch (_: Exception) {}
            }
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or KeyProperties.PURPOSE_AGREE_KEY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .build()

        keyPairGenerator.initialize(spec)
        val kp = keyPairGenerator.generateKeyPair()

        // Verify key agreement capability
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(kp.private)
        return kp
    }

    fun generateSoftwareEcKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE), secureRandom)
        return kpg.generateKeyPair()
    }

    fun softwareKeyPairToBase64(keyPair: KeyPair): Pair<String, String> {
        val privBase64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val pubBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        return Pair(privBase64, pubBase64)
    }

    fun softwareKeyPairFromBase64(privBase64: String, pubBase64: String): KeyPair {
        val kf = KeyFactory.getInstance("EC")
        val privSpec = java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(privBase64))
        val pubSpec = java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(pubBase64))
        return KeyPair(kf.generatePublic(pubSpec), kf.generatePrivate(privSpec))
    }

    fun sign(data: ByteArray, privateKey: PrivateKey): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(data)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    fun verify(data: ByteArray, signatureBase64: String, publicKey: PublicKey): Boolean {
        return try {
            val sigBytes = Base64.getDecoder().decode(signatureBase64)
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(data)
            verifier.verify(sigBytes)
        } catch (e: Exception) {
            false
        }
    }

    fun publicKeyFromBase64(base64Str: String): PublicKey {
        val keyBytes = Base64.getDecoder().decode(base64Str)
        val spec = X509EncodedKeySpec(keyBytes)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(spec)
    }

    fun publicKeyToBase64(publicKey: PublicKey): String {
        return Base64.getEncoder().encodeToString(publicKey.encoded)
    }

    fun generateOtp(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun generateNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hkdfSha256(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val prk = if (salt.isNotEmpty()) {
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            mac.doFinal(secret)
        } else {
            secret
        }

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        var t = ByteArray(0)
        val okm = ByteBuffer.allocate(length)
        var i = 1
        while (okm.hasRemaining()) {
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, okm.remaining())
            okm.put(t, 0, toCopy)
            i++
        }
        return okm.array()
    }

    fun encryptAesGcm(plaintext: ByteArray, keyBytes: ByteArray): Triple<String, String, String> {
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val cipherWithTag = cipher.doFinal(plaintext)
        // In Java AES/GCM, cipherWithTag contains ciphertext + tag at the end (16 bytes)
        val tagSize = 16
        val cipherLen = cipherWithTag.size - tagSize
        val ciphertext = cipherWithTag.copyOfRange(0, cipherLen)
        val tag = cipherWithTag.copyOfRange(cipherLen, cipherWithTag.size)

        return Triple(
            Base64.getEncoder().encodeToString(ciphertext),
            Base64.getEncoder().encodeToString(iv),
            Base64.getEncoder().encodeToString(tag)
        )
    }

    fun decryptAesGcm(ciphertextBase64: String, ivBase64: String, tagBase64: String, keyBytes: ByteArray): ByteArray {
        val ciphertext = Base64.getDecoder().decode(ciphertextBase64)
        val iv = Base64.getDecoder().decode(ivBase64)
        val tag = Base64.getDecoder().decode(tagBase64)

        val combined = ByteArray(ciphertext.size + tag.size)
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
        System.arraycopy(tag, 0, combined, ciphertext.size, tag.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(combined)
    }

    /**
     * Serialises ECDH derivations.
     *
     * Keys live in AndroidKeyStore, which hands out an opaque operation handle
     * per key agreement. That handle is not re-entrant: two agreements on the
     * same private key at the same time make the second one fail with
     * `ProviderException: Keystore operation failed` /
     * `Invalid operation handle`. Every reconnect produced 9-11 of these, and
     * each failure dropped the envelope it was decrypting — which is how ICE
     * candidates went missing and the connection stalled in CHECKING.
     *
     * Derivation is a few hundred microseconds, so a single lock costs nothing.
     */
    fun deriveEcdhKey(privateKey: PrivateKey, publicKey: PublicKey): ByteArray =
        synchronized(ecdhLock) {
            var last: Throwable? = null
            for (attempt in 0 until ECDH_MAX_ATTEMPTS) {
                try {
                    return deriveEcdhKeyOnce(privateKey, publicKey)
                } catch (t: Throwable) {
                    last = t
                    if (attempt < ECDH_MAX_ATTEMPTS - 1) {
                        // A handle can still be held by the operation that just
                        // aborted; give the provider a moment to release it before
                        // retrying. This sleep only happens on the failure path.
                        try {
                            Thread.sleep(40L)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
            }
            throw last ?: IllegalStateException("ECDH derivation failed without an exception")
        }

    private fun deriveEcdhKeyOnce(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        val sharedSecret = agreement.generateSecret()
        return hkdfSha256(sharedSecret, ByteArray(0), "CamMonitor-E2EE".toByteArray(), 32)
    }
}
