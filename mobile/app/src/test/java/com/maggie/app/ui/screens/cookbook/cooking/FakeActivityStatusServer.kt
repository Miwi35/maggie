package com.maggie.app.ui.screens.cookbook.cooking

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `/api/activity-status/cuisine` as the API answers it (MAG-613): one status per user, `PUT` replaces it, `DELETE`
 * ends it, `GET` is 404 when nothing is in progress. Keeps what went on the wire, in order.
 */
class FakeActivityStatusServer {

    /** The origin and state as last written, or null. */
    var origin: Pair<String, String>? = null
        private set
    var state: JsonObject? = null
        private set

    /** `put` or `delete`, in the order they came. */
    val writes = mutableListOf<String>()
    val puts = mutableListOf<JsonObject>()
    var gets = 0
        private set

    /** Answers every request with a 500. */
    var failing = false

    fun seed(kind: String, id: String, state: String) {
        origin = kind to id
        this.state = Json.parseToJsonElement(state).jsonObject
    }

    /** The state last written, as the typed session. */
    fun session(): CookingSession? = status()?.toCookingSession()

    private fun status() = origin?.let { (kind, id) ->
        com.maggie.app.data.model.ActivityStatus(
            activity = "cuisine",
            origin = com.maggie.app.data.model.ActivityOrigin(kind, id),
            state = state ?: JsonObject(emptyMap()),
            startedAt = "2026-10-26T10:00:00+00:00",
            updatedAt = "2026-10-26T10:00:00+00:00",
        )
    }

    /** (status code, body) for a request; body empty for a 204 and a 404. */
    fun handle(method: HttpMethod, body: String?): Pair<Int, String> {
        if (failing) return 500 to ""
        return when (method) {
            HttpMethod.Get -> {
                gets++
                val kind = origin?.first ?: return 404 to ""
                200 to buildJsonObject {
                    put("activity", "cuisine")
                    put("origin", buildJsonObject { put("kind", kind); put("id", origin!!.second) })
                    put("state", state ?: JsonObject(emptyMap()))
                    put("startedAt", "2026-10-26T10:00:00+00:00")
                    put("updatedAt", "2026-10-26T10:00:00+00:00")
                }.toString()
            }
            HttpMethod.Put -> {
                writes += "put"
                val json = Json.parseToJsonElement(body.orEmpty()).jsonObject
                val sent = json.getValue("origin").jsonObject
                origin = sent.getValue("kind").jsonPrimitive.content to sent.getValue("id").jsonPrimitive.content
                state = json.getValue("state").jsonObject
                puts += state!!
                200 to "{}"
            }
            HttpMethod.Delete -> {
                writes += "delete"
                origin = null
                state = null
                204 to ""
            }
            else -> 405 to ""
        }
    }
}
