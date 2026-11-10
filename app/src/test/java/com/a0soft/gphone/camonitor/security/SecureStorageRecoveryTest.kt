package com.a0soft.gphone.camonitor.security

import com.a0soft.gphone.camonitor.data.security.LegacyPrefsMigration
import com.a0soft.gphone.camonitor.data.security.SecureStorageRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureStorageRecoveryTest {

    @Test
    fun healthyStore_OpensFirstTry_NoWipe() {
        var wipes = 0
        val result = SecureStorageRecovery.open(create = { "store" }, wipe = { wipes++ })
        assertEquals("store", result.value)
        assertFalse(result.wiped)
        assertEquals(0, wipes)
    }

    @Test
    fun transientFailure_RetriedWithoutWipe() {
        var calls = 0
        var wipes = 0
        val result = SecureStorageRecovery.open(
            create = { if (++calls == 1) throw IllegalStateException("keystore busy") else "store" },
            wipe = { wipes++ }
        )
        assertEquals("store", result.value)
        assertFalse("Transient failure must not destroy identity", result.wiped)
        assertEquals(0, wipes)
    }

    @Test
    fun corruptedStore_WipedThenRecreated() {
        var corrupted = true
        var wipes = 0
        val result = SecureStorageRecovery.open(
            create = { if (corrupted) throw java.security.GeneralSecurityException("bad tag") else "fresh" },
            wipe = { wipes++; corrupted = false }
        )
        assertEquals("fresh", result.value)
        assertTrue(result.wiped)
        assertEquals(1, wipes)
    }

    @Test
    fun unrecoverableStore_FailsClosed_ReturnsNull() {
        val failures = mutableListOf<Int>()
        val result = SecureStorageRecovery.open(
            create = { throw IllegalStateException("broken keystore") },
            wipe = {},
            onAttemptFailed = { attempt, _ -> failures.add(attempt) }
        )
        assertNull("Must never hand back a plaintext fallback", result.value)
        assertEquals(listOf(1, 2, 4), failures)
    }

    @Test
    fun wipeFailure_StillAttemptsRecreate() {
        var calls = 0
        val result = SecureStorageRecovery.open(
            create = { if (++calls <= 2) throw IllegalStateException("x") else "store" },
            wipe = { throw IllegalStateException("cannot delete") }
        )
        assertEquals("store", result.value)
    }

    @Test
    fun legacyMigration_CopiesIdentityWhenSecureStoreEmpty() {
        val legacy = mapOf("device_id" to "abc", "device_priv_key" to "k", "key_ecdh_capable_v2" to true)
        val entries = LegacyPrefsMigration.entriesToMigrate(legacy, secureHasIdentity = false)
        assertEquals(3, entries.size)
        assertEquals("abc", entries["device_id"])
    }

    @Test
    fun legacyMigration_SecureIdentityWins() {
        val legacy = mapOf("device_id" to "old", "device_priv_key" to "k")
        assertTrue(LegacyPrefsMigration.entriesToMigrate(legacy, secureHasIdentity = true).isEmpty())
    }

    @Test
    fun legacyMigration_IgnoresLegacyWithoutIdentity() {
        val legacy = mapOf("night_mode_setting" to "AUTO")
        assertTrue(LegacyPrefsMigration.entriesToMigrate(legacy, secureHasIdentity = false).isEmpty())
    }

    @Test
    fun legacyMigration_DetectsLegacyIdentityKeys() {
        assertTrue(LegacyPrefsMigration.hasLegacyIdentity(mapOf("device_id" to "123")))
        assertTrue(LegacyPrefsMigration.hasLegacyIdentity(mapOf("device_priv_key" to "abc")))
        assertFalse(LegacyPrefsMigration.hasLegacyIdentity(mapOf("has_requested_android.permission.CAMERA" to true)))
        assertFalse(LegacyPrefsMigration.hasLegacyIdentity(emptyMap<String, Any>()))
    }
}
