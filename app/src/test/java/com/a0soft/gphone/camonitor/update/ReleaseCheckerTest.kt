package com.a0soft.gphone.camonitor.update

import com.a0soft.gphone.camonitor.data.update.ReleaseCheckResult
import com.a0soft.gphone.camonitor.data.update.ReleaseChecker
import com.a0soft.gphone.camonitor.data.update.ReleaseInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCheckerTest {

    private val sampleJson = """
        {
          "tag_name": "v1.0.9",
          "name": "CamMonitor v1.0.9",
          "html_url": "https://github.com/samlu/CamMonitor/releases/tag/v1.0.9",
          "body": "### Changes\n- Fixed battery drain in standby\n- Improved night mode"
        }
    """.trimIndent()

    @Test
    fun testParseValidReleaseJson() {
        val release = ReleaseChecker.parseReleaseJson(sampleJson)
        assertNotNull(release)
        assertEquals("1.0.9", release!!.versionName)
        assertEquals("v1.0.9", release.tag)
        assertEquals("CamMonitor v1.0.9", release.title)
        assertEquals("https://github.com/samlu/CamMonitor/releases/tag/v1.0.9", release.releaseUrl)
        assertTrue(release.releaseNotes.contains("Fixed battery drain in standby"))
    }

    @Test
    fun testParseInvalidJsonReturnsNull() {
        assertNull(ReleaseChecker.parseReleaseJson("{ invalid json }"))
        assertNull(ReleaseChecker.parseReleaseJson("""{ "name": "Missing tag" }"""))
        assertNull(ReleaseChecker.parseReleaseJson("""{ "tag_name": "" }"""))
    }

    @Test
    fun testEvaluateReleaseWhenNewerVersionAvailable() {
        val release = ReleaseInfo(
            versionName = "1.0.9",
            tag = "v1.0.9",
            title = "v1.0.9",
            releaseUrl = "https://github.com/samlu/CamMonitor/releases/tag/v1.0.9",
            releaseNotes = "Release notes"
        )
        val result = ReleaseChecker.evaluateRelease(release, currentVersionName = "1.0.8")
        assertTrue(result is ReleaseCheckResult.UpdateAvailable)
        assertEquals("1.0.9", (result as ReleaseCheckResult.UpdateAvailable).release.versionName)
    }

    @Test
    fun testEvaluateReleaseWhenUpToDate() {
        val release = ReleaseInfo(
            versionName = "1.0.8",
            tag = "v1.0.8",
            title = "v1.0.8",
            releaseUrl = "https://github.com/samlu/CamMonitor/releases/tag/v1.0.8",
            releaseNotes = ""
        )
        val result = ReleaseChecker.evaluateRelease(release, currentVersionName = "1.0.8")
        assertEquals(ReleaseCheckResult.UpToDate, result)
    }

    @Test
    fun testEvaluateReleaseWhenLocalIsNewer() {
        val release = ReleaseInfo(
            versionName = "1.0.7",
            tag = "v1.0.7",
            title = "v1.0.7",
            releaseUrl = "https://github.com/samlu/CamMonitor/releases/tag/v1.0.7",
            releaseNotes = ""
        )
        val result = ReleaseChecker.evaluateRelease(release, currentVersionName = "1.0.8")
        assertEquals(ReleaseCheckResult.UpToDate, result)
    }
}
