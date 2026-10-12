package eu.kanade.tachiyomi.extension

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.domain.base.BasePreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * Background job that silently auto-updates sandbox extensions once per day.
 * Only runs when sandbox mode is enabled. Zero user interaction — no popups, no dialogs.
 */
class SandboxExtensionUpdateJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val basePreferences = Injekt.get<BasePreferences>()

        // Only run when sandbox mode is ON
        if (!basePreferences.sandboxExtensionsEnabled.get()) {
            return Result.success()
        }

        return try {
            val extensionManager = Injekt.get<ExtensionManager>()

            // Fetch the latest available extension list so hasUpdate is accurate
            extensionManager.findAvailableExtensions()

            // Only update extensions that are private (sandbox) and have a known update
            val sandboxExtsWithUpdates = extensionManager.installedExtensionsFlow.value
                .filter { !it.isShared && it.hasUpdate }

            if (sandboxExtsWithUpdates.isEmpty()) {
                logcat { "Sandbox auto-update: nothing to update" }
                return Result.success()
            }

            logcat { "Sandbox auto-update: ${sandboxExtsWithUpdates.size} extension(s) to update" }

            for (ext in sandboxExtsWithUpdates) {
                try {
                    // updateExtension() already routes private extensions through
                    // installApkPrivately() → installPrivateExtensionFile() — zero OS dialog
                    extensionManager.updateExtension(ext).collect { /* silent */ }
                    logcat { "Sandbox auto-updated: ${ext.name}" }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to auto-update sandbox extension: ${ext.name}" }
                }
            }

            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Sandbox extension update job failed" }
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SandboxExtensionUpdateJob"

        /**
         * Call with enabled=true when sandbox is turned ON.
         * Call with enabled=false when sandbox is turned OFF.
         */
        fun setupTask(context: Context, enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)

            if (!enabled) {
                workManager.cancelUniqueWork(TAG)
                logcat { "Sandbox auto-update worker cancelled" }
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SandboxExtensionUpdateJob>(
                1, TimeUnit.DAYS,
            )
                .setConstraints(constraints)
                .addTag(TAG)
                .build()

            workManager.enqueueUniquePeriodicWork(
                TAG,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )

            logcat { "Sandbox auto-update worker scheduled (daily)" }
        }
    }
}
