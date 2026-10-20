package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The reward a task ticked earned (MAG-508), `GET /api/rewards/task/{taskId}`: a star and a
 * message drawn from written banks in the user's tone. Created by the tick itself, so it is there
 * once the tick has answered; a tick that earned none reads 404. Plain JSON, not API Platform.
 *
 * - `level`: `standard`, `surprise` or `palier`;
 * - `surprise`: why it is an « étoile filante » (`fullDay`, `monthRecord`, `waited`), null otherwise.
 */
@Serializable
data class Reward(
    val id: String,
    val subjectType: String,
    val subjectId: String,
    val level: String,
    val surprise: String? = null,
    val messageKey: String,
    val message: String,
    /** What a palier celebrates, the chapter's title; null for a task. */
    val title: String? = null,
    val star: Boolean,
    val earnedAt: String,
) {
    /** The rare one: its own animation, a star crossing the screen. */
    val isShootingStar: Boolean get() = surprise != null

    /** A big moment (MAG-509), a chapter finished: gold, no star in the month. */
    val isPalier: Boolean get() = level == LEVEL_PALIER

    companion object {
        const val SUBJECT_TASK = "task"
        const val LEVEL_PALIER = "palier"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The reward a Mercure message carries (MAG-509): the entity as the API describes it, its
         * identity spelled `@id` (`/api/rewards/{id}`) instead of `id`. `null` for anything else
         * published on the stream.
         */
        fun fromPush(data: String): Reward? = runCatching {
            val body = json.parseToJsonElement(data).jsonObject
            val iri = body["@id"]?.jsonPrimitive?.contentOrNull ?: return null
            if (!iri.startsWith("/api/rewards/")) return null
            json.decodeFromJsonElement<Reward>(JsonObject(body + ("id" to JsonPrimitive(iri.substringAfterLast('/')))))
        }.getOrNull()
    }
}
