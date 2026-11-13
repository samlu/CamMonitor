package com.a0soft.gphone.camonitor.data.update

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class UpdatePreferenceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "cammonitor_update_prefs"
        private const val KEY_LAST_CHECK_TIME = "key_last_check_time"
        private const val KEY_SKIPPED_VERSION = "key_skipped_version"
        private const val KEY_CACHED_VERSION = "key_cached_version"
        private const val KEY_CACHED_TAG = "key_cached_tag"
        private const val KEY_CACHED_TITLE = "key_cached_title"
        private const val KEY_CACHED_URL = "key_cached_url"
        private const val KEY_CACHED_NOTES = "key_cached_notes"

        const val COOLDOWN_24_HOURS_MS = 24 * 60 * 60 * 1000L

        fun shouldCheck(
            lastCheckTimeMs: Long,
            currentTimeMs: Long,
            cooldownMs: Long = COOLDOWN_24_HOURS_MS
        ): Boolean = (lastCheckTimeMs == 0L) || ((currentTimeMs - lastCheckTimeMs) >= cooldownMs)

        fun isVersionSkipped(skippedVersion: String?, targetVersion: String): Boolean {
            if (skippedVersion == null) return false
            val skippedSemVer = SemVer.parse(skippedVersion)
            val targetSemVer = SemVer.parse(targetVersion)
            return if (skippedSemVer != null && targetSemVer != null) {
                targetSemVer <= skippedSemVer
            } else {
                skippedVersion.equals(targetVersion, ignoreCase = true)
            }
        }
    }

    fun shouldCheck(
        cooldownMs: Long = COOLDOWN_24_HOURS_MS,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
        return shouldCheck(lastCheck, currentTimeMs, cooldownMs)
    }

    fun recordCheck(currentTimeMs: Long = System.currentTimeMillis()) {
        prefs.edit { putLong(KEY_LAST_CHECK_TIME, currentTimeMs) }
    }

    fun skipVersion(versionName: String) {
        prefs.edit { putString(KEY_SKIPPED_VERSION, versionName) }
    }

    fun getSkippedVersion(): String? {
        return prefs.getString(KEY_SKIPPED_VERSION, null)
    }

    fun isVersionSkipped(versionName: String): Boolean {
        return isVersionSkipped(getSkippedVersion(), versionName)
    }

    fun saveCachedRelease(release: ReleaseInfo) {
        prefs.edit {
            putString(KEY_CACHED_VERSION, release.versionName)
            putString(KEY_CACHED_TAG, release.tag)
            putString(KEY_CACHED_TITLE, release.title)
            putString(KEY_CACHED_URL, release.releaseUrl)
            putString(KEY_CACHED_NOTES, release.releaseNotes)
        }
    }

    fun getCachedRelease(): ReleaseInfo? {
        val version = prefs.getString(KEY_CACHED_VERSION, null) ?: return null
        val tag = prefs.getString(KEY_CACHED_TAG, "v$version") ?: "v$version"
        val title = prefs.getString(KEY_CACHED_TITLE, "CamMonitor $tag") ?: "CamMonitor $tag"
        val url = prefs.getString(KEY_CACHED_URL, "https://github.com/samlu/CamMonitor/releases") ?: ""
        val notes = prefs.getString(KEY_CACHED_NOTES, "") ?: ""
        return ReleaseInfo(
            versionName = version,
            tag = tag,
            title = title,
            releaseUrl = url,
            releaseNotes = notes
        )
    }

    fun clearCachedRelease() {
        prefs.edit {
            remove(KEY_CACHED_VERSION)
            remove(KEY_CACHED_TAG)
            remove(KEY_CACHED_TITLE)
            remove(KEY_CACHED_URL)
            remove(KEY_CACHED_NOTES)
        }
    }
}
