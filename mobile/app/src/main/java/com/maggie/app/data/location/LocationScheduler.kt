package com.maggie.app.data.location

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

/** What runs in the background while « Partir à l'heure » is on (MAG-651). */
interface LocationScheduler {
    /** The position every 30 minutes, and the listening for the owner leaving a car. */
    fun startTracking()

    /** Everything above, and any retry still waiting. */
    fun stopTracking()

    fun retryPositionLater()

    fun retryCarLater()
}

/** Registers and removes the Activity Recognition transitions; apart from the scheduler so WorkManager can be tested without Play Services. */
interface TransitionRegistrar {
    fun register()
    fun unregister()
}

class AndroidLocationScheduler(
    private val work: WorkManager,
    private val transitions: TransitionRegistrar,
) : LocationScheduler {

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    // KEEP: the app starts this at every launch, which must not push the next report back.
    override fun startTracking() {
        val request = PeriodicWorkRequestBuilder<PositionWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC_POSITION, ExistingPeriodicWorkPolicy.KEEP, request)
        transitions.register()
    }

    override fun stopTracking() {
        listOf(PERIODIC_POSITION, RETRY_POSITION, RETRY_CAR).forEach(work::cancelUniqueWork)
        transitions.unregister()
    }

    override fun retryPositionLater() {
        val request = OneTimeWorkRequestBuilder<PositionWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        work.enqueueUniqueWork(RETRY_POSITION, ExistingWorkPolicy.REPLACE, request)
    }

    override fun retryCarLater() {
        val request = OneTimeWorkRequestBuilder<CarPlaceWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        work.enqueueUniqueWork(RETRY_CAR, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val PERIODIC_POSITION = "departure-position"
        const val RETRY_POSITION = "departure-position-retry"
        const val RETRY_CAR = "departure-car-retry"
        const val PERIOD_MINUTES = 30L
        private const val BACKOFF_MINUTES = 1L
    }
}

/** The position every 30 minutes; a failed send is tried again by WorkManager with its backoff, never a crash. */
class PositionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val koin = GlobalContext.getOrNull() ?: return Result.retry()
        return koin.get<LocationReporter>().reportPosition().toWorkResult()
    }
}

/** The car's place a failed send kept. */
class CarPlaceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val koin = GlobalContext.getOrNull() ?: return Result.retry()
        return koin.get<LocationReporter>().sendPendingCar().toWorkResult()
    }
}

private fun ReportOutcome.toWorkResult(): ListenableWorker.Result =
    if (this == ReportOutcome.RETRY) ListenableWorker.Result.retry() else ListenableWorker.Result.success()
