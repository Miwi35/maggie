package com.maggie.app.data.location

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.rules.TemporaryFolder
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ParkingDetectorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val api = RecordingApi()
    private val scheduler = FakeScheduler()
    private lateinit var preferences: LocationPreferences
    private lateinit var detector: ParkingDetector
    private lateinit var handler: ActivityTransitionHandler

    private val t0 = Instant.parse("2026-10-26T21:29:00Z")

    private fun leaveCar(at: Instant = t0) = TransitionEvent(Movement.IN_VEHICLE, entering = false, at = at)
    private fun enterCar(at: Instant = t0) = TransitionEvent(Movement.IN_VEHICLE, entering = true, at = at)
    private fun standUp(at: Instant = t0.plusSeconds(20)) = TransitionEvent(Movement.ON_FOOT, entering = true, at = at)

    @Before
    fun setUp() = runTest {
        preferences = preferencesIn(folder.root)
        preferences.setEnabled(true)
        detector = ParkingDetector(preferences)
        handler = ActivityTransitionHandler(detector, LocationReporter(api.service, preferences, FakePermissions(), FakePositionSource()), scheduler)
    }

    @Test
    fun `leaving a car then standing on one's feet puts one car on the map`() = runTest {
        handler.handle(listOf(leaveCar(), standUp()))

        val call = api.calls.single()
        assertEquals("PUT", call.method)
        assertEquals("/api/me/car", call.path)
    }

    @Test
    fun `walking counts as being on foot, and a second foot event does not park the car twice`() = runTest {
        handler.handle(listOf(leaveCar(), standUp(), standUp(t0.plusSeconds(30))))

        assertEquals(1, api.calls.size)
    }

    @Test
    fun `the two events may reach the app apart, the first one is remembered`() = runTest {
        handler.handle(listOf(leaveCar()))
        assertTrue(api.calls.isEmpty())

        handler.handle(listOf(standUp()))

        assertEquals(1, api.calls.size)
    }

    @Test
    fun `any other sequence parks nothing`() = runTest {
        // Walking with no car before it.
        handler.handle(listOf(standUp()))
        // Feet first, car left after.
        handler.handle(listOf(standUp(t0), leaveCar(t0.plusSeconds(5))))
        // Left the car, got back in, then walked.
        handler.handle(listOf(leaveCar(), enterCar(t0.plusSeconds(5)), standUp(t0.plusSeconds(10))))
        // Getting into a car is not leaving one.
        handler.handle(listOf(enterCar()))
        // The owner left the car, sat somewhere, and walked off much later.
        handler.handle(listOf(leaveCar(), standUp(t0.plus(ParkingDetector.MAX_GAP).plusSeconds(1))))

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `the exit and the foot event stamped the same instant park the car whatever their order`() = runTest {
        assertEquals(t0, detector.onTransitions(listOf(standUp(t0), leaveCar(t0))))
    }

    @Test
    fun `an unrelated transition leaves the exit waiting`() = runTest {
        handler.handle(listOf(leaveCar(), TransitionEvent(Movement.OTHER, entering = true, at = t0.plusSeconds(3)), standUp()))

        assertEquals(1, api.calls.size)
    }

    @Test
    fun `a parking whose send failed asks WorkManager to try again`() = runTest {
        api.status = HttpStatusCode.ServiceUnavailable

        handler.handle(listOf(leaveCar(), standUp()))

        assertEquals(1, scheduler.carRetries)
    }

    @Test
    fun `a parking is detected, and its time is the moment the car was left`() = runTest {
        assertNull(detector.onTransitions(listOf(standUp())))
        assertEquals(t0, detector.onTransitions(listOf(leaveCar(), standUp())))
    }

    @Test
    fun `Movement maps ON_FOOT and WALKING to being on foot`() {
        assertEquals(Movement.ON_FOOT, Movement.of(com.google.android.gms.location.DetectedActivity.ON_FOOT))
        assertEquals(Movement.ON_FOOT, Movement.of(com.google.android.gms.location.DetectedActivity.WALKING))
        assertEquals(Movement.IN_VEHICLE, Movement.of(com.google.android.gms.location.DetectedActivity.IN_VEHICLE))
        assertEquals(Movement.OTHER, Movement.of(com.google.android.gms.location.DetectedActivity.STILL))
    }

    @Test
    fun `the events of a delivery are dated from the boot clock`() {
        val result = com.google.android.gms.location.ActivityTransitionResult(
            listOf(
                com.google.android.gms.location.ActivityTransitionEvent(
                    com.google.android.gms.location.DetectedActivity.IN_VEHICLE,
                    com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_EXIT,
                    5_000_000_000L,
                ),
                com.google.android.gms.location.ActivityTransitionEvent(
                    com.google.android.gms.location.DetectedActivity.WALKING,
                    com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                    9_000_000_000L,
                ),
            ),
        )

        val events = TransitionEvent.from(result, elapsedNowNanos = 10_000_000_000L, now = t0)

        assertEquals(
            listOf(
                TransitionEvent(Movement.IN_VEHICLE, entering = false, at = t0.minusSeconds(5)),
                TransitionEvent(Movement.ON_FOOT, entering = true, at = t0.minusSeconds(1)),
            ),
            events,
        )
    }
}
