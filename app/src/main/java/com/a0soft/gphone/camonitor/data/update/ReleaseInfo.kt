package com.a0soft.gphone.camonitor.data.update

/**
 * Metadata for a detected GitHub release.
 */
data class ReleaseInfo(
    val versionName: String,
    val tag: String,
    val title: String,
    val releaseUrl: String,
    val releaseNotes: String
)
