package com.a0soft.gphone.camonitor.data.update

/**
 * Semantic version representation and comparison.
 * Independent of Android SDK so it can be tested with standard JVM unit tests.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: String? = null
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        if (this.major != other.major) return this.major.compareTo(other.major)
        if (this.minor != other.minor) return this.minor.compareTo(other.minor)
        if (this.patch != other.patch) return this.patch.compareTo(other.patch)
        
        // Stable release (null prerelease) is considered newer than a prerelease version
        if ((this.prerelease == null) && (other.prerelease != null)) return 1
        if ((this.prerelease != null) && (other.prerelease == null)) return -1
        
        return (this.prerelease ?: "").compareTo(other.prerelease ?: "")
    }

    companion object {
        private val SEMVER_REGEX = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?""", RegexOption.IGNORE_CASE)

        fun parse(raw: String): SemVer? {
            val clean = raw.trim()
            val match = SEMVER_REGEX.find(clean) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            val prerelease = match.groupValues.getOrNull(4)?.takeIf { it.isNotEmpty() }
            return SemVer(major, minor, patch, prerelease)
        }
    }
}
