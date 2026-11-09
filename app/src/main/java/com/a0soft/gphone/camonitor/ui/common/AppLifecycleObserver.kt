package com.a0soft.gphone.camonitor.ui.common

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Tracks application foreground / background state via Activity lifecycle callbacks.
 * Pure Android framework, zero overhead, no additional architecture component dependencies.
 */
object AppLifecycleObserver : Application.ActivityLifecycleCallbacks {

    private var startedActivityCount = 0

    val isAppInForeground: Boolean
        get() = startedActivityCount > 0

    fun init(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount++
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount = maxOf(0, startedActivityCount - 1)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
