package com.maggie.app.data.mercure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the pushes of a collection announced: the id of each resource touched, and whether it is gone. */
object PushedChanges {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The last word on each id wins; a payload that names no resource is ignored. */
    fun of(events: List<MercureEvent>): Map<String, Boolean> {
        val changes = linkedMapOf<String, Boolean>()
        for (event in events) {
            val payload = runCatching { json.parseToJsonElement(event.data).jsonObject }.getOrNull() ?: continue
            val id = payload.idOrNull() ?: continue
            changes.remove(id)
            changes[id] = payload["deleted"]?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() } == true
        }
        return changes
    }

    private fun JsonObject.idOrNull(): String? =
        this["@id"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
}
