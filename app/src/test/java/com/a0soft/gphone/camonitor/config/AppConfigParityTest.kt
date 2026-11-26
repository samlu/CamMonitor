package com.a0soft.gphone.camonitor.config

import com.a0soft.gphone.camonitor.data.camera.CameraXVideoCapturer
import com.a0soft.gphone.camonitor.data.model.PairingPayload
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.data.signaling.MqttSignalingClient
import com.a0soft.gphone.camonitor.data.update.ReleaseChecker
import com.a0soft.gphone.camonitor.data.update.UpdatePreferenceManager
import com.a0soft.gphone.camonitor.service.CameraMonitorService
import com.a0soft.gphone.camonitor.ui.home.LOW_BATTERY_BROADCAST_WARN_PERCENT
import com.a0soft.gphone.camonitor.ui.home.LOW_BATTERY_PERCENT
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppConfigParityTest {

    private fun deepMerge(base: JSONObject, override: JSONObject): JSONObject {
        val result = JSONObject(base.toString())
        val keys = override.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val ov = override.get(key)
            val ex = result.opt(key)
            if (ex is JSONObject && ov is JSONObject) {
                result.put(key, deepMerge(ex, ov))
            } else {
                result.put(key, ov)
            }
        }
        return result
    }

    private fun loadMergedRootConfig(): JSONObject {
        val baseFile = File("../cammonitor.config.json")
        assertTrue("Root cammonitor.config.json must exist at ${baseFile.absolutePath}", baseFile.exists())
        val base = JSONObject(baseFile.readText(Charsets.UTF_8))
        val localFile = File("../cammonitor.local.json")
        return if (localFile.exists()) {
            deepMerge(base, JSONObject(localFile.readText(Charsets.UTF_8)))
        } else {
            base
        }
    }

    @Test
    fun generatedAppConfigMatchesRootJsonFile() {
        val cfg = loadMergedRootConfig()

        // Project
        val project = cfg.getJSONObject("project")
        val owner = project.getString("githubOwner")
        val repo = project.getString("githubRepo")
        val webViewerUrl = project.getString("webViewerBaseUrl")
        assertEquals(owner, AppConfig.GITHUB_OWNER)
        assertEquals(repo, AppConfig.GITHUB_REPO)
        assertEquals("https://github.com/$owner/$repo", AppConfig.GITHUB_REPO_URL)
        assertEquals("https://github.com/$owner/$repo/releases", AppConfig.GITHUB_RELEASES_URL)
        assertEquals("https://api.github.com/repos/$owner/$repo/releases/latest", AppConfig.GITHUB_LATEST_RELEASE_API_URL)
        assertEquals("https://github.com/$owner/$repo/graphs/contributors", AppConfig.GITHUB_CONTRIBUTORS_URL)
        assertEquals(webViewerUrl, AppConfig.WEB_VIEWER_BASE_URL)
        assertEquals(webViewerUrl, PairingPayload.DEFAULT_WEB_VIEWER_BASE_URL)
        assertEquals(AppConfig.GITHUB_LATEST_RELEASE_API_URL, ReleaseChecker.DEFAULT_GITHUB_RELEASE_URL)

        // Signaling
        val signaling = cfg.getJSONObject("signaling")
        assertEquals(signaling.getString("topicPrefix"), AppConfig.SIGNALING_TOPIC_PREFIX)
        assertEquals(signaling.getString("topicPrefix"), MqttSignalingClient.TOPIC_PREFIX)
        assertEquals(signaling.getInt("keepAliveSec"), AppConfig.SIGNALING_KEEP_ALIVE_SEC)
        assertEquals(signaling.getLong("maxReconnectBackoffMs"), AppConfig.SIGNALING_MAX_RECONNECT_BACKOFF_MS)

        val brokers = signaling.getJSONArray("brokers")
        assertEquals(brokers.length(), AppConfig.SIGNALING_BROKERS.size)
        var primaryBroker: JSONObject? = null
        var secondaryBroker: JSONObject? = null
        for (i in 0 until brokers.length()) {
            val b = brokers.getJSONObject(i)
            val actual = AppConfig.SIGNALING_BROKERS[i]
            assertEquals(b.getString("host"), actual.host)
            assertEquals(b.getInt("tlsPort"), actual.tlsPort)
            assertEquals(b.getInt("wssPort"), actual.wssPort)
            assertEquals(b.getString("wssPath"), actual.wssPath)
            assertEquals(b.getBoolean("primary"), actual.primary)
            if (b.getBoolean("primary")) primaryBroker = b
            else if (secondaryBroker == null) secondaryBroker = b
        }
        val expectedPrimary = requireNotNull(primaryBroker)
        val expectedSecondary = secondaryBroker ?: expectedPrimary
        assertEquals(expectedPrimary.getString("host"), MqttSignalingClient.PRIMARY_BROKER_HOST)
        assertEquals(expectedPrimary.getInt("tlsPort"), MqttSignalingClient.PRIMARY_BROKER_PORT)
        assertEquals(expectedSecondary.getString("host"), MqttSignalingClient.SECONDARY_BROKER_HOST)
        assertEquals(expectedSecondary.getInt("tlsPort"), MqttSignalingClient.SECONDARY_BROKER_PORT)

        // WebRTC
        val webrtc = cfg.getJSONObject("webrtc")
        val iceServers = webrtc.getJSONArray("iceServers")
        assertEquals(iceServers.length(), AppConfig.ICE_SERVERS.size)
        for (i in 0 until iceServers.length()) {
            val s = iceServers.getJSONObject(i)
            val actual = AppConfig.ICE_SERVERS[i]
            assertEquals(s.getString("urls"), actual.urls)
            assertEquals(s.optString("username").takeIf { it.isNotEmpty() }, actual.username)
            assertEquals(s.optString("credential").takeIf { it.isNotEmpty() }, actual.credential)
        }
        val video = webrtc.getJSONObject("video")
        assertEquals(video.getInt("width"), AppConfig.VIDEO_WIDTH)
        assertEquals(video.getInt("height"), AppConfig.VIDEO_HEIGHT)
        assertEquals(video.getInt("targetFps"), AppConfig.VIDEO_TARGET_FPS)
        assertEquals(video.getInt("minBitrateBps"), AppConfig.VIDEO_MIN_BITRATE_BPS)
        assertEquals(video.getInt("maxBitrateBps"), AppConfig.VIDEO_MAX_BITRATE_BPS)

        // Limits
        val limits = cfg.getJSONObject("limits")
        assertEquals(limits.getInt("maxPairedCameras"), SecurityRepository.MAX_PAIRED_CAMERAS)
        assertEquals(limits.getInt("maxPairedViewers"), SecurityRepository.MAX_PAIRED_VIEWERS)
        assertEquals(limits.getInt("maxConcurrentViewers"), SecurityRepository.MAX_CONCURRENT_VIEWERS)

        // Camera
        val camera = cfg.getJSONObject("camera")
        assertEquals(camera.getLong("noViewerPauseMs"), CameraMonitorService.NO_VIEWER_PAUSE_MS)
        assertEquals(camera.getInt("oledAutoLockSec"), AppConfig.OLED_AUTO_LOCK_SEC)
        assertEquals(camera.getInt("maxRecoveryAttempts"), CameraMonitorService.MAX_CAMERA_RECOVERY_ATTEMPTS)
        val night = camera.getJSONObject("nightMode")
        assertEquals(night.getDouble("luxEnter").toFloat(), CameraXVideoCapturer.LUX_THRESHOLD_NIGHT_ENTER, 0.0001f)
        assertEquals(night.getDouble("luxExit").toFloat(), CameraXVideoCapturer.LUX_THRESHOLD_NIGHT_EXIT, 0.0001f)
        assertEquals(night.getLong("debounceMs"), CameraXVideoCapturer.LIGHT_DEBOUNCE_DELAY_MS)
        val battery = camera.getJSONObject("battery")
        assertEquals(battery.getInt("lowPercent"), LOW_BATTERY_PERCENT)
        assertEquals(battery.getInt("broadcastWarnPercent"), LOW_BATTERY_BROADCAST_WARN_PERCENT)

        // Updates
        val updates = cfg.getJSONObject("updates")
        assertEquals(updates.getLong("checkIntervalHours"), AppConfig.UPDATE_CHECK_INTERVAL_HOURS)
        assertEquals(updates.getLong("flexIntervalHours"), AppConfig.UPDATE_FLEX_INTERVAL_HOURS)
        assertEquals(updates.getLong("checkIntervalHours") * 3_600_000L, UpdatePreferenceManager.COOLDOWN_24_HOURS_MS)
        assertEquals(updates.getInt("httpTimeoutMs"), AppConfig.UPDATE_HTTP_TIMEOUT_MS)

        // Logging
        val logging = cfg.getJSONObject("logging")
        assertEquals(logging.getLong("maxFileSizeMb") * 1024L * 1024L, AppConfig.LOG_MAX_FILE_SIZE_BYTES)
        assertEquals(logging.getInt("maxBackupFiles"), AppConfig.LOG_MAX_BACKUP_FILES)
        assertEquals(logging.getInt("androidMemoryEntries"), AppConfig.LOG_MAX_MEMORY_ENTRIES)
    }
}
