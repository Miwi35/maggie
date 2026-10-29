package com.maggie.app.data.location

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.maggie.app.data.api.MaggieApiService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.Instant

/** One request the API got: its method and path, and the JSON body. */
data class Call(val method: String, val path: String, val body: JsonObject)

/** A Ktor client whose answers are [status] (changeable), recording every call. */
class RecordingApi(var status: HttpStatusCode = HttpStatusCode.NoContent) {
    val calls = mutableListOf<Call>()

    private val client = HttpClient(
        MockEngine { request ->
            calls += Call(request.method.value, request.url.encodedPath, request.bodyJson())
            respond(content = "", status = status)
        },
    ) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
    }

    val service = MaggieApiService(client)

    private fun HttpRequestData.bodyJson(): JsonObject {
        val text = (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: return JsonObject(emptyMap())
        return Json.parseToJsonElement(text).jsonObject
    }
}

class FakePermissions(
    var fine: Boolean = true,
    var background: Boolean = true,
    var activity: Boolean = true,
) : LocationPermissions {
    override fun hasFineLocation() = fine
    override fun hasBackgroundLocation() = background
    override fun hasActivityRecognition() = activity
}

class FakePositionSource(var position: Position? = PARIS) : PositionSource {
    var asked = 0

    override suspend fun current(): Position? {
        asked++
        return position
    }

    companion object {
        val PARIS = Position(48.8566, 2.3522, 12.0, Instant.parse("2026-10-26T21:30:00Z"))
    }
}

class FakeScheduler : LocationScheduler {
    var started = 0
    var stopped = 0
    var positionRequests = 0
    val handedTransitions = mutableListOf<List<TransitionEvent>>()
    val handedPassive = mutableListOf<List<Position>>()
    var carRetries = 0
    override fun startTracking() { started++ }
    override fun stopTracking() { stopped++ }
    override fun requestPositionNow() { positionRequests++ }
    override fun handleTransitions(events: List<TransitionEvent>) { handedTransitions.add(events) }
    override fun handlePassivePoints(points: List<Position>) { handedPassive.add(points) }
    override fun retryCarLater() { carRetries++ }
}

class FakeLauncher : TripLauncher {
    var starts = 0
    override suspend fun startIfNeeded() { starts++ }
}

class FakePassive : PassiveRegistrar {
    var registered = false
    var registrations = 0
    override fun register() { registered = true; registrations++ }
    override fun unregister() { registered = false }
}

/** A clock that only moves when told to. */
class FakeClock(var now: Instant) : java.time.Clock() {
    override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): java.time.Clock = this
    override fun instant(): Instant = now
}

/** A real DataStore in [dir], so what the receiver keeps between two events is really kept. */
fun preferencesIn(dir: File, name: String = "departure.preferences_pb"): LocationPreferences =
    LocationPreferences(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) { File(dir, name) },
    )
