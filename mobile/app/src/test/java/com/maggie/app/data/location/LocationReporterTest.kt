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
    private lateinit var reporter: LocationReporter

    @Before
    fun setUp() {
        preferences = preferencesIn(folder.root)
        reporter = LocationReporter(api.service, preferences, permissions, source)
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
    fun `no fix in time sends nothing and the work is told to try again`() = runTest {
        switchOn()
        source.position = null

        assertEquals(ReportOutcome.RETRY, reporter.reportPosition())

        assertTrue(api.calls.isEmpty())
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
        api.status = HttpStatusCode.BadGateway
        assertEquals(ReportOutcome.RETRY, reporter.reportCar(Instant.parse("2026-10-26T21:29:40Z")))
        assertEquals(PendingCar(48.8566, 2.3522, Instant.parse("2026-10-26T21:29:40Z")), preferences.pendingCar())

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
}
