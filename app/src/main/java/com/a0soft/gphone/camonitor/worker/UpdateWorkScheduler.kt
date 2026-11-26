package com.a0soft.gphone.camonitor.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.a0soft.gphone.camonitor.config.AppConfig
import com.a0soft.gphone.camonitor.log.AppLogger
import java.util.concurrent.TimeUnit

object UpdateWorkScheduler {
    private const val TAG = "UpdateWorkScheduler"
    const val PERIODIC_WORK_NAME = "cammonitor_periodic_release_check"

    fun schedulePeriodicCheck(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<ReleaseCheckWorker>(
                AppConfig.UPDATE_CHECK_INTERVAL_HOURS, TimeUnit.HOURS,
                AppConfig.UPDATE_FLEX_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            AppLogger.d(TAG, "Enqueued unique periodic release check worker (${AppConfig.UPDATE_CHECK_INTERVAL_HOURS}h)")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed scheduling periodic release check: ${e.message}")
        }
    }
}
