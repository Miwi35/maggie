package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * A training session of the owner (MAG-705), read from `GET /api/workouts` (sorted by [startedAt], newest first).
 * [type], [feeling] and [source] stay the API's strings (`run`, `good`, `manual`…); their French words are in
 * `ui/screens/health/WorkoutLabels.kt`. [startedAt] is an ISO date-time with its offset. Units: whole minutes, metres,
 * kcal and bpm; a figure the session does not have is null. A `planned` session is announced, not done.
 * [exercises] are the prescribed exercises, in order (MAG-775); [difficulty] (1–10) and [variant] (`normal`, `calibration`,
 * `soft`, `deload`) qualify a strength session and are null otherwise.
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
    val difficulty: Int? = null,
    val variant: String? = null,
    val exercises: List<WorkoutExercise> = emptyList(),
) {
    val isPlanned: Boolean get() = source == WorkoutSource.PLANNED
}

/**
 * One prescribed exercise of a session (MAG-775): [exercise] is the catalogue slug, [name] its French name, [unit] what a set
 * counts (`reps`, `seconds`, `meters`). [sets] × [target] is the prescription, [loadKg] its load; [done] the sets actually
 * performed (empty while the session is only planned), [feeling] how it felt (`too_easy`, `right`, `too_hard`).
 */
@Serializable
data class WorkoutExercise(
    val exercise: String,
    val name: String,
    val unit: String = WorkoutUnit.REPS,
    val step: Int = 0,
    val sets: Int,
    val target: Int,
    val targetMin: Int? = null,
    val targetMax: Int? = null,
    val loadKg: Double? = null,
    val restSeconds: Int? = null,
    val tempo: Boolean = false,
    val targetRir: Int? = null,
    val cue: String? = null,
    val feeling: String? = null,
    val done: List<WorkoutSetDone> = emptyList(),
)

/** A set performed: the count in the exercise's unit, and its load when there is one. */
@Serializable
data class WorkoutSetDone(
    val reps: Int? = null,
    val seconds: Int? = null,
    val meters: Int? = null,
    val loadKg: Double? = null,
)

/** The units a [WorkoutExercise] counts in. */
object WorkoutUnit {
    const val REPS = "reps"
    const val SECONDS = "seconds"
    const val METERS = "meters"
}

/** The keys of `WorkoutSource` that the app needs by name. */
object WorkoutSource {
    const val MANUAL = "manual"
    const val PLANNED = "planned"
}
