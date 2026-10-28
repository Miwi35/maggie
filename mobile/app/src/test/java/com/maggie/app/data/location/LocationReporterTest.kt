package com.maggie.app.data.location

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class LocationReporterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val api = RecordingApi()
    private val permissions = FakePermissions()
    private val source = FakePositionSource()
    private lateinit var preferences: LocationPreferences
    private val t0 = Instant.parse("2026-10-27T17:00:00Z")
    private val clock = FakeClock(t0.plusSeconds(600))
    private lateinit var reporter: LocationReporter

    @Before
    fun setUp() {
        preferences = preferencesIn(folder.root)
        reporter = LocationReporter(api.service, preferences, permissions, source, clock)
    }

    private suspend fun switchOn() = preferences.setEnabled(true)

    @Test
    fun `a position request with the switch on sends the fix, its precision and the instant it was taken`() = runTest {
        switchOn()

        assertEquals(ReportOutcome.SENT, reporter.reportPosition())

        assertEquals(1, api.calls.size)
        val call = api.calls.single()
        assertEquals("PUT", call.method)
        assertEquals("/api/me/location", call.path)
        assertEquals(48.8566, call.body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(2.3522, call.body.getValue("longitude").jsonPrimitive.double, 0.0)
        assertEquals(12.0, call.body.getValue("accuracy").jsonPrimitive.double, 0.0)
        assertEquals(JsonPrimitive("2026-10-26T21:30:00Z"), call.body.getValue("capturedAt"))
        assertEquals(JsonPrimitive("gps"), call.body.getValue("source"))
    }

    @Test
    fun `the position asked for before a departure takes a GPS fix`() = runTest {
        switchOn()

        reporter.reportPosition(precise = true)

        assertEquals(1, source.asked)
    }

    @Test
    fun `a periodic position of a phone with no kept point takes a GPS fix`() = runTest {
        switchOn()

        reporter.reportPosition()

        assertEquals(1, source.asked)
    }

    @Test
    fun `a fix that is not GPS-grade is never sent`() = runTest {
        switchOn()
        source.position = FakePositionSource.PARIS.copy(accuracy = 500.0, source = FixSource.NETWORK)

        assertEquals(ReportOutcome.SKIPPED, reporter.reportPosition(precise = true))

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `the car is placed from the GPS`() = runTest {
        switchOn()
        val parkedAt = Instant.parse("2026-10-26T21:29:40Z")
        clock.now = parkedAt.plusSeconds(20)

        reporter.reportCar(parkedAt)

        assertEquals(1, source.asked)
    }

    @Test
    fun `a position request with the switch off sends nothing and does not even look for a fix`() = runTest {
        reporter.reportPosition()

        assertTrue(api.calls.isEmpty())
        assertEquals(0, source.asked)
    }

    @Test
    fun `a position request without the permission sends nothing, whichever one is missing`() = runTest {
        switchOn()

        permissions.fine = false
        reporter.reportPosition()
        permissions.fine = true
        permissions.background = false
        reporter.reportPosition()

        assertTrue(api.calls.isEmpty())
        assertEquals(0, source.asked)
    }

    @Test
    fun `a network failure is not thrown, the work is told to try again`() = runTest {
        switchOn()
        api.status = HttpStatusCode.ServiceUnavailable

        assertEquals(ReportOutcome.RETRY, reporter.reportPosition())

        assertEquals(1, api.calls.size)
    }

    @Test
    fun `a report the API refuses is dropped, not retried`() = runTest {
        switchOn()
        api.status = HttpStatusCode.BadRequest

        assertEquals(ReportOutcome.DROPPED, reporter.reportPosition())
    }

    @Test
    fun `the periodic report says whether to try again`() = runTest {
        switchOn()

        assertEquals(ReportOutcome.SENT, reporter.reportPosition())
        api.status = HttpStatusCode.InternalServerError
        assertEquals(ReportOutcome.RETRY, reporter.reportPosition())
        api.status = HttpStatusCode.TooManyRequests
        assertEquals(ReportOutcome.RETRY, reporter.reportPosition())
    }

    @Test
    fun `the car is sent where the owner stood, parked when they stepped out`() = runTest {
        switchOn()
        val parkedAt = Instant.parse("2026-10-26T21:29:40Z")
        clock.now = parkedAt.plusSeconds(20)

        assertEquals(ReportOutcome.SENT, reporter.reportCar(parkedAt))

        val call = api.calls.single()
        assertEquals("/api/me/car", call.path)
        assertEquals(JsonPrimitive("2026-10-26T21:29:40Z"), call.body.getValue("parkedAt"))
        assertEquals(48.8566, call.body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertNull(preferences.pendingCar())
    }

    @Test
    fun `a car whose send failed is kept and sent again from where it was, not from where the owner is now`() = runTest {
        switchOn()
        val parkedAt = Instant.parse("2026-10-26T21:29:40Z")
        clock.now = parkedAt.plusSeconds(20)
        api.status = HttpStatusCode.BadGateway
        assertEquals(ReportOutcome.RETRY, reporter.reportCar(parkedAt))
        assertEquals(PendingCar(48.8566, 2.3522, parkedAt), preferences.pendingCar())

        source.position = FakePositionSource.PARIS.copy(latitude = 45.0)
        api.status = HttpStatusCode.NoContent
        assertEquals(ReportOutcome.SENT, reporter.sendPendingCar())

        assertEquals(48.8566, api.calls.last().body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertNull(preferences.pendingCar())
    }

    @Test
    fun `a car is sent only with the switch on and the permissions`() = runTest {
        assertEquals(ReportOutcome.SKIPPED, reporter.reportCar(Instant.now()))
        switchOn()
        permissions.background = false
        assertEquals(ReportOutcome.SKIPPED, reporter.reportCar(Instant.now()))

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a car kept while the switch goes off is forgotten, not sent`() = runTest {
        preferences.setPendingCar(PendingCar(1.0, 2.0, Instant.parse("2026-10-26T21:00:00Z")))

        assertEquals(ReportOutcome.SKIPPED, reporter.sendPendingCar())

        assertTrue(api.calls.isEmpty())
        assertNull(preferences.pendingCar())
    }

    private fun gps(seconds: Long, latitude: Double = 45.76, accuracy: Double = 5.0) =
        Position(latitude, 4.83, accuracy, t0.plusSeconds(seconds))

    @Test
    fun `a phone that has not moved sends its last GPS point again and wakes no sensor`() = runTest {
        switchOn()
        preferences.addGpsPoint(gps(0))
        preferences.recordMotion(Motion.STILL, moved = false)

        assertEquals(ReportOutcome.SENT, reporter.reportPosition())

        val call = api.calls.single()
        assertEquals(45.76, call.body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(5.0, call.body.getValue("accuracy").jsonPrimitive.double, 0.0)
        assertEquals(JsonPrimitive(t0.plusSeconds(600).toString()), call.body.getValue("capturedAt"))
        assertEquals(JsonPrimitive("gps"), call.body.getValue("source"))
        assertEquals(0, source.asked)
    }

    @Test
    fun `a network fix claiming 30 m is neither sent nor kept as a GPS point`() = runTest {
        switchOn()
        val wifi = Position(45.76, 4.83, 30.0, t0.plusSeconds(100), FixSource.NETWORK)
        source.position = wifi

        reporter.reportPosition()
        reporter.keepFix(wifi)
        reporter.reportFix(wifi)
        reporter.reportPassive(listOf(wifi))

        assertTrue(api.calls.isEmpty())
        assertTrue(preferences.gpsLog().isEmpty())
        assertNull(preferences.keptGpsPoint())
    }

    @Test
    fun `28 Oct - the GPS point at the car stays the position of the still phone, a network fix 3 km away is never read`() = runTest {
        switchOn()
        source.position = Position(45.76, 4.83, 20.0, t0.plusSeconds(100), FixSource.GPS)
        assertEquals(ReportOutcome.SENT, reporter.reportPosition(precise = true))
        clock.now = t0.plusSeconds(4000)
        source.position = Position(45.787, 4.83, 500.0, t0.plusSeconds(3990), FixSource.NETWORK)

        assertEquals(ReportOutcome.SENT, reporter.reportPosition())

        assertEquals(2, api.calls.size)
        api.calls.forEach {
            assertEquals(JsonPrimitive("gps"), it.body.getValue("source"))
            assertEquals(45.76, it.body.getValue("latitude").jsonPrimitive.double, 0.0)
        }
        assertEquals(1, source.asked)
    }

    @Test
    fun `a phone that has moved takes a GPS fix instead of resending the old point`() = runTest {
        switchOn()
        preferences.addGpsPoint(gps(0))
        preferences.recordMotion(Motion.MOVING, moved = true)
        source.position = Position(45.77, 4.84, 8.0, t0.plusSeconds(590))

        reporter.reportPosition()

        assertEquals(45.77, api.calls.single().body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(1, source.asked)
    }

    @Test
    fun `a phone that has moved, with no GPS fix in time, sends its last GPS point as it was`() = runTest {
        switchOn()
        preferences.addGpsPoint(gps(0))
        preferences.recordMotion(Motion.MOVING, moved = true)
        source.position = null

        assertEquals(ReportOutcome.SENT, reporter.reportPosition())

        val call = api.calls.single()
        assertEquals(45.76, call.body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(JsonPrimitive(t0.toString()), call.body.getValue("capturedAt"))
    }

    @Test
    fun `with no GPS fix in time and no GPS point at all nothing is sent and no retry drains the battery`() = runTest {
        switchOn()
        source.position = null

        assertEquals(ReportOutcome.SKIPPED, reporter.reportPosition(precise = true))

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a position asked for before a departure takes a GPS fix even when the phone is still`() = runTest {
        switchOn()
        preferences.addGpsPoint(gps(0))
        source.position = Position(45.77, 4.84, 4.0, t0.plusSeconds(590))

        reporter.reportPosition(precise = true)

        assertEquals(45.77, api.calls.single().body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(1, source.asked)
    }

    @Test
    fun `asked before a departure with no GPS fix in time, the still phone sends its last GPS point`() = runTest {
        switchOn()
        preferences.addGpsPoint(gps(0))
        source.position = null

        assertEquals(ReportOutcome.SENT, reporter.reportPosition(precise = true))

        val call = api.calls.single()
        assertEquals(45.76, call.body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(JsonPrimitive(t0.plusSeconds(600).toString()), call.body.getValue("capturedAt"))
    }

    @Test
    fun `a passive point newer and more precise than the last one sent is sent`() = runTest {
        switchOn()
        preferences.setLastSent(gps(0, accuracy = 30.0))

        val outcome = reporter.reportPassive(listOf(gps(60, latitude = 45.8, accuracy = 8.0), gps(90, latitude = 45.9, accuracy = 6.0)))

        assertEquals(ReportOutcome.SENT, outcome)
        assertEquals(45.9, api.calls.single().body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(gps(90, latitude = 45.9, accuracy = 6.0), preferences.lastSent())
    }

    @Test
    fun `a passive point older or vaguer than the last one sent is not sent`() = runTest {
        switchOn()
        preferences.setLastSent(gps(100, accuracy = 10.0))

        val outcome = reporter.reportPassive(listOf(gps(50, accuracy = 3.0), gps(200, accuracy = 40.0)))

        assertEquals(ReportOutcome.SKIPPED, outcome)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a passive point is sent when nothing was sent yet, and never with the switch off`() = runTest {
        assertEquals(ReportOutcome.SKIPPED, reporter.reportPassive(listOf(gps(0))))
        switchOn()

        assertEquals(ReportOutcome.SENT, reporter.reportPassive(listOf(gps(0))))
        assertEquals(1, api.calls.size)
    }

    @Test
    fun `the car stands where the last GPS point of the trip was, not where the owner is now`() = runTest {
        switchOn()
        preferences.addGpsPoint(gps(0, latitude = 45.70))
        preferences.addGpsPoint(gps(40, latitude = 45.76))
        preferences.addGpsPoint(gps(120, latitude = 45.80))
        source.position = FakePositionSource.PARIS

        assertEquals(ReportOutcome.SENT, reporter.reportCar(t0.plusSeconds(50)))

        val call = api.calls.single()
        assertEquals("/api/me/car", call.path)
        assertEquals(45.76, call.body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(0, source.asked)
    }

    @Test
    fun `a trip point too old for the exit is not the car's place, the GPS is asked instead`() = runTest {
        switchOn()
        preferences.addGpsPoint(gps(0, latitude = 45.70))
        clock.now = t0.plusSeconds(3620)

        reporter.reportCar(t0.plusSeconds(3600))

        assertEquals(48.8566, api.calls.single().body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(1, source.asked)
    }

    @Test
    fun `without a trip point and without a fix, the car is dropped rather than put in the wrong place`() = runTest {
        switchOn()
        source.position = null
        clock.now = t0.plusSeconds(10)

        assertEquals(ReportOutcome.DROPPED, reporter.reportCar(t0))

        assertTrue(api.calls.isEmpty())
        assertNull(preferences.pendingCar())
    }

    @Test
    fun `the car's connection ending places the car at the last point at the time of now`() = runTest {
        switchOn()
        preferences.addGpsPoint(gps(590, latitude = 45.71))

        reporter.reportCarDisconnected(t0)

        val call = api.calls.single()
        assertEquals(45.71, call.body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(JsonPrimitive(t0.plusSeconds(600).toString()), call.body.getValue("parkedAt"))
    }

    @Test
    fun `a phone said to be still but whose GPS fix asked for is far from the kept point sends the fix`() = runTest {
        switchOn()
        preferences.addGpsPoint(gps(0))
        preferences.recordMotion(Motion.STILL, moved = false)
        source.position = Position(46.5, 4.84, 30.0, t0.plusSeconds(590))

        reporter.reportPosition(precise = true)

        assertEquals(46.5, api.calls.single().body.getValue("latitude").jsonPrimitive.double, 0.0)
    }

    @Test
    fun `a kept point stays the position of a still phone whatever its age`() = runTest {
        switchOn()
        preferences.addGpsPoint(gps(0))
        preferences.recordMotion(Motion.STILL, moved = false)
        source.position = Position(45.761, 4.831, 800.0, t0.plusSeconds(86390), FixSource.NETWORK)
        clock.now = t0.plusSeconds(86400)

        reporter.reportPosition()

        val call = api.calls.single()
        assertEquals(45.76, call.body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(5.0, call.body.getValue("accuracy").jsonPrimitive.double, 0.0)
        assertEquals(0, source.asked)
    }

    @Test
    fun `28 Oct 22 h 44 - indoors, five hours after the car, the GPS point stays the position and the network fix 3 km away is not read`() = runTest {
        switchOn()
        preferences.addGpsPoint(gps(0))
        preferences.recordMotion(Motion.STILL, moved = false)
        source.position = Position(45.787, 4.83, 500.0, t0.plusSeconds(17990), FixSource.NETWORK)
        clock.now = t0.plusSeconds(18000)

        reporter.reportPosition()

        val call = api.calls.single()
        assertEquals(45.76, call.body.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(JsonPrimitive("gps"), call.body.getValue("source"))
        assertEquals(0, source.asked)
    }

    @Test
    fun `a network fix is never sent, even when the position is asked for`() = runTest {
        switchOn()
        source.position = FakePositionSource.PARIS.copy(accuracy = 500.0, source = FixSource.NETWORK)

        reporter.reportPosition(precise = true)

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `without a trip point the fix of now is the car's place only while the owner cannot have walked far`() = runTest {
        switchOn()
        clock.now = t0.plusSeconds(120)
        assertEquals(ReportOutcome.SENT, reporter.reportCar(t0))
        assertEquals(1, source.asked)

        api.calls.clear()
        source.asked = 0
        clock.now = t0.plusSeconds(1800)
        assertEquals(ReportOutcome.DROPPED, reporter.reportCar(t0))

        assertTrue(api.calls.isEmpty())
        assertEquals(0, source.asked)
    }

    @Test
    fun `the car's connection ending after the owner's exit was reported does not report the car a second time`() = runTest {
        switchOn()
        preferences.addGpsPoint(gps(590, latitude = 45.71))
        reporter.reportCar(t0.plusSeconds(580))
        api.calls.clear()
        clock.now = t0.plusSeconds(700)

        assertEquals(ReportOutcome.SKIPPED, reporter.reportCarDisconnected(t0))

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `an exit reported before this connection began does not hide the disconnection`() = runTest {
        switchOn()
        preferences.addGpsPoint(gps(590, latitude = 45.71))
        reporter.reportCar(t0.plusSeconds(580))
        api.calls.clear()
        clock.now = t0.plusSeconds(700)

        assertEquals(ReportOutcome.SENT, reporter.reportCarDisconnected(t0.plusSeconds(600)))

        assertEquals("/api/me/car", api.calls.single().path)
    }

    @Test
    fun `a fix of the trip is sent and kept, a vague GPS one is sent but not kept`() = runTest {
        switchOn()

        reporter.reportFix(gps(0))
        reporter.reportFix(Position(45.9, 4.83, 400.0, t0.plusSeconds(60), FixSource.GPS))

        assertEquals(2, api.calls.size)
        assertEquals(listOf(gps(0)), preferences.gpsLog())
    }
}
