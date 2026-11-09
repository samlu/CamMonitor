package com.a0soft.gphone.camonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.a0soft.gphone.camonitor.data.camera.TorchManager
import com.a0soft.gphone.camonitor.data.security.DefaultSecurityRepository
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.data.signaling.MqttSignalingClient
import com.a0soft.gphone.camonitor.data.signaling.SignalingClient
import com.a0soft.gphone.camonitor.data.webrtc.WebRtcManager
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.time.DefaultTimeProvider
import com.a0soft.gphone.camonitor.time.TimeProvider

class CamMonitorApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "cammonitor_service_channel"
        const val UPDATE_NOTIFICATION_CHANNEL_ID = "cammonitor_update_channel"
        lateinit var instance: CamMonitorApp
            private set
    }

    lateinit var timeProvider: TimeProvider
        private set
    lateinit var securityRepository: SecurityRepository
        private set
    lateinit var signalingClient: SignalingClient
        private set
    lateinit var webRtcManager: WebRtcManager
        private set
    lateinit var torchManager: TorchManager
        private set

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.a0soft.gphone.camonitor.ui.common.LocaleHelper.getLocalizedContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.a0soft.gphone.camonitor.ui.common.LocaleHelper.applyCurrentLocale(this)

        AppLogger.init(this)
        AppLogger.i("CamMonitorApp", "Application starting, initializing core dependencies")

        timeProvider = DefaultTimeProvider()
        securityRepository = DefaultSecurityRepository(this, timeProvider)
        signalingClient = MqttSignalingClient(securityRepository, this)
        webRtcManager = WebRtcManager(this)
        torchManager = TorchManager(this)

        com.a0soft.gphone.camonitor.ui.common.AppLifecycleObserver.init(this)
        createNotificationChannel()
        com.a0soft.gphone.camonitor.worker.UpdateWorkScheduler.schedulePeriodicCheck(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val updateChannel = NotificationChannel(
                UPDATE_NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_updates),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_updates_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            manager?.createNotificationChannel(updateChannel)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.a0soft.gphone.camonitor.ui.common.LocaleHelper.applyCurrentLocale(this)
    }
}
