package com.a0soft.gphone.camonitor.data.security

/**
 * Outcome of [SecureStorageRecovery.open].
 *
 * @property value the opened store, or null when every attempt failed (caller must NOT fall back to
 *   plaintext on disk).
 * @property wiped true when the corrupted secure store had to be wiped before it could be reopened.
 */
class SecureStorageOpenResult<T>(
    val value: T?,
    val wiped: Boolean
)

/**
 * Fail-closed recovery for the encrypted preference store.
 *
 * Sequence: create -> (retry once, covers transient Keystore hiccups without losing data)
 * -> wipe + create (covers corrupted prefs file / invalidated master key) -> give up.
 * Never degrades to an unencrypted on-disk store.
 */
object SecureStorageRecovery {
    fun <T> open(
        create: () -> T,
        wipe: () -> Unit,
        onAttemptFailed: (attempt: Int, error: Exception) -> Unit = { _, _ -> }
    ): SecureStorageOpenResult<T> {
        for (attempt in 1..2) {
            try {
                return SecureStorageOpenResult(create(), wiped = false)
            } catch (e: Exception) {
                onAttemptFailed(attempt, e)
            }
        }
        try {
            wipe()
        } catch (e: Exception) {
            onAttemptFailed(3, e)
        }
        return try {
            SecureStorageOpenResult(create(), wiped = true)
        } catch (e: Exception) {
            onAttemptFailed(4, e)
            SecureStorageOpenResult(null, wiped = true)
        }
    }
}

/**
 * Migration of the legacy plaintext `cammonitor_prefs` file (written by earlier builds whenever
 * `EncryptedSharedPreferences` failed to initialise) into the encrypted store.
 */
object LegacyPrefsMigration {
    const val LEGACY_PREFS_NAME = "cammonitor_prefs"
    private const val IDENTITY_KEY = "device_id"

    /**
     * Whether the legacy file actually contains plaintext cryptographic identity keys that must be scrubbed.
     */
    fun hasLegacyIdentity(legacy: Map<String, *>): Boolean {
        return legacy.containsKey(IDENTITY_KEY) || legacy.containsKey("device_priv_key")
    }

    /**
     * Entries to copy into the secure store. If the secure store already has an identity it wins and
     * nothing is copied (the legacy file is still scrubbed by the caller).
     */
    fun entriesToMigrate(legacy: Map<String, *>, secureHasIdentity: Boolean): Map<String, Any> {
        if (secureHasIdentity || !legacy.containsKey(IDENTITY_KEY)) return emptyMap()
        return legacy.filterValues { it != null }.mapValues { it.value!! }
    }
}
