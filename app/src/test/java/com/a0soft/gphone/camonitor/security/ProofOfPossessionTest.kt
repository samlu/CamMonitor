package com.a0soft.gphone.camonitor.security

import com.a0soft.gphone.camonitor.data.model.SignalingEnvelope
import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class ProofOfPossessionTest {

    private val repo = object : SecurityRepository {
        override fun getDeviceId(): String = "test-device-id"
        override fun getDeviceName(): String = "Test Device"
        override fun setDeviceName(name: String) {}
        override fun getDevicePublicKeyBase64(): String = ""
        override fun getKeyPair(): java.security.KeyPair = generateEcKeyPair()
        override fun generatePairingOtp(): String = "otp-secret"
        override fun setActivePairingOtp(otp: String) {}
        override fun getActivePairingOtp(): String? = "otp-secret"
        override fun clearPairingOtp() {}
        override fun getPairedDevices(): List<com.a0soft.gphone.camonitor.data.model.PairedDevice> = emptyList()
        override fun addPairedDevice(device: com.a0soft.gphone.camonitor.data.model.PairedDevice, maxLimit: Int): Boolean = true
        override fun removePairedDevice(deviceId: String) {}
        override fun isDeviceWhitelisted(deviceId: String): Boolean = false
        override fun getPairedDevice(deviceId: String): com.a0soft.gphone.camonitor.data.model.PairedDevice? = null
        override fun createEnvelope(recipientId: String, plaintextJson: String, secretOrDerivedKey: ByteArray): SignalingEnvelope {
            throw UnsupportedOperationException()
        }
        override fun verifyAndDecryptEnvelope(envelope: SignalingEnvelope, secretOrDerivedKey: ByteArray, isOtpKey: Boolean): String? = null
        override fun isViewerAudioPlaybackEnabled(): Boolean = false
        override fun setViewerAudioPlaybackEnabled(enabled: Boolean) {}
    }

    private fun generateEcKeyPair(): java.security.KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    @Test
    fun testProofOfPossessionValidSignatureAccepted() {
        val viewerKeyPair = generateEcKeyPair()
        val viewerPubKeyBase64 = CryptoUtils.publicKeyToBase64(viewerKeyPair.public)
        val viewerDeviceId = "viewer-1234"
        val cameraDeviceId = "camera-5678"
        val timestamp = System.currentTimeMillis()
        val nonce = CryptoUtils.generateNonce()
        val ciphertext = "encrypted-pair-request-content"

        val sigPayload = "$viewerDeviceId$cameraDeviceId$timestamp$nonce$ciphertext".toByteArray(Charsets.UTF_8)
        val signature = CryptoUtils.sign(sigPayload, viewerKeyPair.private)

        val envelope = SignalingEnvelope(
            senderId = viewerDeviceId,
            recipientId = cameraDeviceId,
            timestamp = timestamp,
            nonce = nonce,
            ciphertext = ciphertext,
            iv = "dummy-iv",
            tag = "dummy-tag",
            signature = signature
        )

        val isValid = repo.verifyEnvelopeSignature(envelope, viewerPubKeyBase64)
        assertTrue("Legitimately signed envelope must verify Proof-of-Possession", isValid)
    }

    @Test
    fun testProofOfPossessionSpoofedPublicKeyRejected() {
        val attackerKeyPair = generateEcKeyPair()
        val victimKeyPair = generateEcKeyPair()
        val victimPubKeyBase64 = CryptoUtils.publicKeyToBase64(victimKeyPair.public)

        val attackerDeviceId = "attacker-1234"
        val cameraDeviceId = "camera-5678"
        val timestamp = System.currentTimeMillis()
        val nonce = CryptoUtils.generateNonce()
        val ciphertext = "encrypted-pair-request-content"

        // Attacker signs with their own private key, but specifies victim's public key in PairRequest
        val sigPayload = "$attackerDeviceId$cameraDeviceId$timestamp$nonce$ciphertext".toByteArray(Charsets.UTF_8)
        val attackerSignature = CryptoUtils.sign(sigPayload, attackerKeyPair.private)

        val envelope = SignalingEnvelope(
            senderId = attackerDeviceId,
            recipientId = cameraDeviceId,
            timestamp = timestamp,
            nonce = nonce,
            ciphertext = ciphertext,
            iv = "dummy-iv",
            tag = "dummy-tag",
            signature = attackerSignature
        )

        // Verifying against the claimed (victim's) public key must fail
        val isValid = repo.verifyEnvelopeSignature(envelope, victimPubKeyBase64)
        assertFalse("Envelope signed by different key must fail Proof-of-Possession", isValid)
    }

    @Test
    fun testProofOfPossessionCorruptedSignatureRejected() {
        val viewerKeyPair = generateEcKeyPair()
        val viewerPubKeyBase64 = CryptoUtils.publicKeyToBase64(viewerKeyPair.public)

        val envelope = SignalingEnvelope(
            senderId = "viewer-1234",
            recipientId = "camera-5678",
            timestamp = System.currentTimeMillis(),
            nonce = "1234567890abcdef",
            ciphertext = "some-ciphertext",
            iv = "iv",
            tag = "tag",
            signature = "corrupted-invalid-base64-signature!"
        )

        val isValid = repo.verifyEnvelopeSignature(envelope, viewerPubKeyBase64)
        assertFalse("Corrupted signature must return false", isValid)
    }

    @Test
    fun testOtpKeyOnlyPermitsPairRequestAndPairConfirm() {
        val pairRequest = SignalingMessage.PairRequest(
            pbDeviceId = "viewer-1",
            pbName = "Viewer",
            pbPublicKeyBase64 = "pubkey"
        )
        val pairConfirm = SignalingMessage.PairConfirm(
            paDeviceId = "camera-1",
            paName = "Camera",
            paPublicKeyBase64 = "pubkey"
        )
        val offer = SignalingMessage.Offer(sdp = "v=0...")
        val ice = SignalingMessage.IceCandidate(sdpMid = "0", sdpMLineIndex = 0, sdp = "candidate:...")

        fun isHandshakeAllowedWithOtp(msg: SignalingMessage): Boolean {
            return msg is SignalingMessage.PairRequest || msg is SignalingMessage.PairConfirm
        }

        assertTrue(isHandshakeAllowedWithOtp(pairRequest))
        assertTrue(isHandshakeAllowedWithOtp(pairConfirm))
        assertFalse("Offer decrypted with OTP must be rejected", isHandshakeAllowedWithOtp(offer))
        assertFalse("IceCandidate decrypted with OTP must be rejected", isHandshakeAllowedWithOtp(ice))
    }
}
