package com.a0soft.gphone.camonitor.data.update

import com.a0soft.gphone.camonitor.BuildConfig
import com.a0soft.gphone.camonitor.log.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ReleaseChecker {
    private const val TAG = "ReleaseChecker"
    const val DEFAULT_GITHUB_RELEASE_URL = "https://api.github.com/repos/samlu/CamMonitor/releases/latest"

    /**
     * Parses the GitHub release JSON object into a [ReleaseInfo] model.
     */
    fun parseReleaseJson(jsonStr: String): ReleaseInfo? {
        return try {
            val json = JSONObject(jsonStr)
            val tag = json.optString("tag_name", "").trim()
            if (tag.isEmpty()) return null
            val versionName = tag.removePrefix("v").removePrefix("V")
            val title = json.optString("name", "CamMonitor $tag")
            val releaseUrl = json.optString("html_url", "https://github.com/samlu/CamMonitor/releases")
            val releaseNotes = json.optString("body", "")
            ReleaseInfo(
                versionName = versionName,
                tag = tag,
                title = title,
                releaseUrl = releaseUrl,
                releaseNotes = releaseNotes
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error parsing GitHub release JSON: ${e.message}")
            null
        }
    }

    /**
     * Evaluates if a given [ReleaseInfo] is newer than [currentVersionName].
     */
    fun evaluateRelease(release: ReleaseInfo, currentVersionName: String): ReleaseCheckResult {
        val remoteSemVer = SemVer.parse(release.versionName)
        val currentSemVer = SemVer.parse(currentVersionName)

        return if (remoteSemVer != null && currentSemVer != null) {
            if (remoteSemVer > currentSemVer) {
                ReleaseCheckResult.UpdateAvailable(release)
            } else {
                ReleaseCheckResult.UpToDate
            }
        } else {
            // Fallback: simple string difference if SemVer parse fails
            if (release.versionName.isNotEmpty() && release.versionName != currentVersionName.trim().removePrefix("v").removePrefix("V")) {
                ReleaseCheckResult.UpdateAvailable(release)
            } else {
                ReleaseCheckResult.UpToDate
            }
        }
    }

    /**
     * Connects to the GitHub release API, fetches the latest release, and compares against [currentVersionName].
     */
    suspend fun checkLatestRelease(
        currentVersionName: String = BuildConfig.VERSION_NAME,
        endpointUrl: String = DEFAULT_GITHUB_RELEASE_URL
    ): ReleaseCheckResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            AppLogger.d(TAG, "Checking for latest release at: $endpointUrl (current: $currentVersionName)")
            val url = URL(endpointUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "CamMonitor-Android/$currentVersionName")
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.use { it.readText() }
                val release = parseReleaseJson(response)
                    ?: return@withContext ReleaseCheckResult.Error("Failed to parse release payload")
                
                val result = evaluateRelease(release, currentVersionName)
                AppLogger.i(TAG, "Update check result: ${result.javaClass.simpleName} (latest=${release.versionName})")
                result
            } else {
                val errorMsg = "HTTP $responseCode from update server"
                AppLogger.w(TAG, errorMsg)
                ReleaseCheckResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to check update: ${e.message}")
            ReleaseCheckResult.Error(e.message ?: "Network error")
        } finally {
            connection?.disconnect()
        }
    }
}
