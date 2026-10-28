package com.maggie.app.data.location

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.time.Instant
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidLocationSchedulerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class FakeTransitions : TransitionRegistrar {
        var registered = false
        var registrations = 0
        override fun register() { registered = true; registrations++ }
        override fun unregister() { registered = false }
    }

    private lateinit var work: WorkManager
    private val transitions = FakeTransitions()
    private val passive = FakePassive()
    private val launcher = FakeLauncher()
    private lateinit var scheduler: AndroidLocationScheduler
    private val permissions = FakePermissions()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        work = WorkManager.getInstance(context)
        scheduler = AndroidLocationScheduler(work, transitions, passive)
    }

    @After
    fun tearDown() = stopKoin()

    private fun infos(name: String) = work.getWorkInfosForUniqueWork(name).get()

    @Test
    fun `starting plans the position every 30 minutes and listens for the car`() {
        scheduler.startTracking()

        val info = infos(AndroidLocationScheduler.PERIODIC_POSITION).single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(transitions.registered)
        assertEquals(TimeUnit.MINUTES.toMillis(30), info.periodicityInfo!!.repeatIntervalMillis)
    }

    @Test
    fun `starting again at every launch keeps the plan instead of restarting it`() {
        scheduler.startTracking()
        val first = infos(AndroidLocationScheduler.PERIODIC_POSITION).single().id

        scheduler.startTracking()

        assertEquals(first, infos(AndroidLocationScheduler.PERIODIC_POSITION).single().id)
    }

    @Test
    fun `a position request from the push is planned at once`() {
        scheduler.requestPositionNow()

        assertEquals(WorkInfo.State.ENQUEUED, infos(AndroidLocationScheduler.POSITION_NOW).single().state)
    }

    /** Runs the work the way WorkManager does once its constraints are met, with the reporter's position source recorded. */
    private fun accuraciesAskedBy(name: String, periodic: Boolean, enabled: Boolean = true): List<PositionAccuracy> = runBlocking {
        val source = FakePositionSource()
        val preferences = preferencesIn(folder.root)
        preferences.setEnabled(enabled)
        val reporter = LocationReporter(RecordingApi().service, preferences, permissions, source)
        stopKoin()
        startKoin {
            modules(
                module {
                    single { reporter }
                    single<TransitionRegistrar> { transitions }
                    single<PassiveRegistrar> { passive }
                    single<TripLauncher> { launcher }
                },
            )
        }
        val id = infos(name).single().id
        val driver = WorkManagerTestInitHelper.getTestDriver(ApplicationProvider.getApplicationContext())!!
        driver.setAllConstraintsMet(id)
        if (periodic) driver.setPeriodDelayMet(id)
        repeat(100) {
            val finished = infos(name).single().state == WorkInfo.State.SUCCEEDED
            if (source.requested.isEmpty() && !finished) delay(50)
        }
        source.requested.toList()
    }

    @Test
    fun `the position asked for by the push is read from the GPS`() {
        scheduler.requestPositionNow()

        assertEquals(listOf(PositionAccuracy.HIGH), accuraciesAskedBy(AndroidLocationScheduler.POSITION_NOW, periodic = false))
    }

    @Test
    fun `the position every 30 minutes is read from the cheap sources`() {
        scheduler.startTracking()

        assertEquals(listOf(PositionAccuracy.BALANCED), accuraciesAskedBy(AndroidLocationScheduler.PERIODIC_POSITION, periodic = true))
    }

    @Test
    fun `every run of the 30-minute position listens for the car again, a reboot or an update having dropped it`() {
        scheduler.startTracking()
        val registrations = transitions.registrations

        accuraciesAskedBy(AndroidLocationScheduler.PERIODIC_POSITION, periodic = true)

        assertEquals(registrations + 1, transitions.registrations)
    }

    @Test
    fun `starting listens for the fixes other apps obtain, stopping stops listening`() {
        scheduler.startTracking()
        assertTrue(passive.registered)

        scheduler.stopTracking()

        assertEquals(false, passive.registered)
    }

    @Test
    fun `every run of the 30-minute position brings back the passive listening and the trip service`() {
        scheduler.startTracking()
        val registrations = passive.registrations
        val starts = launcher.starts

        accuraciesAskedBy(AndroidLocationScheduler.PERIODIC_POSITION, periodic = true)

        assertEquals(registrations + 1, passive.registrations)
        assertEquals(starts + 1, launcher.starts)
    }

    @Test
    fun `a push after the switch went off starts no trip service`() {
        scheduler.requestPositionNow()

        accuraciesAskedBy(AndroidLocationScheduler.POSITION_NOW, periodic = false, enabled = false)

        assertEquals(0, launcher.starts)
        assertEquals(0, passive.registrations)
    }

    @Test
    fun `the passive points are handed to WorkManager in order and survive the trip through the work data`() {
        val first = Position(48.1, 2.1, 9.0, Instant.parse("2026-10-27T17:00:00Z"))
        val second = Position(48.2, 2.2, 5.0, Instant.parse("2026-10-27T17:01:00Z"))

        scheduler.handlePassivePoints(listOf(first))
        scheduler.handlePassivePoints(listOf(second))

        assertEquals(2, infos(AndroidLocationScheduler.PASSIVE).size)
        assertEquals(listOf(first, second), PassivePointsWorker.fromData(PassivePointsWorker.toData(listOf(first, second))))
    }

    @Test
    fun `a push after the switch went off does not bring the listening back`() {
        scheduler.requestPositionNow()

        accuraciesAskedBy(AndroidLocationScheduler.POSITION_NOW, periodic = false, enabled = false)

        assertEquals(0, transitions.registrations)
    }

    @Test
    fun `a push without the activity permission does not bring the listening back`() {
        permissions.activity = false
        scheduler.requestPositionNow()

        accuraciesAskedBy(AndroidLocationScheduler.POSITION_NOW, periodic = false)

        assertEquals(0, transitions.registrations)
    }

    @Test
    fun `the transitions of a delivery are handed to WorkManager, in order, none lost`() {
        val first = TransitionEvent(Movement.IN_VEHICLE, entering = false, at = Instant.parse("2026-10-26T21:29:00Z"))
        val second = TransitionEvent(Movement.ON_FOOT, entering = true, at = Instant.parse("2026-10-26T21:29:20Z"))

        scheduler.handleTransitions(listOf(first))
        scheduler.handleTransitions(listOf(second))

        assertEquals(2, infos(AndroidLocationScheduler.TRANSITIONS).size)
        assertEquals(listOf(first, second), TransitionEvent.fromData(TransitionEvent.toData(listOf(first, second))))
    }

    @Test
    fun `stopping cancels the plan, the retries and the listening`() {
        scheduler.startTracking()
        scheduler.requestPositionNow()
        scheduler.handleTransitions(listOf(TransitionEvent(Movement.IN_VEHICLE, entering = false, at = Instant.parse("2026-10-26T21:29:00Z"))))
        scheduler.retryCarLater()

        scheduler.stopTracking()

        listOf(
            AndroidLocationScheduler.PERIODIC_POSITION,
            AndroidLocationScheduler.POSITION_NOW,
            AndroidLocationScheduler.TRANSITIONS,
            AndroidLocationScheduler.RETRY_CAR,
        ).forEach { name -> assertTrue(name, infos(name).all { it.state == WorkInfo.State.CANCELLED }) }
        assertEquals(false, transitions.registered)
        assertEquals(false, passive.registered)
    }

    @Test
    fun `turning the switch on plans the work and off cancels it`() = runBlocking {
        val tracking = DepartureTracking(preferencesIn(folder.root), scheduler, permissions)

        tracking.enable()
        assertEquals(WorkInfo.State.ENQUEUED, infos(AndroidLocationScheduler.PERIODIC_POSITION).single().state)

        tracking.disable()
        assertEquals(WorkInfo.State.CANCELLED, infos(AndroidLocationScheduler.PERIODIC_POSITION).single().state)
    }

    @Test
    fun `signing out turns the switch off and stops everything, signing in again resumes only a switch left on`() = runBlocking {
        val scheduler = FakeScheduler()
        val preferences = preferencesIn(folder.root)
        preferences.setEnabled(true)
        val tracking = DepartureTracking(preferences, scheduler, permissions)

        tracking.follow(kotlinx.coroutines.flow.flowOf(true, false, true))

        assertEquals(false, preferences.isEnabled())
        assertEquals(1, scheduler.started)
        assertEquals(1, scheduler.stopped)
    }

    @Test
    fun `at launch the plan is made again while the switch is on, and a taken-back permission turns it off`() = runBlocking {
        val scheduler = FakeScheduler()
        val preferences = preferencesIn(folder.root)
        val tracking = DepartureTracking(preferences, scheduler, permissions)

        tracking.resume()
        assertEquals(0, scheduler.started)

        preferences.setEnabled(true)
        tracking.resume()
        assertEquals(1, scheduler.started)

        permissions.background = false
        tracking.resume()
        assertEquals(1, scheduler.started)
        assertEquals(1, scheduler.stopped)
        assertEquals(false, preferences.isEnabled())
    }
}
