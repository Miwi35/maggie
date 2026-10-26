package com.maggie.app.data.location

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidLocationSchedulerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class FakeTransitions : TransitionRegistrar {
        var registered = false
        override fun register() { registered = true }
        override fun unregister() { registered = false }
    }

    private lateinit var work: WorkManager
    private val transitions = FakeTransitions()
    private lateinit var scheduler: AndroidLocationScheduler
    private val permissions = FakePermissions()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        work = WorkManager.getInstance(context)
        scheduler = AndroidLocationScheduler(work, transitions)
    }

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
