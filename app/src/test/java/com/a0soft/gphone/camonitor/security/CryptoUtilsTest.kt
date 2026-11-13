package com.a0soft.gphone.camonitor.security

import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import org.junit.Assert.*
import org.junit.Test

class CryptoUtilsTest {

    @Test
    fun testEcKeyGenerationAndSignatureVerification() {
        val keyPair = CryptoUtils.generateSoftwareEcKeyPair()
        val data = "Hello, CamMonitor Security!".toByteArray(Charsets.UTF_8)

        val signature = CryptoUtils.sign(data, keyPair.private)
        assertNotNull(signature)
        assertTrue(signature.isNotEmpty())

        val isValid = CryptoUtils.verify(data, signature, keyPair.public)
        assertTrue("Signature should be valid with matching key pair", isValid)
    }

    @Test
    fun testSignatureVerification_TamperedData_Fails() {
        val keyPair = CryptoUtils.generateSoftwareEcKeyPair()
        val originalData = "Authorized payload".toByteArray(Charsets.UTF_8)
        val tamperedData = "Malicious modified payload".toByteArray(Charsets.UTF_8)

        val signature = CryptoUtils.sign(originalData, keyPair.private)
        val isValid = CryptoUtils.verify(tamperedData, signature, keyPair.public)

        assertFalse("Tampered data must fail signature verification", isValid)
    }

    @Test
    fun testAesGcmEncryptionAndDecryption() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val plaintext = "CamMonitor Secret SDP and ICE Candidates".toByteArray(Charsets.UTF_8)

        val (ciphertext, iv, tag) = CryptoUtils.encryptAesGcm(plaintext, key)
        assertNotNull(ciphertext)
        assertNotNull(iv)
        assertNotNull(tag)

        val decrypted = CryptoUtils.decryptAesGcm(ciphertext, iv, tag, key)
        assertEquals("Decrypted plaintext must match original", String(plaintext), String(decrypted))
    }

    @Test
    fun testEcdhKeyAgreement() {
        val deviceA = CryptoUtils.generateSoftwareEcKeyPair()
        val deviceB = CryptoUtils.generateSoftwareEcKeyPair()

        val derivedKeyA = CryptoUtils.deriveEcdhKey(deviceA.private, deviceB.public)
        val derivedKeyB = CryptoUtils.deriveEcdhKey(deviceB.private, deviceA.public)

        assertEquals("Both parties must compute identical ECDH shared secret", 32, derivedKeyA.size)
        assertArrayEquals("Derived keys must match", derivedKeyA, derivedKeyB)
    }

    @Test
    fun testHkdfSha256() {
        val secret = "test-secret".toByteArray(Charsets.UTF_8)
        val salt = "test-salt".toByteArray(Charsets.UTF_8)
        val info = "CamMonitor-Info".toByteArray(Charsets.UTF_8)

        val okm1 = CryptoUtils.hkdfSha256(secret, salt, info, 32)
        val okm2 = CryptoUtils.hkdfSha256(secret, salt, info, 32)

        assertEquals(32, okm1.size)
        assertArrayEquals("HKDF must be deterministic", okm1, okm2)
    }

    @Test
    fun testSoftwareKeyPairSerialization() {
        val original = CryptoUtils.generateSoftwareEcKeyPair()
        val (priv, pub) = CryptoUtils.softwareKeyPairToBase64(original)

        val restored = CryptoUtils.softwareKeyPairFromBase64(priv, pub)
        assertArrayEquals("Private key bytes must match", original.private.encoded, restored.private.encoded)
        assertArrayEquals("Public key bytes must match", original.public.encoded, restored.public.encoded)

        // Verify restored key pair works for sign and verify
        val data = "Verification test".toByteArray(Charsets.UTF_8)
        val sig = CryptoUtils.sign(data, restored.private)
        assertTrue(CryptoUtils.verify(data, sig, restored.public))
    }

    @Test
    fun testEcdhKeyCachingAndDefensiveClone() {
        val deviceA = CryptoUtils.generateSoftwareEcKeyPair()
        val deviceB = CryptoUtils.generateSoftwareEcKeyPair()

        val first = CryptoUtils.deriveEcdhKey(deviceA.private, deviceB.public)
        val originalCopy = first.clone()

        // Mutate the returned array to ensure the internal cache is not corrupted
        first[0] = (first[0].toInt() xor 0xFF).toByte()

        val second = CryptoUtils.deriveEcdhKey(deviceA.private, deviceB.public)
        assertArrayEquals("Cached ECDH key must return a defensive clone unaffected by caller mutation", originalCopy, second)
    }

    @Test
    fun testConcurrentEcdhAndSigningThreadSafety() {
        val deviceA = CryptoUtils.generateSoftwareEcKeyPair()
        val deviceB = CryptoUtils.generateSoftwareEcKeyPair()
        val expectedShared = CryptoUtils.deriveEcdhKey(deviceA.private, deviceB.public)
        val payload = "Concurrent ICE candidate envelope".toByteArray(Charsets.UTF_8)

        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val threads = (1..16).map { index ->
            Thread {
                try {
                    repeat(25) {
                        if (index % 2 == 0) {
                            val derived = CryptoUtils.deriveEcdhKey(deviceA.private, deviceB.public)
                            assertArrayEquals(expectedShared, derived)
                        } else {
                            val sig = CryptoUtils.sign(payload, deviceA.private)
                            assertTrue(CryptoUtils.verify(payload, sig, deviceA.public))
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000L) }
        assertTrue("Expected zero concurrency errors, but got: $errors", errors.isEmpty())
    }
}
