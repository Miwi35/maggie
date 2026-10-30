package com.maggie.app.data.health

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.maggie.app.data.repository.HealthSyncRepository
import com.maggie.app.data.repository.WorkoutRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.IOException
import java.util.concurrent.TimeUnit

/** When the Health Connect pass runs, and what the worker tells WorkManager (MAG-708). */
@RunWith(AndroidJUnit4::class)
class HealthSyncWorkerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var work: WorkManager
    private lateinit var scheduler: AndroidHealthSyncScheduler
    private val gateway = FakeHealthConnectGateway()
    private val api = HealthApi()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        work = WorkManager.getInstance(context)
        scheduler = AndroidHealthSyncScheduler(work)
        val sync = HealthConnectSync(gateway, HealthSyncRepository(api.service), WorkoutRepository(api.service), healthPreferencesIn(folder.root))
        stopKoin()
        startKoin { modules(module { single { sync } }) }
    }

    @After
    fun tearDown() = stopKoin()

    private fun infos(name: String) = work.getWorkInfosForUniqueWork(name).get()

    private fun doWork(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<HealthSyncWorker>(context).build().doWork()
    }

    @Test
    fun `the pass is planned every hour, on a network, and a launch keeps the plan`() {
        scheduler.startPeriodic()
        val info = infos(AndroidHealthSyncScheduler.PERIODIC).single()

        scheduler.startPeriodic()

        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(TimeUnit.HOURS.toMillis(1), info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals(info.id, infos(AndroidHealthSyncScheduler.PERIODIC).single().id)
    }

    @Test
    fun `a pass now is planned at once, and signing out cancels both`() {
        scheduler.startPeriodic()
        scheduler.syncNow()
        assertEquals(WorkInfo.State.ENQUEUED, infos(AndroidHealthSyncScheduler.NOW).single().state)

        scheduler.stop()

        assertTrue(infos(AndroidHealthSyncScheduler.PERIODIC).all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(infos(AndroidHealthSyncScheduler.NOW).all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `the worker retries on a network error`() {
        api.failWith = IOException("no network")

        assertEquals(ListenableWorker.Result.retry(), doWork())
    }

    @Test
    fun `the worker succeeds without sending when the permissions are refused`() {
        gateway.granted = emptySet()

        assertEquals(ListenableWorker.Result.success(), doWork())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `the worker succeeds without sending when Health Connect is missing`() {
        gateway.available = HealthConnectAvailability.NEEDS_UPDATE

        assertEquals(ListenableWorker.Result.success(), doWork())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `the worker succeeds once the API took the batch`() {
        assertEquals(ListenableWorker.Result.success(), doWork())
        assertEquals(1, api.syncBodies().size)
    }

    @Test
    fun `only a failure worth another try asks WorkManager for one`() {
        assertEquals(ListenableWorker.Result.retry(), SyncOutcome.RETRY.toWorkResult())
        listOf(SyncOutcome.SENT, SyncOutcome.UNAVAILABLE, SyncOutcome.NOT_PERMITTED, SyncOutcome.REFUSED).forEach {
            assertEquals(it.name, ListenableWorker.Result.success(), it.toWorkResult())
        }
    }
}
