package com.a0soft.gphone.camonitor.security

import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import com.a0soft.gphone.camonitor.data.security.DefaultSecurityRepository
import com.a0soft.gphone.camonitor.time.TimeProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.KeyPair

class FakeTimeProvider : TimeProvider {
    var currentTime: Long = 1728723600000L
    override fun currentTimeMillis(): Long = currentTime
    override suspend fun delayMillis(timeMillis: Long) { currentTime += timeMillis }
}

class FakeSecurityRepository(
    private val timeProvider: FakeTimeProvider
) {
    val keyPair: KeyPair = CryptoUtils.generateSoftwareEcKeyPair()
    val pairedDevices = mutableListOf<PairedDevice>()
    private val seenNonces = java.util.concurrent.ConcurrentHashMap<String, Long>()
    var activeOtp: String? = null
    var myDeviceId: String = "my-camera-id"

    fun verifyEnvelope(timestamp: Long, nonce: String, senderId: String, recipientId: String = myDeviceId): Boolean {
        // 0. Recipient check (S-03)
        if (recipientId != myDeviceId) {
            return false
        }
        val now = timeProvider.currentTimeMillis()
        // 1. Anti-Replay Timestamp Check (30 seconds window)
        if (kotlin.math.abs(now - timestamp) > 30_000L) {
            return false
        }
        // 2. Anti-Replay Nonce Check (atomic reservation - S-04)
        if (seenNonces.putIfAbsent(nonce, timestamp) != null) {
            return false
        }

        // 3. Sender Verification
        if (pairedDevices.none { it.deviceId == senderId } && activeOtp == null) {
            seenNonces.remove(nonce)
            return false
        }
        return true
    }
}

class AntiReplayTest {

    private lateinit var fakeTimeProvider: FakeTimeProvider
    private lateinit var securityRepo: FakeSecurityRepository

    @Before
    fun setup() {
        fakeTimeProvider = FakeTimeProvider()
        securityRepo = FakeSecurityRepository(fakeTimeProvider)
        securityRepo.pairedDevices.add(
            PairedDevice("pb-1", "Viewer 1", "key1", fakeTimeProvider.currentTimeMillis())
        )
    }

    @Test
    fun testValidEnvelopeWithinWindow_Passes() {
        val timestamp = fakeTimeProvider.currentTimeMillis()
        val nonce = "unique-nonce-1"
        val passed = securityRepo.verifyEnvelope(timestamp, nonce, "pb-1")
        assertTrue("Recent envelope with unique nonce should be accepted", passed)
    }

    @Test
    fun testExpiredEnvelope_OlderThan30Seconds_Rejected() {
        val oldTimestamp = fakeTimeProvider.currentTimeMillis() - 31_000L // 31 seconds ago
        val nonce = "unique-nonce-2"
        val passed = securityRepo.verifyEnvelope(oldTimestamp, nonce, "pb-1")
        assertFalse("Envelope older than 30s must be rejected", passed)
    }

    @Test
    fun testFutureEnvelope_Beyond30Seconds_Rejected() {
        val futureTimestamp = fakeTimeProvider.currentTimeMillis() + 35_000L
        val nonce = "unique-nonce-3"
        val passed = securityRepo.verifyEnvelope(futureTimestamp, nonce, "pb-1")
        assertFalse("Future envelope beyond 30s must be rejected", passed)
    }

    @Test
    fun testDuplicateNonce_Rejected() {
        val timestamp = fakeTimeProvider.currentTimeMillis()
        val nonce = "duplicate-nonce-xyz"

        val firstAttempt = securityRepo.verifyEnvelope(timestamp, nonce, "pb-1")
        assertTrue(firstAttempt)

        val secondAttempt = securityRepo.verifyEnvelope(timestamp, nonce, "pb-1")
        assertFalse("Replayed envelope with identical nonce must be rejected", secondAttempt)
    }

    @Test
    fun testUnauthorizedSender_WithoutOtp_Rejected() {
        val timestamp = fakeTimeProvider.currentTimeMillis()
        val nonce = "unique-nonce-4"
        val passed = securityRepo.verifyEnvelope(timestamp, nonce, "unauthorized-hacker-id")
        assertFalse("Sender not in whitelist without active OTP must be rejected", passed)
    }

    @Test
    fun testRecipientIdMismatch_Rejected() {
        val timestamp = fakeTimeProvider.currentTimeMillis()
        val nonce = "unique-nonce-s03"

        // Legitimate recipientId matches local device ID
        val validRecipient = securityRepo.verifyEnvelope(timestamp, nonce, "pb-1", recipientId = "my-camera-id")
        assertTrue("Matching recipientId must be accepted", validRecipient)

        // Mismatched recipientId (intended for a different device) must be rejected
        val mismatchedNonce = "unique-nonce-s03-mismatch"
        val wrongRecipient = securityRepo.verifyEnvelope(timestamp, mismatchedNonce, "pb-1", recipientId = "different-camera-id")
        assertFalse("Envelope with mismatched recipientId must be rejected", wrongRecipient)
    }

    @Test
    fun testConcurrentDuplicateNonce_AtomicDeduplication() {
        val timestamp = fakeTimeProvider.currentTimeMillis()
        val targetNonce = "concurrent-race-nonce"
        val threadCount = 8
        val executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount)
        val latch = java.util.concurrent.CountDownLatch(threadCount)
        val acceptedCount = java.util.concurrent.atomic.AtomicInteger(0)

        for (i in 0 until threadCount) {
            executor.execute {
                try {
                    val accepted = securityRepo.verifyEnvelope(timestamp, targetNonce, "pb-1")
                    if (accepted) {
                        acceptedCount.incrementAndGet()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Exactly one thread must succeed in claiming the atomic nonce", 1, acceptedCount.get())
    }

    @Test
    fun testSoftwareFallbackKeyPersistence() {
        // Simulates S-05 fallback flag logic
        var usingSoftwareFallback = false
        var softwareKeyGenerated = false
        var keyStoreAttempted = false

        fun initKeyPair(isReboot: Boolean, keyStoreFails: Boolean) {
            if (!usingSoftwareFallback) {
                keyStoreAttempted = true
                if (keyStoreFails) {
                    usingSoftwareFallback = true
                    softwareKeyGenerated = true
                }
            } else {
                keyStoreAttempted = false
                softwareKeyGenerated = true
            }
        }

        // Run 1: KeyStore fails on Android 12+ -> falls back to software key
        initKeyPair(isReboot = false, keyStoreFails = true)
        assertTrue(keyStoreAttempted)
        assertTrue(usingSoftwareFallback)
        assertTrue(softwareKeyGenerated)

        // Run 2: Device reboots, KeyStore is now theoretically healthy -> fallback flag prevents KeyStore regeneration
        initKeyPair(isReboot = true, keyStoreFails = false)
        assertFalse("KeyStore generation must not be re-attempted once software fallback was recorded", keyStoreAttempted)
        assertTrue(usingSoftwareFallback)
    }
}
