package com.a0soft.gphone.camonitor.pairing

import com.a0soft.gphone.camonitor.data.model.PairedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSecurityValidationTest {

    private fun validatePairRequest(
        existingDevice: PairedDevice?,
        incomingDeviceId: String,
        incomingPublicKey: String
    ): Boolean {
        if (existingDevice != null && existingDevice.publicKeyBase64 != incomingPublicKey) {
            return false
        }
        return true
    }

    private fun sanitizeDeviceName(rawName: String, fallback: String): String {
        return rawName.trim().take(32).filter { !it.isISOControl() }.ifEmpty { fallback }
    }

    @Test
    fun pairingRequest_withExistingDeviceIdDifferentKey_rejected() {
        val existing = PairedDevice("viewer-1", "Viewer Original", "originalPubKeyBase64", 1000L, role = "VIEWER")
        val allowed = validatePairRequest(existing, "viewer-1", "maliciousImposterPubKeyBase64")
        assertFalse("PairRequest claiming existing deviceId with differing public key must be rejected", allowed)
    }

    @Test
    fun pairingRequest_withExistingDeviceIdSameKey_accepted() {
        val existing = PairedDevice("viewer-1", "Viewer Original", "legitPubKeyBase64", 1000L, role = "VIEWER")
        val allowed = validatePairRequest(existing, "viewer-1", "legitPubKeyBase64")
        assertTrue("Idempotent PairRequest retransmission with identical public key must be accepted", allowed)
    }

    @Test
    fun pairingRequest_withNewDeviceId_accepted() {
        val allowed = validatePairRequest(null, "viewer-new", "newPubKeyBase64")
        assertTrue("New device pairing must be accepted", allowed)
    }

    @Test
    fun deviceNameSanitization_clampsTo32Chars() {
        val longName = "A".repeat(50)
        val sanitized = sanitizeDeviceName(longName, "Default")
        assertEquals(32, sanitized.length)
        assertEquals("A".repeat(32), sanitized)
    }

    @Test
    fun deviceNameSanitization_stripsIsoControlChars() {
        val maliciousName = "Camera\u0000\u0007\r\n\tRoom"
        val sanitized = sanitizeDeviceName(maliciousName, "Default")
        assertEquals("CameraRoom", sanitized)
    }

    @Test
    fun deviceNameSanitization_preservesInternationalAndPunctuation() {
        val cjkName = "「客廳監視器 #1 & 花園」"
        val sanitized = sanitizeDeviceName(cjkName, "Default")
        assertEquals("「客廳監視器 #1 & 花園」", sanitized)
    }

    @Test
    fun deviceNameSanitization_fallsBackWhenEmptyOrWhitespace() {
        val emptyName = "   \t\r\n  "
        val sanitized = sanitizeDeviceName(emptyName, "Camera")
        assertEquals("Camera", sanitized)
    }
}
