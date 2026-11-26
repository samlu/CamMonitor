import com.android.build.api.variant.impl.VariantOutputImpl
import groovy.json.JsonSlurper
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateAppConfigTask : DefaultTask() {
    @get:InputFile
    abstract val configFile: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val localConfigFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @Suppress("UNCHECKED_CAST")
    private fun deepMerge(base: Map<String, Any?>, override: Map<String, Any?>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>(base)
        for ((k, v) in override) {
            val existing = result[k]
            result[k] = if (existing is Map<*, *> && v is Map<*, *>) {
                deepMerge(existing as Map<String, Any?>, v as Map<String, Any?>)
            } else {
                v
            }
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.obj(key: String, path: String = key): Map<String, Any?> =
        (this[key] as? Map<String, Any?>)
            ?: throw GradleException("[AppConfig Error] Missing object '$path' in cammonitor.config.json")

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.listOfObj(key: String, path: String = key): List<Map<String, Any?>> =
        (this[key] as? List<*>)?.map { it as? Map<String, Any?> ?: throw GradleException("[AppConfig Error] Invalid item in '$path'") }
            ?.takeIf { it.isNotEmpty() }
            ?: throw GradleException("[AppConfig Error] '$path' must be a non-empty array")

    private fun Map<String, Any?>.str(key: String, path: String = key): String =
        (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw GradleException("[AppConfig Error] '$path' must be a non-empty string")

    private fun Map<String, Any?>.posInt(key: String, path: String = key): Int =
        (this[key] as? Number)?.toInt()?.takeIf { it > 0 }
            ?: throw GradleException("[AppConfig Error] '$path' must be a positive integer")

    private fun Map<String, Any?>.posLong(key: String, path: String = key): Long =
        (this[key] as? Number)?.toLong()?.takeIf { it > 0L }
            ?: throw GradleException("[AppConfig Error] '$path' must be a positive integer")

    private fun Map<String, Any?>.numFloat(key: String, path: String = key): Float =
        (this[key] as? Number)?.toFloat()
            ?: throw GradleException("[AppConfig Error] '$path' must be a number")

    private fun ktStr(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "\"$escaped\""
    }

    @TaskAction
    @Suppress("UNCHECKED_CAST")
    fun generate() {
        val slurper = JsonSlurper()
        val base = slurper.parse(configFile.get().asFile) as Map<String, Any?>
        val merged = if (localConfigFile.isPresent && localConfigFile.get().asFile.exists()) {
            val local = slurper.parse(localConfigFile.get().asFile) as Map<String, Any?>
            deepMerge(base, local)
        } else {
            base
        }

        val project = merged.obj("project")
        val githubOwner = project.str("githubOwner", "project.githubOwner")
        val githubRepo = project.str("githubRepo", "project.githubRepo")
        val webViewerBaseUrl = project.str("webViewerBaseUrl", "project.webViewerBaseUrl")

        val signaling = merged.obj("signaling")
        val topicPrefix = signaling.str("topicPrefix", "signaling.topicPrefix")
        val keepAliveSec = signaling.posInt("keepAliveSec", "signaling.keepAliveSec")
        val maxReconnectBackoffMs = signaling.posLong("maxReconnectBackoffMs", "signaling.maxReconnectBackoffMs")
        val brokers = signaling.listOfObj("brokers", "signaling.brokers")
        val primaryBrokers = brokers.filter { it["primary"] == true }
        if (primaryBrokers.size != 1) {
            throw GradleException("[AppConfig Error] signaling.brokers must have exactly one broker with primary=true")
        }
        val primaryBroker = primaryBrokers.first()
        val secondaryBroker = brokers.firstOrNull { it["primary"] != true } ?: primaryBroker

        val webrtc = merged.obj("webrtc")
        val iceServers = webrtc.listOfObj("iceServers", "webrtc.iceServers")
        val video = webrtc.obj("video", "webrtc.video")
        val videoWidth = video.posInt("width", "webrtc.video.width")
        val videoHeight = video.posInt("height", "webrtc.video.height")
        val videoTargetFps = video.posInt("targetFps", "webrtc.video.targetFps")
        val videoMinBitrate = video.posInt("minBitrateBps", "webrtc.video.minBitrateBps")
        val videoMaxBitrate = video.posInt("maxBitrateBps", "webrtc.video.maxBitrateBps")
        if (videoMinBitrate > videoMaxBitrate) {
            throw GradleException("[AppConfig Error] webrtc.video.minBitrateBps must be <= maxBitrateBps")
        }

        val limits = merged.obj("limits")
        val maxPairedCameras = limits.posInt("maxPairedCameras", "limits.maxPairedCameras")
        val maxPairedViewers = limits.posInt("maxPairedViewers", "limits.maxPairedViewers")
        val maxConcurrentViewers = limits.posInt("maxConcurrentViewers", "limits.maxConcurrentViewers")
        if (maxConcurrentViewers > maxPairedViewers) {
            throw GradleException("[AppConfig Error] limits.maxConcurrentViewers must be <= maxPairedViewers")
        }

        val camera = merged.obj("camera")
        val noViewerPauseMs = camera.posLong("noViewerPauseMs", "camera.noViewerPauseMs")
        val oledAutoLockSec = camera.posInt("oledAutoLockSec", "camera.oledAutoLockSec")
        val maxRecoveryAttempts = camera.posInt("maxRecoveryAttempts", "camera.maxRecoveryAttempts")
        val nightMode = camera.obj("nightMode", "camera.nightMode")
        val nightLuxEnter = nightMode.numFloat("luxEnter", "camera.nightMode.luxEnter")
        val nightLuxExit = nightMode.numFloat("luxExit", "camera.nightMode.luxExit")
        if (nightLuxExit <= nightLuxEnter) {
            throw GradleException("[AppConfig Error] camera.nightMode.luxExit must be > luxEnter")
        }
        val nightDebounceMs = nightMode.posLong("debounceMs", "camera.nightMode.debounceMs")
        val battery = camera.obj("battery", "camera.battery")
        val lowBatteryPercent = battery.posInt("lowPercent", "camera.battery.lowPercent")
        val broadcastWarnPercent = battery.posInt("broadcastWarnPercent", "camera.battery.broadcastWarnPercent")
        if (lowBatteryPercent > broadcastWarnPercent || broadcastWarnPercent >= 100) {
            throw GradleException("[AppConfig Error] battery thresholds must satisfy 0 < lowPercent <= broadcastWarnPercent < 100")
        }

        val updates = merged.obj("updates")
        val checkIntervalHours = updates.posLong("checkIntervalHours", "updates.checkIntervalHours")
        val flexIntervalHours = updates.posLong("flexIntervalHours", "updates.flexIntervalHours")
        val httpTimeoutMs = updates.posInt("httpTimeoutMs", "updates.httpTimeoutMs")

        val logging = merged.obj("logging")
        val maxFileSizeMb = logging.posLong("maxFileSizeMb", "logging.maxFileSizeMb")
        val maxBackupFiles = logging.posInt("maxBackupFiles", "logging.maxBackupFiles")
        val androidMemoryEntries = logging.posInt("androidMemoryEntries", "logging.androidMemoryEntries")

        val brokersCode = brokers.joinToString(",\n        ") { b ->
            val host = b.str("host", "signaling.brokers[].host")
            val tlsPort = b.posInt("tlsPort", "signaling.brokers[].tlsPort")
            val wssPort = b.posInt("wssPort", "signaling.brokers[].wssPort")
            val wssPath = b.str("wssPath", "signaling.brokers[].wssPath")
            val isPrimary = b["primary"] == true
            "BrokerConfig(host = ${ktStr(host)}, tlsPort = $tlsPort, wssPort = $wssPort, wssPath = ${ktStr(wssPath)}, primary = $isPrimary)"
        }

        val iceServersCode = iceServers.joinToString(",\n        ") { s ->
            val urls = s.str("urls", "webrtc.iceServers[].urls")
            val username = (s["username"] as? String)?.takeIf { it.isNotEmpty() }
            val credential = (s["credential"] as? String)?.takeIf { it.isNotEmpty() }
            val userArg = if (username != null) ktStr(username) else "null"
            val credArg = if (credential != null) ktStr(credential) else "null"
            "IceServerConfig(urls = ${ktStr(urls)}, username = $userArg, credential = $credArg)"
        }

        val repoUrl = "https://github.com/$githubOwner/$githubRepo"
        val releasesUrl = "$repoUrl/releases"
        val latestReleaseApiUrl = "https://api.github.com/repos/$githubOwner/$githubRepo/releases/latest"
        val contributorsUrl = "$repoUrl/graphs/contributors"

        val generatedCode = """
            |// AUTO-GENERATED from cammonitor.config.json — DO NOT EDIT MANUALLY.
            |package com.a0soft.gphone.camonitor.config
            |
            |data class BrokerConfig(
            |    val host: String,
            |    val tlsPort: Int,
            |    val wssPort: Int,
            |    val wssPath: String,
            |    val primary: Boolean
            |)
            |
            |data class IceServerConfig(
            |    val urls: String,
            |    val username: String? = null,
            |    val credential: String? = null
            |)
            |
            |object AppConfig {
            |    // Project & Hosting
            |    const val GITHUB_OWNER: String = ${ktStr(githubOwner)}
            |    const val GITHUB_REPO: String = ${ktStr(githubRepo)}
            |    const val GITHUB_REPO_URL: String = ${ktStr(repoUrl)}
            |    const val GITHUB_RELEASES_URL: String = ${ktStr(releasesUrl)}
            |    const val GITHUB_LATEST_RELEASE_API_URL: String = ${ktStr(latestReleaseApiUrl)}
            |    const val GITHUB_CONTRIBUTORS_URL: String = ${ktStr(contributorsUrl)}
            |    const val WEB_VIEWER_BASE_URL: String = ${ktStr(webViewerBaseUrl)}
            |
            |    // Signaling (MQTT over TLS / WSS)
            |    const val SIGNALING_TOPIC_PREFIX: String = ${ktStr(topicPrefix)}
            |    const val SIGNALING_KEEP_ALIVE_SEC: Int = $keepAliveSec
            |    const val SIGNALING_MAX_RECONNECT_BACKOFF_MS: Long = ${maxReconnectBackoffMs}L
            |    const val PRIMARY_BROKER_HOST: String = ${ktStr(primaryBroker.str("host"))}
            |    const val PRIMARY_BROKER_PORT: Int = ${primaryBroker.posInt("tlsPort")}
            |    const val SECONDARY_BROKER_HOST: String = ${ktStr(secondaryBroker.str("host"))}
            |    const val SECONDARY_BROKER_PORT: Int = ${secondaryBroker.posInt("tlsPort")}
            |    val SIGNALING_BROKERS: List<BrokerConfig> = listOf(
            |        $brokersCode
            |    )
            |
            |    // WebRTC & Video Encoding
            |    val ICE_SERVERS: List<IceServerConfig> = listOf(
            |        $iceServersCode
            |    )
            |    const val VIDEO_WIDTH: Int = $videoWidth
            |    const val VIDEO_HEIGHT: Int = $videoHeight
            |    const val VIDEO_TARGET_FPS: Int = $videoTargetFps
            |    const val VIDEO_MIN_BITRATE_BPS: Int = $videoMinBitrate
            |    const val VIDEO_MAX_BITRATE_BPS: Int = $videoMaxBitrate
            |
            |    // Capacity Limits
            |    const val MAX_PAIRED_CAMERAS: Int = $maxPairedCameras
            |    const val MAX_PAIRED_VIEWERS: Int = $maxPairedViewers
            |    const val MAX_CONCURRENT_VIEWERS: Int = $maxConcurrentViewers
            |
            |    // Camera, Standby, Night Vision & Battery
            |    const val NO_VIEWER_PAUSE_MS: Long = ${noViewerPauseMs}L
            |    const val OLED_AUTO_LOCK_SEC: Int = $oledAutoLockSec
            |    const val MAX_CAMERA_RECOVERY_ATTEMPTS: Int = $maxRecoveryAttempts
            |    const val NIGHT_LUX_ENTER: Float = ${nightLuxEnter}f
            |    const val NIGHT_LUX_EXIT: Float = ${nightLuxExit}f
            |    const val NIGHT_DEBOUNCE_MS: Long = ${nightDebounceMs}L
            |    const val LOW_BATTERY_PERCENT: Int = $lowBatteryPercent
            |    const val LOW_BATTERY_BROADCAST_WARN_PERCENT: Int = $broadcastWarnPercent
            |
            |    // Release Update Policy
            |    const val UPDATE_CHECK_INTERVAL_HOURS: Long = ${checkIntervalHours}L
            |    const val UPDATE_FLEX_INTERVAL_HOURS: Long = ${flexIntervalHours}L
            |    const val UPDATE_COOLDOWN_MS: Long = ${checkIntervalHours * 60L * 60L * 1000L}L
            |    const val UPDATE_HTTP_TIMEOUT_MS: Int = $httpTimeoutMs
            |
            |    // Logging
            |    const val LOG_MAX_FILE_SIZE_BYTES: Long = ${maxFileSizeMb * 1024L * 1024L}L
            |    const val LOG_MAX_BACKUP_FILES: Int = $maxBackupFiles
            |    const val LOG_MAX_MEMORY_ENTRIES: Int = $androidMemoryEntries
            |}
            |
        """.trimMargin()

        val outFile = outputDir.get().asFile.resolve("com/a0soft/gphone/camonitor/config/AppConfig.kt")
        outFile.parentFile.mkdirs()
        outFile.writeText(generatedCode, Charsets.UTF_8)
    }
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersionName = "1.3.0"

fun computeAndValidateVersionCode(version: String): Int {
    val regex = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(?:([a-zA-Z]+)[.-]?)?(\d{1,2}))?$""")
    val match = regex.matchEntire(version.trim())
        ?: throw GradleException(
            "[VersionCode Error] Malformed versionName: '$version'. Must follow 'X.Y.Z' or 'X.Y.Z-betaNN' format."
        )

    val major = match.groupValues[1].toLong()
    val minor = match.groupValues[2].toLong()
    val patch = match.groupValues[3].toLong()
    val preReleaseName = match.groupValues[4]
    val preReleaseNum = match.groupValues[5]

    if (major > 21L) {
        throw GradleException("[VersionCode Error] Major version ($major) in '$version' exceeds 21 (Android 32-bit limit).")
    }
    if (minor > 999L) {
        throw GradleException("[VersionCode Error] Minor version ($minor) in '$version' exceeds 3 digits (999).")
    }
    if (patch > 999L) {
        throw GradleException("[VersionCode Error] Patch version ($patch) in '$version' exceeds 3 digits (999).")
    }

    val stage = if (preReleaseName.isEmpty() && preReleaseNum.isEmpty()) {
        99L
    } else {
        val num = if (preReleaseNum.isEmpty()) 1L else preReleaseNum.toLong()
        if (num !in 1L..<99L) {
            throw GradleException("[VersionCode Error] Pre-release number ($num) in '$version' must be between 1 and 98.")
        }
        num
    }

    val computed = major * 100_000_000L + minor * 100_000L + patch * 100L + stage
    if (computed <= 0L || computed > Int.MAX_VALUE) {
        throw GradleException("[VersionCode Error] Computed versionCode ($computed) exceeds positive 32-bit integer range.")
    }

    return computed.toInt()
}

android {
    namespace = "com.a0soft.gphone.camonitor"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.a0soft.gphone.camonitor"
        minSdk = 28
        targetSdk = 37
        // Use 100200099 to avoid downgrade rejection on devices updated during test cycle
        versionCode = maxOf(computeAndValidateVersionCode(appVersionName), 100200099)
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                val storeFilePath = keystoreProperties.getProperty("storeFile") ?: "keystore/release.keystore"
                storeFile = rootProject.file(storeFilePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants { variant ->
        val capName = variant.name.replaceFirstChar { it.uppercase(Locale.ROOT) }
        val genAppConfigTask = tasks.register<GenerateAppConfigTask>("generate${capName}AppConfig") {
            configFile.set(rootProject.layout.projectDirectory.file("cammonitor.config.json"))
            val localFile = rootProject.file("cammonitor.local.json")
            if (localFile.exists()) {
                localConfigFile.set(rootProject.layout.projectDirectory.file("cammonitor.local.json"))
            }
            outputDir.set(layout.buildDirectory.dir("generated/source/appConfig/${variant.name}"))
        }
        variant.sources.kotlin?.addGeneratedSourceDirectory(genAppConfigTask, GenerateAppConfigTask::outputDir)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val debugTag = if (variant.buildType == "debug") "-debug" else ""
        variant.outputs.forEach { output ->
            val vName = output.versionName.getOrElse("1.0.0")
            (output as? VariantOutputImpl)?.outputFileName?.set("Camonitor-v${vName}-${timestamp}${debugTag}.apk")
        }
    }
}

dependencies {
    testImplementation(libs.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)

    // QR Code
    implementation(libs.zxing.core)

    // Security
    implementation(libs.androidx.security.crypto)

    // Networking: MQTT & WebRTC
    implementation(libs.hivemq.mqtt.client)
    implementation(libs.stream.webrtc)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.cash.turbine)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
