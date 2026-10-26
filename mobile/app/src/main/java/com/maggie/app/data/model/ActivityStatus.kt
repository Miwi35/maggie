package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** What an activity was launched from: `kind` is the activity's own (`meal`, `recipe`), `id` the thing's. */
@Serializable
data class ActivityOrigin(val kind: String, val id: String)

/**
 * An activity left in progress (MAG-613, `GET /api/activity-status/{activity}`), so another device picks it up
 * where it stopped. [state] is the activity's own JSON: Cuisine's is read by `CookingState`.
 */
@Serializable
data class ActivityStatus(
    val activity: String,
    val origin: ActivityOrigin,
    val state: JsonObject,
    val startedAt: String,
    val updatedAt: String,
)

/** `PUT /api/activity-status/{activity}`: creates the status or replaces it. */
@Serializable
data class ActivityStatusRequest(val origin: ActivityOrigin, val state: JsonObject)
