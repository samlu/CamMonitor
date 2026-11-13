package com.a0soft.gphone.camonitor.data.update

/**
 * Computes and validates a 32-bit positive integer versionCode from a Semantic Versioning string.
 *
 * Encoding Formula (Decimal Positional Slots):
 *   versionCode = major * 100,000,000 + minor * 100,000 + patch * 100 + stage
 *
 * Slots & Constraints:
 *   - major: 0..21 (ensures total value <= Android's Int.MAX_VALUE = 2,147,483,647)
 *   - minor: 0..999 (3 decimal digits)
 *   - patch: 0..999 (3 decimal digits)
 *   - stage:
 *       - Final release (no pre-release tag): 99
 *       - Pre-release (-beta01, -rc02, -alpha3, etc.): 01..98
 *
 * Guarantees:
 *   - 1.0.8-beta01 (10000801) < 1.0.8-beta03 (10000803) < 1.0.8 (10000899)
 *   - Prevents duplicate or downgrade versionCodes across pre-releases and final releases.
 *   - Throws [IllegalArgumentException] on illegal formats, numbers out of bounds (e.g. 1.1234.4567),
 *     or integer overflow.
 */
object VersionCodeHelper {
    private val VERSION_REGEX = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(?:([a-zA-Z]+)[.-]?)?(\d{1,2}))?$""")

    const val MAX_MAJOR = 21L
    const val MAX_MINOR = 999L
    const val MAX_PATCH = 999L
    const val FINAL_STAGE = 99L

    fun compute(version: String): Int {
        val trimmed = version.trim()
        val match = VERSION_REGEX.matchEntire(trimmed)
            ?: throw IllegalArgumentException(
                "Malformed versionName: '$version'. Must follow 'X.Y.Z' or 'X.Y.Z-betaNN' format."
            )

        val major = match.groupValues[1].toLongOrNull() ?: -1L
        val minor = match.groupValues[2].toLongOrNull() ?: -1L
        val patch = match.groupValues[3].toLongOrNull() ?: -1L
        val preReleaseName = match.groupValues[4]
        val preReleaseNum = match.groupValues[5]

        if (major !in 0L..MAX_MAJOR) {
            throw IllegalArgumentException(
                "Major version ($major) in '$version' is out of range (0..$MAX_MAJOR)."
            )
        }
        if (minor !in 0L..MAX_MINOR) {
            throw IllegalArgumentException(
                "Minor version ($minor) in '$version' exceeds 3 digits (0..$MAX_MINOR)."
            )
        }
        if (patch !in 0L..MAX_PATCH) {
            throw IllegalArgumentException(
                "Patch version ($patch) in '$version' exceeds 3 digits (0..$MAX_PATCH)."
            )
        }

        val stage = if (preReleaseName.isEmpty() && preReleaseNum.isEmpty()) {
            FINAL_STAGE
        } else {
            val num = if (preReleaseNum.isEmpty()) 1L else preReleaseNum.toLong()
            if (num !in 1L..<FINAL_STAGE) {
                throw IllegalArgumentException(
                    "Pre-release number ($num) in '$version' must be between 1 and 98."
                )
            }
            num
        }

        val computed = major * 100_000_000L + minor * 100_000L + patch * 100L + stage
        if (computed !in 1L..Int.MAX_VALUE.toLong()) {
            throw IllegalArgumentException(
                "Computed versionCode ($computed) for '$version' exceeds Android 32-bit positive integer range (1..${Int.MAX_VALUE})."
            )
        }

        return computed.toInt()
    }
}
