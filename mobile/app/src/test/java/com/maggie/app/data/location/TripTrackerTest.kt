package com.maggie.app.data.location

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TripTrackerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class FakeUpdates : LocationUpdates {
        val intervals = mutableListOf<Long>()
        var active = 0
        val fixes = MutableSharedFlow<Position>(extraBufferCapacity = 64)

        override fun gps(intervalMs: Long): Flow<Position> =
            fixes.onStart { intervals += intervalMs; active++ }.onCompletion { active-- }
    }

    private val t0 = Instant.parse("2026-10-27T17:00:00Z")
    private val api = RecordingApi()
    private val updates = FakeUpdates()
    private val carConnected = MutableStateFlow(false)
    private val batteryPercent = MutableStateFlow(80)
    private val clock = FakeClock(t0)
    private lateinit var preferences: LocationPreferences
    private lateinit var tracker: TripTracker

    @Before
    fun setUp() = runBlocking {
        preferences = preferencesIn(folder.root)
        preferences.setEnabled(true)
        tracker = TripTracker(
            preferences,
            LocationReporter(api.service, preferences, FakePermissions(), FakePositionSource(), clock),
            updates,
            object : CarConnectionSource { override val connected = carConnected },
            object : BatteryLevel { override val percent = batteryPercent },
        )
    }

    /** The DataStore and the tracker work on other threads: wait, in real time, for what the test expects. */
    private suspend fun until(what: suspend () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { while (!what()) delay(10) }
    }

    /** Long enough for something that must not happen to have happened. */
    private suspend fun quiet() = withContext(Dispatchers.Default) { delay(300) }

    private fun fix(seconds: Long, latitude: Double = 48.0 + seconds / 100_000.0, accuracy: Double = 6.0) =
        Position(latitude, 2.0, accuracy, t0.plusSeconds(seconds))

    @Test
    fun `standing still asks for no GPS fix at all`() = runTest {
        preferences.recordMotion(Motion.STILL, moved = false)

        val run = backgroundScope.launch { tracker.run() }
        until { run.isCompleted }

        assertTrue(updates.intervals.isEmpty())
    }

    @Test
    fun `walking asks for the GPS every 2 minutes, and stops asking when the owner is still again`() = runTest {
        preferences.recordMotion(Motion.MOVING, moved = true)
        val run = backgroundScope.launch { tracker.run() }
        until { updates.active == 1 }

        assertEquals(listOf(120_000L), updates.intervals)

        preferences.recordMotion(Motion.STILL, moved = false)
        until { run.isCompleted }

        assertEquals(0, updates.active)
    }

    @Test
    fun `connected to Android Auto the GPS is continuous, even for a phone that stands still`() = runTest {
        preferences.recordMotion(Motion.STILL, moved = false)
        carConnected.value = true

        backgroundScope.launch { tracker.run() }
        until { updates.active == 1 }

        assertEquals(listOf(10_000L), updates.intervals)
    }

    @Test
    fun `detected in a vehicle without Android Auto the GPS is continuous too`() = runTest {
        preferences.recordMotion(Motion.IN_VEHICLE, moved = true)

        backgroundScope.launch { tracker.run() }
        until { updates.active == 1 }

        assertEquals(listOf(10_000L), updates.intervals)
    }

    @Test
    fun `a battery falling to 15 percent at the wheel slows the GPS to every 5 minutes`() = runTest {
        preferences.recordMotion(Motion.IN_VEHICLE, moved = true)
        backgroundScope.launch { tracker.run() }
        until { updates.active == 1 }

        batteryPercent.value = 15
        until { updates.intervals.size == 2 && updates.active == 1 }

        assertEquals(listOf(10_000L, 300_000L), updates.intervals)
    }

    @Test
    fun `at the wheel the position goes out every minute at most, every fix being kept`() = runTest {
        preferences.recordMotion(Motion.IN_VEHICLE, moved = true)
        backgroundScope.launch { tracker.run() }
        until { updates.active == 1 }

        listOf(0L, 10L, 20L, 30L, 40L, 50L, 60L, 70L).forEach { updates.fixes.emit(fix(it)) }
        until { api.calls.size == 2 }
        quiet()

        assertEquals(listOf("/api/me/location", "/api/me/location"), api.calls.map { it.path })
        assertEquals(
            listOf(JsonPrimitive(t0.toString()), JsonPrimitive(t0.plusSeconds(60).toString())),
            api.calls.map { it.body.getValue("capturedAt") },
        )
        assertEquals(listOf(0L, 30L, 60L), preferences.gpsLog().map { it.capturedAt.epochSecond - t0.epochSecond })
    }

    @Test
    fun `the car disconnecting puts the car where the last GPS point was`() = runTest {
        carConnected.value = true
        backgroundScope.launch { tracker.run() }
        until { updates.active == 1 }
        updates.fixes.emit(fix(0))
        updates.fixes.emit(fix(40, latitude = 48.5))
        until { preferences.gpsLog().size == 2 }
        api.calls.clear()

        clock.now = t0.plusSeconds(50)
        carConnected.value = false
        until { api.calls.any { it.path == "/api/me/car" } }

        val call = api.calls.single { it.path == "/api/me/car" }
        assertEquals(48.5, call.body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(JsonPrimitive(t0.plusSeconds(50).toString()), call.body.getValue("parkedAt"))
    }

    @Test
    fun `a car that was never connected leaves nothing to place when the service starts`() = runTest {
        preferences.recordMotion(Motion.IN_VEHICLE, moved = true)

        backgroundScope.launch { tracker.run() }
        until { updates.active == 1 }
        quiet()

        assertTrue(api.calls.none { it.path == "/api/me/car" })
    }

    @Test
    fun `with the switch off nothing is followed`() = runTest {
        preferences.setEnabled(false)
        carConnected.value = true

        val run = backgroundScope.launch { tracker.run() }
        until { run.isCompleted }

        assertTrue(updates.intervals.isEmpty())
    }

    @Test
    fun `the mode is read without starting anything`() = runTest {
        assertEquals(TrackingMode.IDLE, tracker.currentMode())
        carConnected.value = true
        assertEquals(TrackingMode.DRIVING, tracker.currentMode())
        assertTrue(updates.intervals.isEmpty())
    }
}
