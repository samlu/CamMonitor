import com.android.build.api.variant.impl.VariantOutputImpl
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersionName = "1.1.0"

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
        if (num < 1L || num >= 99L) {
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
        versionCode = computeAndValidateVersionCode(appVersionName)
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
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val debugTag = if (variant.buildType == "debug") "-debug" else ""
        variant.outputs.forEach { output ->
            val vName = output.versionName.getOrElse("1.0.0")
            (output as? VariantOutputImpl)?.outputFileName?.set("Camonitor-v${vName}-${timestamp}${debugTag}.apk")
        }
    }
}

dependencies {
    testImplementation("org.json:json:20240303")
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
