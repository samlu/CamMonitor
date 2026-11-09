package com.a0soft.gphone.camonitor.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.a0soft.gphone.camonitor.data.update.ReleaseCheckResult
import com.a0soft.gphone.camonitor.data.update.ReleaseChecker
import com.a0soft.gphone.camonitor.data.update.UpdatePreferenceManager
import com.a0soft.gphone.camonitor.ui.common.AppLifecycleObserver
import com.a0soft.gphone.camonitor.ui.common.UpdateNotificationHelper
import com.a0soft.gphone.camonitor.log.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReleaseCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val tag = "ReleaseCheckWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AppLogger.i(tag, "Background release check worker running")
        val prefs = UpdatePreferenceManager(context)

        val result = ReleaseChecker.checkLatestRelease()
        prefs.recordCheck()

        if (result is ReleaseCheckResult.UpdateAvailable) {
            val release = result.release
            if (prefs.isVersionSkipped(release.versionName)) {
                AppLogger.i(tag, "Newer version ${release.versionName} found but user previously marked as skipped")
                return@withContext Result.success()
            }

            prefs.saveCachedRelease(release)

            // Post system status bar notification if app is in background
            if (!AppLifecycleObserver.isAppInForeground) {
                UpdateNotificationHelper.showUpdateNotification(context, release)
            } else {
                AppLogger.i(tag, "App is currently in foreground; update cached for Home Screen banner")
            }
        }
        Result.success()
    }
}
