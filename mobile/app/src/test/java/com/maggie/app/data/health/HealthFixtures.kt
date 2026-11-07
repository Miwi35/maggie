package com.maggie.app.data.health

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.maggie.app.data.api.MaggieApiService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Health Connect as a test sets it up: what it holds, what it grants, and what was written to it. */
class FakeHealthConnectGateway(
    var available: HealthConnectAvailability = HealthConnectAvailability.AVAILABLE,
    var granted: Set<String> = HealthConnectPermissions.ALL,
) : HealthConnectGateway {
    val sessions = mutableListOf<HcExerciseSession>()
    val weights = mutableListOf<HcWeight>()
    val bodyFat = mutableListOf<HcBodyFat>()
    val days = mutableListOf<HcDayActivity>()
    val written = mutableListOf<HcSessionToWrite>()
    val requested = mutableListOf<Set<String>>()

    /** The windows the reads were asked for. */
    val reads = mutableListOf<Pair<Instant, Instant>>()
    val aggregations = mutableListOf<Pair<LocalDate, LocalDate>>()

    /** Set to make every read throw, as Health Connect does when the background read is refused. */
    var readError: Exception? = null

    override fun availability() = available

    override suspend fun grantedPermissions() = granted

    override fun requestPermissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        object : ActivityResultContract<Set<String>, Set<String>>() {
            override fun createIntent(context: Context, input: Set<String>): Intent {
                requested += input
                return Intent("test.REQUEST_HEALTH_PERMISSIONS")
            }

            override fun parseResult(resultCode: Int, intent: Intent?): Set<String> = emptySet()
        }

    override suspend fun readExerciseSessions(since: Instant, until: Instant): List<HcExerciseSession> {
        readError?.let { throw it }
        reads += since to until
        return sessions.filter { !it.start.isBefore(since) && it.start.isBefore(until) }
    }

    override suspend fun readWeights(since: Instant, until: Instant): List<HcWeight> {
        readError?.let { throw it }
        return weights.filter { !it.time.isBefore(since) && it.time.isBefore(until) }
    }

    override suspend fun readBodyFat(since: Instant, until: Instant): List<HcBodyFat> {
        readError?.let { throw it }
        return bodyFat.filter { !it.time.isBefore(since) && it.time.isBefore(until) }
    }

    override suspend fun aggregateDays(from: LocalDate, to: LocalDate, zone: ZoneId): List<HcDayActivity> {
        readError?.let { throw it }
        aggregations += from to to
        return days.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
    }

    override suspend fun insertSession(session: HcSessionToWrite): String {
        written += session
        // Health Connect would hold it now, carrying the Workout id as its client id.
        sessions += HcExerciseSession(
            id = "hc-written-${written.size}",
            clientRecordId = session.clientRecordId,
            exerciseType = session.exerciseType,
            title = session.title,
            start = session.start,
            startOffset = session.startOffset,
            end = session.end,
        )
        return "hc-written-${written.size}"
    }
}

/** One request the API got. */
data class ApiCall(val method: String, val path: String, val contentType: String?, val body: JsonObject)

/**
 * The API behind a Ktor mock: `POST /api/health/sync` answers [syncStatus], `GET /api/workouts` the [workouts] JSON
 * members, `PATCH /api/workouts/{id}` 200; [failWith] makes every request fail on the network.
 */
class HealthApi {
    val calls = mutableListOf<ApiCall>()
    var syncStatus: HttpStatusCode = HttpStatusCode.OK
    var workouts: List<String> = emptyList()
    var failWith: IOException? = null

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val client = HttpClient(
        MockEngine { request ->
            failWith?.let { throw it }
            calls += ApiCall(request.method.value, request.url.encodedPath, request.body.contentType?.toString(), request.bodyJson())
            when {
                request.url.encodedPath == "/api/health/sync" -> respond(
                    content = if (syncStatus == HttpStatusCode.OK) SYNC_RESPONSE else """{"error":"refused"}""",
                    status = syncStatus,
                    headers = json,
                )
                request.method.value == "GET" && request.url.encodedPath == "/api/workouts" -> respond(
                    content = """{"member":[${workouts.joinToString(",")}]}""",
                    headers = json,
                )
                request.method.value == "PATCH" -> respond(
                    content = workouts.firstOrNull { request.url.encodedPath.endsWith(idOf(it)) } ?: "{}",
                    headers = json,
                )
                else -> respond(content = "", status = HttpStatusCode.NotFound)
            }
        },
    ) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
    }

    val service = MaggieApiService(client)

    fun syncBodies(): List<JsonObject> = calls.filter { it.path == "/api/health/sync" }.map { it.body }

    private fun idOf(workout: String) = Json.parseToJsonElement(workout).jsonObject["id"].toString().trim('"')

    private fun HttpRequestData.bodyJson(): JsonObject {
        val text = (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: return JsonObject(emptyMap())
        return Json.parseToJsonElement(text).jsonObject
    }

    companion object {
        const val SYNC_RESPONSE =
            """{"workouts":{"created":1,"updated":0,"unchanged":0},"measurements":{"created":2,"updated":0,"unchanged":0},"days":{"created":1,"updated":1,"unchanged":0}}"""
    }
}

/** A real DataStore in [dir]. */
fun healthPreferencesIn(dir: File): HealthSyncPreferences =
    HealthSyncPreferences(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) {
            File(dir, "health_sync.preferences_pb")
        },
    )

/** A recording [HealthSyncScheduler]. */
class FakeHealthSyncScheduler : HealthSyncScheduler {
    var periodic = 0
    var now = 0
    var stopped = 0
    override fun startPeriodic() { periodic++ }
    override fun syncNow() { now++ }
    override fun stop() { stopped++ }
}
