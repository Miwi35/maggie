package com.maggie.app.data.health

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/** When the Health Connect pass runs (MAG-708): every hour while signed in, and once at each opening of the app. */
interface HealthSyncScheduler {
    /** The hourly pass; KEEP, so a launch does not push the next one back. */
    fun startPeriodic()

    /** One pass now — at opening, or when the permissions have just been granted. */
    fun syncNow()

    /** Signed out: no pass at all. */
    fun stop()
}

class AndroidHealthSyncScheduler(private val work: WorkManager) : HealthSyncScheduler {

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun startPeriodic() {
        val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    // KEEP: a pass already waiting does the same job; the sync's lock keeps it from running beside the hourly one.
    override fun syncNow() {
        val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    override fun stop() {
        work.cancelUniqueWork(PERIODIC)
        work.cancelUniqueWork(NOW)
    }

    companion object {
        const val PERIODIC = "health-connect-sync"
        const val NOW = "health-connect-sync-now"
        const val PERIOD_HOURS = 1L
        private const val BACKOFF_MINUTES = 5L
    }
}

/** Runs [HealthConnectSync]: a network or server failure is tried again; no Health Connect or no permission is not an error. */
class HealthSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val koin = GlobalContext.getOrNull() ?: return Result.retry()
        return koin.get<HealthConnectSync>().run().toWorkResult()
    }
}

/** Only a failure worth another try asks WorkManager for one. */
internal fun SyncOutcome.toWorkResult(): ListenableWorker.Result =
    if (this == SyncOutcome.RETRY) ListenableWorker.Result.retry() else ListenableWorker.Result.success()
