package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * A training session of the owner (MAG-705), read from `GET /api/workouts` (sorted by [startedAt], newest first).
 * [type], [feeling] and [source] stay the API's strings (`run`, `good`, `manual`…); their French words are in
 * `ui/screens/health/WorkoutLabels.kt`. [startedAt] is an ISO date-time with its offset. Units: whole minutes, metres,
 * kcal and bpm; a figure the session does not have is null. A `planned` session is announced, not done.
 */
@Serializable
data class Workout(
    val id: String,
    val type: String,
    val title: String,
    val startedAt: String,
    val durationMinutes: Int,
    val distanceMeters: Int? = null,
    val kcal: Int? = null,
    val avgHeartRate: Int? = null,
    val feeling: String? = null,
    val notes: String? = null,
    val source: String = "manual",
    val externalId: String? = null,
) {
    val isPlanned: Boolean get() = source == WorkoutSource.PLANNED
}

/** The keys of `WorkoutSource` that the app needs by name. */
object WorkoutSource {
    const val MANUAL = "manual"
    const val PLANNED = "planned"
}
