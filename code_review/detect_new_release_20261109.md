# Specification: Automatic Release Detection & Update Notification (WorkManager & System Notifications)

**Document:** `code_review/detect_new_release_20261109.md`  
**Date:** 2026-11-09  
**Status:** APPROVED BY USER  
**Target Repository:** [`samlu/CamMonitor`](https://github.com/samlu/CamMonitor)  

---

## 1. Overview & Objectives

CamMonitor is distributed as a standalone APK via GitHub Releases. Without Google Play Store background services, users need an autonomous mechanism to be notified of new releases even when the app is closed.

This feature enables CamMonitor to:
1. Schedule a periodic background task using Android **WorkManager** to autonomously query GitHub Releases once every 24 hours.
2. Compare the remote Semantic Versioning tag (`vX.Y.Z`) against the local `BuildConfig.VERSION_NAME`.
3. If a newer version is discovered while the app is **closed / in background**:
   - Post an Android **System Status Bar Notification** (`NotificationCompat.Builder`).
   - Tapping the notification opens the **CamMonitor app directly to the Home Screen with the update banner displayed**.
4. If a newer version is discovered while the app is **opened / in foreground**:
   - Render a non-blocking Material 3 banner/card on the Home Screen.
5. Support running background checks even while `CameraMonitorService` is active.
6. Provide a manual "Check for Updates" button in the About screen.

---

## 2. Decision Log & Architecture Choices

| Architectural Decision | Selected Choice | Rationale |
| :--- | :--- | :--- |
| **Scheduler Engine** | **Android Jetpack WorkManager** (`androidx.work:work-runtime-ktx`) | Survives app closure, system reboots, and respects Doze & battery constraints. |
| **Background Check Interval** | **24 Hours Periodic** (`PeriodicWorkRequestBuilder`) | Respects GitHub API rate limits (60/hr); minimal battery impact. |
| **Work Constraints** | `NetworkType.CONNECTED` + `setRequiresBatteryNotLow(true)` | Prevents failing when offline and avoids running on critical low battery. |
| **Background Notification** | **System Notification** via dedicated Notification Channel | Informs user even when app is not opened. |
| **Notification Tap Action** | **Open CamMonitor App** (`MainActivity` $\to$ `HomeScreen`) | Takes user into the app where the update banner is clearly presented. |
| **Camera Mode Policy** | **Allow check & notification during Camera Mode** | Keeps camera devices notified of important security or bugfix releases. |
| **In-App Presentation** | **Non-blocking Card / Banner on Home Screen** | Clean, non-modal, does not block "Start Camera Mode". |
| **Update Action** | **Open GitHub Release webpage in browser** | Zero new permissions (`REQUEST_INSTALL_PACKAGES` not needed); full changelog visible. |
| **Skip Version** | **Enabled ("Skip This Version")** | Allows user to suppress notifications for a specific version. |

---

## 3. WorkManager Background Architecture

### 3.1 WorkManager Dependency
In `gradle/libs.versions.toml`:
```toml
[versions]
workManager = "2.10.0"

[libraries]
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workManager" }
```
In `app/build.gradle.kts`:
```kotlin
implementation(libs.androidx.work.runtime.ktx)
```

### 3.2 Worker Implementation (`ReleaseCheckWorker.kt`)
`ReleaseCheckWorker` extends `CoroutineWorker(appContext, workerParams)`:

```kotlin
class ReleaseCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = UpdatePreferenceManager(context)

        val result = ReleaseChecker.checkLatestRelease()
        if (result is ReleaseCheckResult.UpdateAvailable) {
            val remoteSemVer = SemVer.parse(result.release.versionName)
            val skipped = prefs.getSkippedVersion()
            
            // Check if user already skipped this version
            if (skipped != null && remoteSemVer != null && remoteSemVer <= SemVer.parse(skipped)) {
                AppLogger.i("ReleaseCheckWorker", "Update available (${result.release.versionName}) but skipped by user")
                return@withContext Result.success()
            }

            prefs.saveCachedUpdate(result.release)

            // Post system notification if app is not in foreground or if user needs alert
            if (!AppLifecycleObserver.isAppInForeground) {
                UpdateNotificationHelper.showUpdateNotification(context, result.release)
            }
        }
        Result.success()
    }
}
```

### 3.3 Scheduling Policy (`UpdateWorkScheduler.kt`)
Enqueued once during `CamMonitorApp.onCreate()`:
```kotlin
object UpdateWorkScheduler {
    private const val WORK_NAME = "cammonitor_periodic_release_check"

    fun schedulePeriodicCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<ReleaseCheckWorker>(
            24, TimeUnit.HOURS,
            4, TimeUnit.HOURS // Flex interval
        )
        .setConstraints(constraints)
        .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // Preserves schedule across reboots & app starts
            workRequest
        )
    }
}
```

---

## 4. System Notification Specification

### 4.1 Notification Channel
Registered in `CamMonitorApp.createNotificationChannel()`:
* **Channel ID:** `cammonitor_update_channel`
* **Name (i18n):** `"App Updates"` / `"應用程式更新"` / `"应用程序更新"` / `"アプリアップデート"`
* **Importance:** `NotificationManager.IMPORTANCE_DEFAULT`
* **Show Badge:** `true`

### 4.2 Notification Content & Tap Action (`UpdateNotificationHelper.kt`)
* **Small Icon:** `R.drawable.ic_menu_camera` / adaptive app icon
* **Title:** `String.format(context.getString(R.string.update_banner_title), release.versionName)` (e.g. `"New Version Available: 1.0.9"`)
* **Content Text:** `context.getString(R.string.update_notification_text)` (`"Tap to review changelog and update."`)
* **Priority:** `NotificationCompat.PRIORITY_DEFAULT`
* **AutoCancel:** `true`
* **PendingIntent (Tap Action):**
  * Launches `MainActivity` with intent action `"com.a0soft.gphone.camonitor.ACTION_SHOW_UPDATE"` and extra `"update_version" -> release.versionName`.
  * Flags: `PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE`.
  * `MainActivity` routes to `HomeScreen` where the update banner is visibly displayed and scrolled into view.

---

## 5. Semantic Versioning Comparison Logic (`SemVer.kt`)

Stand-alone Kotlin comparator class:
```kotlin
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
        if (this.prerelease == null && other.prerelease != null) return 1
        if (this.prerelease != null && other.prerelease == null) return -1
        return (this.prerelease ?: "").compareTo(other.prerelease ?: "")
    }

    companion object {
        fun parse(raw: String): SemVer? {
            val clean = raw.trim().removePrefix("v").removePrefix("V")
            val regex = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?""")
            val match = regex.find(clean) ?: return null
            val (maj, min, pat) = match.destructured
            val pre = match.groupValues.getOrNull(4)?.takeIf { it.isNotEmpty() }
            return SemVer(maj.toInt(), min.toInt(), pat.toInt(), pre)
        }
    }
}
```

---

## 6. Power, Thermal & Notification Hygiene Constraints

1. **Battery & Network Constraints**:
   WorkManager is configured with `.setRequiresBatteryNotLow(true)` and `.setRequiredNetworkType(NetworkType.CONNECTED)` to guarantee the device never spins up radios under critical battery.
2. **Notification Hygiene**:
   Notification uses a fixed ID (`1002`) so repeated background triggers update the existing notification rather than spamming the status bar.
3. **App Foreground Tracking (`AppLifecycleObserver.kt`)**:
   Tracks `ProcessLifecycleOwner` (or activity count) to suppress duplicate status bar notifications if the user is already actively using the app in foreground.

---

## 7. Multilingual Resource Parity (i18n)

Keys to add across all 4 locales (`values/`, `values-b+zh-Hant/`, `values-b+zh-Hans/`, `values-ja/`):

```xml
<!-- English (values/strings.xml) -->
<string name="notification_channel_updates">App Updates</string>
<string name="notification_channel_updates_desc">Notifications for new CamMonitor app versions</string>
<string name="update_banner_title">New Version Available: %1$s</string>
<string name="update_banner_subtitle">Current version: %1$s</string>
<string name="update_notification_text">A new version of CamMonitor is available. Tap to view and update.</string>
<string name="update_action_view">View &amp; Update</string>
<string name="update_action_skip">Skip This Version</string>
<string name="update_action_remind_later">Remind Later</string>
<string name="update_check_manual">Check for Updates</string>
<string name="update_status_latest">CamMonitor is up to date (%1$s)</string>
<string name="update_status_failed">Failed to check for updates</string>

<!-- Traditional Chinese (values-b+zh-Hant/strings.xml) -->
<string name="notification_channel_updates">應用程式更新</string>
<string name="notification_channel_updates_desc">CamMonitor 新版本發佈通知</string>
<string name="update_banner_title">發現新版本：%1$s</string>
<string name="update_banner_subtitle">目前版本：%1$s</string>
<string name="update_notification_text">CamMonitor 已釋出新版本，輕觸即可查看更新。</string>
<string name="update_action_view">查看並更新</string>
<string name="update_action_skip">略過此版本</string>
<string name="update_action_remind_later">稍後提醒</string>
<string name="update_check_manual">檢查更新</string>
<string name="update_status_latest">CamMonitor 已是最新版本 (%1$s)</string>
<string name="update_status_failed">檢查更新失敗</string>

<!-- Simplified Chinese (values-b+zh-Hans/strings.xml) -->
<string name="notification_channel_updates">应用程序更新</string>
<string name="notification_channel_updates_desc">CamMonitor 新版本发布通知</string>
<string name="update_banner_title">发现新版本：%1$s</string>
<string name="update_banner_subtitle">当前版本：%1$s</string>
<string name="update_notification_text">CamMonitor 已发布新版本，点击即可查看更新。</string>
<string name="update_action_view">查看并更新</string>
<string name="update_action_skip">跳过此版本</string>
<string name="update_action_remind_later">稍后提醒</string>
<string name="update_check_manual">检查更新</string>
<string name="update_status_latest">CamMonitor 已是最新版本 (%1$s)</string>
<string name="update_status_failed">检查更新失败</string>

<!-- Japanese (values-ja/strings.xml) -->
<string name="notification_channel_updates">アプリアップデート</string>
<string name="notification_channel_updates_desc">CamMonitor の新バージョン通知</string>
<string name="update_banner_title">新しいバージョンがあります: %1$s</string>
<string name="update_banner_subtitle">現在のバージョン: %1$s</string>
<string name="update_notification_text">CamMonitor の新しいバージョンが利用可能です。タップして確認してください。</string>
<string name="update_action_view">確認して更新</string>
<string name="update_action_skip">このバージョンをスキップ</string>
<string name="update_action_remind_later">後で通知</string>
<string name="update_check_manual">アップデートを確認</string>
<string name="update_status_latest">CamMonitor は最新です (%1$s)</string>
<string name="update_status_failed">アップデートの確認に失敗しました</string>
```

---

## 8. Directory & File Structure

```text
app/src/
├── main/java/com/a0soft/gphone/camonitor/
│   ├── data/update/
│   │   ├── ReleaseInfo.kt            # Release metadata model
│   │   ├── SemVer.kt                 # SemVer parser & comparator
│   │   ├── ReleaseChecker.kt         # GitHub Releases API HTTP client
│   │   └── UpdatePreferenceManager.kt# Throttling & skipped version storage
│   ├── worker/
│   │   ├── ReleaseCheckWorker.kt     # CoroutineWorker for WorkManager
│   │   └── UpdateWorkScheduler.kt    # WorkManager enqueue logic
│   ├── ui/
│   │   ├── common/
│   │   │   ├── AppLifecycleObserver.kt   # Tracks foreground/background state
│   │   │   └── UpdateNotificationHelper.kt # System status bar notification builder
│   │   ├── home/
│   │   │   ├── HomeScreen.kt         # Integrates UpdateBannerCard
│   │   │   └── UpdateBannerCard.kt   # Non-blocking Compose M3 banner card
│   │   └── about/
│   │       └── AboutScreen.kt        # "Check for Updates" button
└── test/java/com/a0soft/gphone/camonitor/
    └── update/
        ├── SemVerTest.kt             # Pure JVM unit tests for version comparison
        ├── ReleaseCheckerTest.kt     # JSON parsing tests with mock release payload
        └── UpdateThrottlingTest.kt   # 24h cooldown and skip version tests
```
