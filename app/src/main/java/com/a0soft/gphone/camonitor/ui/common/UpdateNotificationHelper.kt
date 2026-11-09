package com.a0soft.gphone.camonitor.ui.common

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.MainActivity
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.data.update.ReleaseInfo
import com.a0soft.gphone.camonitor.log.AppLogger

object UpdateNotificationHelper {
    private const val TAG = "UpdateNotification"
    const val UPDATE_NOTIFICATION_ID = 1002
    const val ACTION_SHOW_UPDATE = "com.a0soft.gphone.camonitor.ACTION_SHOW_UPDATE"
    const val EXTRA_UPDATE_VERSION = "extra_update_version"

    fun showUpdateNotification(context: Context, release: ReleaseInfo) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_SHOW_UPDATE
                putExtra(EXTRA_UPDATE_VERSION, release.versionName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = context.getString(R.string.update_banner_title, release.versionName)
            val message = context.getString(R.string.update_notification_text)

            val notification = NotificationCompat.Builder(context, CamMonitorApp.UPDATE_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(UPDATE_NOTIFICATION_ID, notification)
            AppLogger.i(TAG, "Posted update notification for version ${release.versionName}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to post update notification: ${e.message}")
        }
    }

    fun dismissUpdateNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(UPDATE_NOTIFICATION_ID)
    }
}
