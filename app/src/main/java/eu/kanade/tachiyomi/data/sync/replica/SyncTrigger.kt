package eu.kanade.tachiyomi.data.sync.replica

import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Triggers sync on app foregrounding, repeats every 5 minutes while active,
 * and pushes changes immediately to Google Drive via WorkManager when the app goes to the background.
 */
class SyncTrigger(
    private val context: Context,
    private val repository: GoogleDriveSyncRepository = Injekt.get(),
    private val syncSettings: SyncSettings = Injekt.get(),
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var foregroundLoop: Job? = null

    fun init() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        foregroundLoop?.cancel()
        foregroundLoop = scope.launch {
            while (true) {
                if (syncSettings.isSignedIn && isNetworkAllowed()) {
                    try {
                        repository.sync()
                    } catch (_: Exception) {
                        // Silent in background loop
                    }
                }
                delay(FOREGROUND_INTERVAL_MS)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        foregroundLoop?.cancel()
        foregroundLoop = null

        // App minimized: push any changes made right before leaving the app
        if (syncSettings.isSignedIn) {
            SyncWorker.Scheduler.syncSoon(context)
        }
    }

    private fun isNetworkAllowed(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = cm.activeNetwork ?: return false
        return !syncSettings.isWifiOnly || !cm.isActiveNetworkMetered
    }

    companion object {
        private const val FOREGROUND_INTERVAL_MS = 5L * 60 * 1000 // 5 minutes
    }
}
