package com.a0soft.gphone.camonitor.security

import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.model.SignalingEnvelope
import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.KeyPair

class MultiCameraTestSecurityRepository : SecurityRepository {
    private val pairedDevices = mutableListOf<PairedDevice>()
    private val keyPair = CryptoUtils.generateSoftwareEcKeyPair()

    override fun getDeviceId(): String = "test-device-id"
    override fun getDeviceName(): String = "Test-Device"
    override fun setDeviceName(name: String) {}
    override fun getDevicePublicKeyBase64(): String = CryptoUtils.publicKeyToBase64(keyPair.public)
    override fun getKeyPair(): KeyPair = keyPair

    override fun generatePairingOtp(): String = "123456"
    override fun setActivePairingOtp(otp: String) {}
    override fun getActivePairingOtp(): String? = null
    override fun clearPairingOtp() {}

    override fun getPairedDevices(): List<PairedDevice> = pairedDevices.toList()

    override fun addPairedDevice(device: PairedDevice, maxLimit: Int): Boolean {
        val roleCount = when (device.role) {
            "CAMERA" -> pairedDevices.count { it.role != "VIEWER" }
            "VIEWER" -> pairedDevices.count { it.role != "CAMERA" }
            else -> pairedDevices.size
        }
        if (roleCount >= maxLimit && pairedDevices.none { it.deviceId == device.deviceId }) {
            return false
        }
        pairedDevices.removeAll { it.deviceId == device.deviceId }
        pairedDevices.add(device)
        return true
    }

    override fun removePairedDevice(deviceId: String) {
        pairedDevices.removeAll { it.deviceId == deviceId }
    }

    override fun isDeviceWhitelisted(deviceId: String): Boolean = pairedDevices.any { it.deviceId == deviceId }

    override fun getPairedDevice(deviceId: String): PairedDevice? = pairedDevices.firstOrNull { it.deviceId == deviceId }

    override fun createEnvelope(recipientId: String, plaintextJson: String, secretOrDerivedKey: ByteArray): SignalingEnvelope {
        throw UnsupportedOperationException()
    }

    override fun verifyAndDecryptEnvelope(envelope: SignalingEnvelope, secretOrDerivedKey: ByteArray, isOtpKey: Boolean): String? {
        throw UnsupportedOperationException()
    }

    override fun isViewerAudioPlaybackEnabled(): Boolean = false
    override fun setViewerAudioPlaybackEnabled(enabled: Boolean) {}
}

class MultiCameraPairingTest {

    private lateinit var repository: SecurityRepository

    @Before
    fun setup() {
        repository = MultiCameraTestSecurityRepository()
    }

    @Test
    fun constants_verifyLimits() {
        assertEquals(8, SecurityRepository.MAX_PAIRED_CAMERAS)
        assertEquals(2, SecurityRepository.MAX_PAIRED_VIEWERS)
    }

    @Test
    fun viewer_canPairUpTo8Cameras() {
        for (i in 1..8) {
            val camera = PairedDevice(
                deviceId = "camera-id-$i",
                name = "Camera $i",
                publicKeyBase64 = "pubKey$i",
                pairedTimestamp = System.currentTimeMillis()
            )
            val added = repository.addPairedDevice(camera, maxLimit = SecurityRepository.MAX_PAIRED_CAMERAS)
            assertTrue("Camera $i should be paired successfully", added)
        }

        assertEquals(8, repository.getPairedDevices().size)

        // 9th camera should be rejected
        val camera9 = PairedDevice(
            deviceId = "camera-id-9",
            name = "Camera 9",
            publicKeyBase64 = "pubKey9",
            pairedTimestamp = System.currentTimeMillis()
        )
        val added9 = repository.addPairedDevice(camera9, maxLimit = SecurityRepository.MAX_PAIRED_CAMERAS)
        assertFalse("9th camera should be rejected when limit is 8", added9)
        assertEquals(8, repository.getPairedDevices().size)
    }

    @Test
    fun camera_canPairUpTo2Viewers() {
        for (i in 1..2) {
            val viewer = PairedDevice(
                deviceId = "viewer-id-$i",
                name = "Viewer $i",
                publicKeyBase64 = "pubKey$i",
                pairedTimestamp = System.currentTimeMillis()
            )
            val added = repository.addPairedDevice(viewer, maxLimit = SecurityRepository.MAX_PAIRED_VIEWERS)
            assertTrue("Viewer $i should be paired successfully", added)
        }

        assertEquals(2, repository.getPairedDevices().size)

        // 3rd viewer should be rejected
        val viewer3 = PairedDevice(
            deviceId = "viewer-id-3",
            name = "Viewer 3",
            publicKeyBase64 = "pubKey3",
            pairedTimestamp = System.currentTimeMillis()
        )
        val added3 = repository.addPairedDevice(viewer3, maxLimit = SecurityRepository.MAX_PAIRED_VIEWERS)
        assertFalse("3rd viewer should be rejected when limit is 2", added3)
        assertEquals(2, repository.getPairedDevices().size)
    }

    @Test
    fun updatingExistingDevice_succeedsEvenAtLimit() {
        for (i in 1..8) {
            val camera = PairedDevice(
                deviceId = "camera-id-$i",
                name = "Camera $i",
                publicKeyBase64 = "pubKey$i",
                pairedTimestamp = System.currentTimeMillis()
            )
            repository.addPairedDevice(camera, maxLimit = SecurityRepository.MAX_PAIRED_CAMERAS)
        }
        assertEquals(8, repository.getPairedDevices().size)

        // Update Camera 3 with new name
        val updatedCamera3 = PairedDevice(
            deviceId = "camera-id-3",
            name = "Kitchen Camera Renamed",
            publicKeyBase64 = "pubKey3New",
            pairedTimestamp = System.currentTimeMillis()
        )
        val updated = repository.addPairedDevice(updatedCamera3, maxLimit = SecurityRepository.MAX_PAIRED_CAMERAS)
        assertTrue("Updating an existing device should succeed even at limit", updated)
        assertEquals(8, repository.getPairedDevices().size)
        assertEquals("Kitchen Camera Renamed", repository.getPairedDevice("camera-id-3")?.name)
    }

    @Test
    fun cameraSelection_resolvesCorrectDevice() {
        val cam1 = PairedDevice("cam-1", "Living Room", "pub1", 1000L)
        val cam2 = PairedDevice("cam-2", "Backyard", "pub2", 2000L)
        val cam3 = PairedDevice("cam-3", "Garage", "pub3", 3000L)

        repository.addPairedDevice(cam1)
        repository.addPairedDevice(cam2)
        repository.addPairedDevice(cam3)

        // Specific target selected
        val selected = repository.getPairedDevice("cam-2")
        assertNotNull(selected)
        assertEquals("Backyard", selected?.name)

        // Unknown device falls back to null, caller falls back to firstOrNull()
        val unknown = repository.getPairedDevice("unknown")
        assertNull(unknown)
        val fallback = repository.getPairedDevices().firstOrNull()
        assertEquals("cam-1", fallback?.deviceId)
    }

    @Test
    fun roleSeparation_filtersCamerasAndViewersSeparately() {
        val cam = PairedDevice("cam-1", "Living Room", "pub1", 1000L, role = "CAMERA")
        val viewer = PairedDevice("viewer-1", "Dad Phone", "pub2", 2000L, role = "VIEWER")
        val legacy = PairedDevice("legacy-1", "Old Device", "pub3", 3000L, role = null)

        repository.addPairedDevice(cam)
        repository.addPairedDevice(viewer)
        repository.addPairedDevice(legacy)

        val cameras = repository.getPairedCameras()
        val viewers = repository.getPairedViewers()

        assertEquals(2, cameras.size) // cam + legacy
        assertTrue(cameras.any { it.deviceId == "cam-1" })
        assertTrue(cameras.any { it.deviceId == "legacy-1" })
        assertFalse(cameras.any { it.deviceId == "viewer-1" })

        assertEquals(2, viewers.size) // viewer + legacy
        assertTrue(viewers.any { it.deviceId == "viewer-1" })
        assertTrue(viewers.any { it.deviceId == "legacy-1" })
        assertFalse(viewers.any { it.deviceId == "cam-1" })
    }

    @Test
    fun testMultiRolePairingCoexistence() {
        // Pair 8 cameras
        for (i in 1..8) {
            val camera = PairedDevice(
                deviceId = "camera-id-$i",
                name = "Camera $i",
                publicKeyBase64 = "pubKeyCam$i",
                pairedTimestamp = System.currentTimeMillis(),
                role = "CAMERA"
            )
            val added = repository.addPairedDevice(camera, maxLimit = SecurityRepository.MAX_PAIRED_CAMERAS)
            assertTrue("Camera $i should pair successfully", added)
        }

        // Pair 2 viewers on same repository without being blocked by camera count
        for (i in 1..2) {
            val viewer = PairedDevice(
                deviceId = "viewer-id-$i",
                name = "Viewer $i",
                publicKeyBase64 = "pubKeyView$i",
                pairedTimestamp = System.currentTimeMillis(),
                role = "VIEWER"
            )
            val added = repository.addPairedDevice(viewer, maxLimit = SecurityRepository.MAX_PAIRED_VIEWERS)
            assertTrue("Viewer $i should pair successfully even though 8 cameras exist", added)
        }

        // Both roles coexist up to their individual limits
        assertEquals(10, repository.getPairedDevices().size)
        assertEquals(8, repository.getPairedCameras().size)
        assertEquals(2, repository.getPairedViewers().size)

        // 9th camera rejected
        val camera9 = PairedDevice(
            deviceId = "camera-id-9",
            name = "Camera 9",
            publicKeyBase64 = "pubKeyCam9",
            pairedTimestamp = System.currentTimeMillis(),
            role = "CAMERA"
        )
        assertFalse(repository.addPairedDevice(camera9, maxLimit = SecurityRepository.MAX_PAIRED_CAMERAS))

        // 3rd viewer rejected
        val viewer3 = PairedDevice(
            deviceId = "viewer-id-3",
            name = "Viewer 3",
            publicKeyBase64 = "pubKeyView3",
            pairedTimestamp = System.currentTimeMillis(),
            role = "VIEWER"
        )
        assertFalse(repository.addPairedDevice(viewer3, maxLimit = SecurityRepository.MAX_PAIRED_VIEWERS))

        // Updating existing camera or viewer succeeds
        val updatedCam = PairedDevice(
            deviceId = "camera-id-1",
            name = "Camera 1 Renamed",
            publicKeyBase64 = "pubKeyCam1",
            pairedTimestamp = System.currentTimeMillis(),
            role = "CAMERA"
        )
        assertTrue(repository.addPairedDevice(updatedCam, maxLimit = SecurityRepository.MAX_PAIRED_CAMERAS))
        assertEquals("Camera 1 Renamed", repository.getPairedDevice("camera-id-1")?.name)

        val updatedViewer = PairedDevice(
            deviceId = "viewer-id-2",
            name = "Viewer 2 Renamed",
            publicKeyBase64 = "pubKeyView2",
            pairedTimestamp = System.currentTimeMillis(),
            role = "VIEWER"
        )
        assertTrue(repository.addPairedDevice(updatedViewer, maxLimit = SecurityRepository.MAX_PAIRED_VIEWERS))
        assertEquals("Viewer 2 Renamed", repository.getPairedDevice("viewer-id-2")?.name)
    }
}
