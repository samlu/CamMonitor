package com.a0soft.gphone.camonitor.data.update

/**
 * Result of checking for a new release.
 */
sealed class ReleaseCheckResult {
    data class UpdateAvailable(val release: ReleaseInfo) : ReleaseCheckResult()
    data object UpToDate : ReleaseCheckResult()
    data class Error(val message: String) : ReleaseCheckResult()
}
