package eu.kanade.tachiyomi.data.sync.replica

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker executing periodic background sync and expedited pushes on app exit.
 */
class SyncWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val repository = Injekt.get<GoogleDriveSyncRepository>()
        val syncSettings = Injekt.get<SyncSettings>()

        if (!syncSettings.isSignedIn) {
            return Result.success()
        }

        return when (val res = repository.sync(force = true)) {
            is SyncResult.Success -> Result.success()
            is SyncResult.SignInRequired -> Result.failure()
            is SyncResult.Error -> if (res.retryable && runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    object Scheduler {

        fun schedule(context: Context) {
            val syncSettings = Injekt.get<SyncSettings>()
            val workManager = WorkManager.getInstance(context)

            val interval = syncSettings.intervalMinutes
            if (interval <= 0 || !syncSettings.isSignedIn) {
                unschedule(context)
                return
            }

            val request = PeriodicWorkRequestBuilder<SyncWorker>(interval.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints(syncSettings.isWifiOnly))
                .addTag(TAG_PERIODIC)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            workManager.enqueueUniquePeriodicWork(
                TAG_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun unschedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(TAG_PERIODIC)
            workManager.cancelUniqueWork(TAG_SOON)
        }

        /**
         * Enqueues an expedited sync as soon as network allows (fired on app minimize).
         */
        fun syncSoon(context: Context) {
            val syncSettings = Injekt.get<SyncSettings>()
            if (!syncSettings.isSignedIn) return

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints(syncSettings.isWifiOnly))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG_SOON)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                TAG_SOON,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        private fun constraints(isWifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (isWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
    }

    companion object {
        private const val TAG_PERIODIC = "replica_sync_periodic"
        private const val TAG_SOON = "replica_sync_soon"
        private const val MAX_ATTEMPTS = 3
    }
}
