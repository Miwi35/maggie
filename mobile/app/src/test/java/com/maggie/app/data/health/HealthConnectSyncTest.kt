package com.maggie.app.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.HealthSyncResponse
import com.maggie.app.data.repository.HealthSyncRepository
import com.maggie.app.data.repository.WorkoutRepository
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** The Health Connect pass (MAG-708), on a fake Health Connect and the real API client over a Ktor mock. */
class HealthConnectSyncTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val paris = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-10-21T10:00:00Z")
    private val clock = Clock.fixed(now, paris)
    private val gateway = FakeHealthConnectGateway()
    private val api = HealthApi()
    private lateinit var preferences: HealthSyncPreferences
    private lateinit var sync: HealthConnectSync

    @Before
    fun setUp() {
        preferences = healthPreferencesIn(folder.root)
        sync = HealthConnectSync(gateway, HealthSyncRepository(api.service), WorkoutRepository(api.service), preferences, clock)
    }

    private fun session(
        id: String,
        type: Int = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        start: String = "2026-10-20T05:10:00Z",
        minutes: Long = 32,
        clientRecordId: String? = null,
        title: String? = "Course",
    ) = HcExerciseSession(
        id = id,
        clientRecordId = clientRecordId,
        exerciseType = type,
        title = title,
        start = Instant.parse(start),
        startOffset = ZoneOffset.ofHours(2),
        end = Instant.parse(start).plus(Duration.ofMinutes(minutes)),
    )

    private fun workoutJson(id: String, source: String = "manual", externalId: String? = null, startedAt: String = "2026-10-19T18:30:00+02:00") =
        """{"id":"$id","type":"strength","title":"Renfo","startedAt":"$startedAt","durationMinutes":45,"source":"$source"""" +
            (externalId?.let { ""","externalId":"$it"}""" } ?: "}")

    private fun JsonObject.list(key: String): JsonArray = getValue(key).jsonArray

    // — Type mapping

    @Test
    fun `every Health Connect exercise type the ticket lists maps to its WorkoutType, the rest to other`() {
        val expected = mapOf(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "run",
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "run",
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "walk",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "bike",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "bike",
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "swim",
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "swim",
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "strength",
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "strength",
            ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING to "climbing",
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "other",
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "other",
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "other",
        )
        expected.forEach { (type, workout) -> assertEquals("exercise type $type", workout, workoutTypeOf(type)) }
    }

    @Test
    fun `every WorkoutType is written back as its Health Connect exercise type`() {
        val expected = mapOf(
            "run" to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            "walk" to ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            "bike" to ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            "swim" to ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            "strength" to ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            "climbing" to ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING,
            "other" to ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
        )
        expected.forEach { (workout, type) -> assertEquals(workout, type, exerciseTypeOf(workout)) }
    }

    // — The batch

    @Test
    fun `a watch session leaves as a workout with its offset, whole minutes and the watch as source`() = runTest {
        gateway.sessions += session("hc-run-1", minutes = 32)

        assertEquals(SyncOutcome.SENT, sync.run())

        val workout = api.syncBodies().single().list("workouts").single().jsonObject
        assertEquals(JsonPrimitive("hc-run-1"), workout["externalId"])
        assertEquals(JsonPrimitive("run"), workout["type"])
        assertEquals(JsonPrimitive("Course"), workout["title"])
        assertEquals(JsonPrimitive("2026-10-20T07:10:00+02:00"), workout["startedAt"])
        assertEquals(32, workout.getValue("durationMinutes").jsonPrimitive.content.toInt())
        assertEquals(JsonPrimitive("watch"), workout["source"])
        assertFalse("no figure the permissions cannot give", "distanceMeters" in workout)
    }

    @Test
    fun `a session without a title leaves without one, and one over a day is not sent`() = runTest {
        gateway.sessions += session("hc-untitled", title = "  ")
        gateway.sessions += session("hc-too-long", start = "2026-10-19T05:00:00Z", minutes = 1441)

        sync.run()

        val workouts = api.syncBodies().single().list("workouts")
        assertEquals(1, workouts.size)
        assertFalse("title" in workouts.single().jsonObject)
    }

    @Test
    fun `a weighing and a body fat reading leave as two measurements of Health Connect`() = runTest {
        val at = Instant.parse("2026-10-21T05:12:00Z")
        gateway.weights += HcWeight("hc-w-1", at, ZoneOffset.ofHours(2), 72.4)
        gateway.bodyFat += HcBodyFat("hc-f-1", at, null, 21.3)

        sync.run()

        val measurements = api.syncBodies().single().list("measurements").map { it.jsonObject }
        assertEquals(2, measurements.size)
        val weight = measurements.single { it["externalId"] == JsonPrimitive("hc-w-1") }
        assertEquals(JsonPrimitive("2026-10-21T07:12:00+02:00"), weight["measuredAt"])
        assertEquals(72.4, weight.getValue("weightKg").jsonPrimitive.content.toDouble(), 0.0)
        assertFalse("bodyFatPercent" in weight)
        assertEquals(JsonPrimitive("health_connect"), weight["source"])
        val fat = measurements.single { it["externalId"] == JsonPrimitive("hc-f-1") }
        // No offset in the record: the phone's zone gives it.
        assertEquals(JsonPrimitive("2026-10-21T07:12:00+02:00"), fat["measuredAt"])
        assertEquals(21.3, fat.getValue("bodyFatPercent").jsonPrimitive.content.toDouble(), 0.0)
        assertFalse("weightKg" in fat)
    }

    @Test
    fun `the days sent are Health Connect's per-day aggregate, local dates of the phone's zone`() = runTest {
        preferences.advanceTo(Instant.parse("2026-10-21T08:00:00Z"))
        gateway.days += HcDayActivity(LocalDate.parse("2026-10-20"), 11_204, 538.6)
        gateway.days += HcDayActivity(LocalDate.parse("2026-10-21"), 8_432, 412.2)

        sync.run()

        // The mark minus a day of overlap, 20 Oct 10:00 Paris, through today.
        assertEquals(LocalDate.parse("2026-10-20") to LocalDate.parse("2026-10-21"), gateway.aggregations.single())
        val days = api.syncBodies().single().list("days").map { it.jsonObject }
        assertEquals(
            listOf(
                Triple("2026-10-20", 11_204, 539),
                Triple("2026-10-21", 8_432, 412),
            ),
            days.map {
                Triple(
                    it.getValue("date").jsonPrimitive.content,
                    it.getValue("steps").jsonPrimitive.content.toInt(),
                    it.getValue("activeKcal").jsonPrimitive.content.toInt(),
                )
            },
        )
    }

    @Test
    fun `the batch goes as JSON to POST api health sync`() = runTest {
        sync.run()

        val call = api.calls.first()
        assertEquals("POST", call.method)
        assertEquals("/api/health/sync", call.path)
        assertTrue(call.contentType.orEmpty().startsWith("application/json"))
        assertEquals(setOf("workouts", "measurements", "days"), call.body.keys)
    }

    // — Maggie's own workouts

    @Test
    fun `a session Maggie wrote is not imported again, and the write-back sends its record id to the workout`() = runTest {
        val workoutId = "01JB8Y4Z9Q6V3N2M1K0H7G5F4D"
        val startedAt = "2026-10-21T08:30:00+02:00"
        api.workouts = listOf(workoutJson(workoutId, startedAt = startedAt))

        sync.run()

        val written = gateway.written.single()
        assertEquals(workoutId, written.clientRecordId)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, written.exerciseType)
        assertEquals("Renfo", written.title)
        assertEquals(Instant.parse("2026-10-21T06:30:00Z"), written.start)
        assertEquals(Instant.parse("2026-10-21T07:15:00Z"), written.end)
        assertEquals(ZoneOffset.ofHours(2), written.startOffset)
        val patch = api.calls.single { it.method == "PATCH" }
        assertEquals("/api/workouts/$workoutId", patch.path)
        assertEquals("application/merge-patch+json", patch.contentType)
        assertEquals(JsonObject(mapOf("externalId" to JsonPrimitive("hc-written-1"))), patch.body)

        // The next pass reads Health Connect again: the session carries the Workout id, it stays out.
        api.workouts = listOf(workoutJson(workoutId, externalId = "hc-written-1", startedAt = startedAt))
        sync.run()

        assertTrue("the second read covers the session", gateway.sessions.single().start.isAfter(gateway.reads.last().first))
        assertEquals(1, gateway.written.size)
        assertEquals(0, api.syncBodies().last().list("workouts").size)
    }

    @Test
    fun `only manual, activity and estimated workouts without an externalId are written to Health Connect`() = runTest {
        api.workouts = listOf(
            workoutJson("01JB8Y4Z9Q6V3N2M1K0H7G5F41", source = "manual"),
            workoutJson("01JB8Y4Z9Q6V3N2M1K0H7G5F42", source = "activity"),
            workoutJson("01JB8Y4Z9Q6V3N2M1K0H7G5F43", source = "estimated"),
            workoutJson("01JB8Y4Z9Q6V3N2M1K0H7G5F44", source = "watch", externalId = "hc-from-watch"),
            workoutJson("01JB8Y4Z9Q6V3N2M1K0H7G5F45", source = "planned"),
            workoutJson("01JB8Y4Z9Q6V3N2M1K0H7G5F46", source = "manual", externalId = "hc-already"),
        )

        sync.run()

        assertEquals(
            listOf("01JB8Y4Z9Q6V3N2M1K0H7G5F41", "01JB8Y4Z9Q6V3N2M1K0H7G5F42", "01JB8Y4Z9Q6V3N2M1K0H7G5F43"),
            gateway.written.map { it.clientRecordId },
        )
    }

    @Test
    fun `without the write permission nothing is written back, and the batch still leaves`() = runTest {
        gateway.granted = HealthConnectPermissions.READ + HealthConnectPermissions.READ_IN_BACKGROUND
        api.workouts = listOf(workoutJson("01JB8Y4Z9Q6V3N2M1K0H7G5F4D"))

        assertEquals(SyncOutcome.SENT, sync.run())

        assertTrue(gateway.written.isEmpty())
        assertTrue(api.calls.none { it.method == "PATCH" })
        assertEquals(1, api.syncBodies().size)
    }

    @Test
    fun `another app's client id is not a Workout id: its session is imported`() = runTest {
        gateway.sessions += session("hc-gadget", clientRecordId = "gadgetbridge-42")

        sync.run()

        assertEquals(1, api.syncBodies().single().list("workouts").size)
    }

    // — Nothing sent

    @Test
    fun `permissions refused send nothing`() = runTest {
        gateway.granted = setOf(HealthConnectPermissions.READ_STEPS)
        gateway.sessions += session("hc-run-1")

        assertEquals(SyncOutcome.NOT_PERMITTED, sync.run())

        assertTrue(api.calls.isEmpty())
        assertNull(preferences.lastSync())
    }

    @Test
    fun `a read Health Connect refuses (no background permission) sends nothing`() = runTest {
        gateway.readError = SecurityException("background read not granted")

        assertEquals(SyncOutcome.NOT_PERMITTED, sync.run())

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `Health Connect missing sends nothing`() = runTest {
        gateway.available = HealthConnectAvailability.NOT_INSTALLED

        assertEquals(SyncOutcome.UNAVAILABLE, sync.run())

        assertTrue(api.calls.isEmpty())
    }

    // — The mark

    @Test
    fun `the first pass reads 30 days back`() = runTest {
        sync.run()

        assertEquals(now.minus(Duration.ofDays(30)) to now, gateway.reads.single())
    }

    @Test
    fun `the mark moves to the pass's instant once the API took the batch, and the next pass starts a day before it`() = runTest {
        sync.run()
        assertEquals(now, preferences.lastSync())

        val later = Instant.parse("2026-10-21T11:00:00Z")
        HealthConnectSync(gateway, HealthSyncRepository(api.service), WorkoutRepository(api.service), preferences, Clock.fixed(later, paris)).run()

        assertEquals(now.minus(Duration.ofDays(1)) to later, gateway.reads.last())
        assertEquals(later, preferences.lastSync())
    }

    @Test
    fun `a network failure leaves the mark where it was and asks for another try`() = runTest {
        preferences.advanceTo(Instant.parse("2026-10-21T08:00:00Z"))
        api.failWith = IOException("no network")

        assertEquals(SyncOutcome.RETRY, sync.run())

        assertEquals(Instant.parse("2026-10-21T08:00:00Z"), preferences.lastSync())
    }

    @Test
    fun `a server error asks for another try, a refused batch does not, and neither moves the mark`() = runTest {
        api.syncStatus = HttpStatusCode.ServiceUnavailable
        assertEquals(SyncOutcome.RETRY, sync.run())

        api.syncStatus = HttpStatusCode.BadRequest
        assertEquals(SyncOutcome.REFUSED, sync.run())

        assertNull(preferences.lastSync())
    }

    @Test
    fun `the mark never goes back`() = runTest {
        preferences.advanceTo(now)
        preferences.advanceTo(now.minusSeconds(60))

        assertEquals(now, preferences.lastSync())
    }

    @Test
    fun `the read window is 30 days at most, however old the mark`() {
        assertEquals(now.minus(Duration.ofDays(30)), HealthConnectSync.readWindowStart(null, now))
        assertEquals(now.minus(Duration.ofDays(30)), HealthConnectSync.readWindowStart(now.minus(Duration.ofDays(90)), now))
        assertEquals(now.minus(Duration.ofHours(25)), HealthConnectSync.readWindowStart(now.minus(Duration.ofHours(1)), now))
    }

    // — Failures, as the worker reads them

    @Test
    fun `a network error and a 5xx ask for another try, a 4xx does not`() {
        assertEquals(SyncOutcome.RETRY, HealthConnectSync.outcomeOf(IOException("timeout")))
        assertEquals(SyncOutcome.RETRY, HealthConnectSync.outcomeOf(ApiRefusalException(502, "Bad gateway")))
        assertEquals(SyncOutcome.REFUSED, HealthConnectSync.outcomeOf(ApiRefusalException(400, "workouts[2].startedAt")))
    }

    @Test
    fun `the API's answer reads into its counts`() {
        val response = Json { ignoreUnknownKeys = true }.decodeFromString(HealthSyncResponse.serializer(), HealthApi.SYNC_RESPONSE)

        assertEquals(1, response.workouts.created)
        assertEquals(2, response.measurements.created)
        assertEquals(1, response.days.updated)
    }
}
