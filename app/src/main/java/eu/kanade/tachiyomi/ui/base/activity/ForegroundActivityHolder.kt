package eu.kanade.tachiyomi.ui.base.activity

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Tracks the currently active foreground Activity to provide an active Window context
 * for auto-resolving challenges (e.g. interactive Cloudflare Turnstile dialogs).
 */
class ForegroundActivityHolder : Application.ActivityLifecycleCallbacks {

    private var activityRef: WeakReference<Activity>? = null

    val current: Activity?
        get() = activityRef?.get()

    override fun onActivityResumed(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    companion object {
        val instance = ForegroundActivityHolder()
    }
}
