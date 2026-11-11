package com.maggie.app.ui.screens.health.sport

import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import com.maggie.app.data.model.Workout
import com.maggie.app.ui.navigation.DeepLinks
import com.maggie.app.ui.screens.health.WorkoutType
import com.maggie.app.ui.screens.health.workoutStart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The activity status' name for Sport (`GET /api/activity-status/sport`). */
const val SPORT_ACTIVITY = com.maggie.app.data.model.ACTIVITY_SPORT

/** What launches Sport: the planned session, by its id. */
const val SPORT_ORIGIN_KIND = "workout"

private val stateJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** A set performed, in the exercise's unit (the same shape as the API's `done`). */
@Serializable
data class SportSetDone(val reps: Int? = null, val seconds: Int? = null, val meters: Int? = null, val loadKg: Double? = null)

/** Where a set stands: to do (or the held one not started), resting after it, or a held exercise counting down. */
object SportPhase {
    const val WORK = "work"
    const val REST = "rest"
    const val HOLD = "hold"
}

/**
 * Sport's `state` in the activity status (MAG-779, moved by MAG-780): the exercise and set on screen, the [phase] and its timer —
 * [timerEndsAt] while it runs, [timerRemaining] (seconds) while a held exercise is paused. In `rest` the set on screen is the
 * one just done. `loadKg` and `done` have one entry per exercise of the session.
 */
@Serializable
data class SportState(
    val exerciseIndex: Int = 0,
    val setIndex: Int = 0,
    val phase: String = SportPhase.WORK,
    val timerEndsAt: String? = null,
    val timerRemaining: Int? = null,
    val loadKg: List<Double?> = emptyList(),
    val done: List<List<SportSetDone>> = emptyList(),
)

fun SportState.toJson(): JsonObject = stateJson.encodeToJsonElement(SportState.serializer(), this).jsonObject

/** The state a status holds, null when this version cannot read it. */
fun ActivityStatus.toSportState(): SportState? =
    runCatching { stateJson.decodeFromJsonElement(SportState.serializer(), state) }.getOrNull()

/** A planned strength session with exercises is the only one that can be launched. */
val Workout.isLaunchable: Boolean
    get() = isPlanned && type == WorkoutType.STRENGTH.key && exercises.isNotEmpty()

/** Launchable, and planned for [today] in [zone]. */
fun Workout.isLaunchableOn(today: LocalDate, zone: ZoneId): Boolean =
    isLaunchable && workoutStart(this, zone)?.toLocalDate() == today

fun sportOrigin(workoutId: String): ActivityOrigin = ActivityOrigin(SPORT_ORIGIN_KIND, workoutId)

/** The state of a session that has just been launched: the first exercise, its first set, the prescribed loads. */
fun startState(workout: Workout): JsonObject = sportStart(workout).toJson()

fun sportStart(workout: Workout): SportState = SportState(
    loadKg = workout.exercises.map { it.loadKg },
    done = workout.exercises.map { emptyList() },
)

/** A Sport session in progress, as the launch points read it. */
data class SportResume(
    val workoutId: String,
    /** 0-based. */
    val exerciseIndex: Int,
    val exerciseCount: Int,
    val startedAt: Instant?,
) {
    /** « 2/6 », on the pill. */
    val progress: String get() = "${exerciseIndex + 1}/$exerciseCount"
}

/** The session a status holds, or null when there is nothing to resume: another origin, a state this version cannot read. */
fun ActivityStatus.toSportResume(): SportResume? {
    if (origin.kind != SPORT_ORIGIN_KIND || !DeepLinks.isValidId(origin.id)) return null
    val state = runCatching { stateJson.decodeFromJsonElement(SportState.serializer(), state) }.getOrNull() ?: return null
    val count = state.done.size
    if (count == 0 || state.exerciseIndex !in 0 until count) return null
    return SportResume(origin.id, state.exerciseIndex, count, runCatching { Instant.parse(startedAt) }.getOrNull())
}

/** « ▶ Reprendre » on the card or the sheet of this very session. */
fun SportResume?.resumes(workoutId: String): Boolean = this?.workoutId == workoutId

/** « Ce matin » before noon, « Cet après-midi » before 18 h, « Ce soir » after. */
fun momentLabel(time: LocalTime): String = when {
    time.hour < 12 -> "Ce matin"
    time.hour < 18 -> "Cet après-midi"
    else -> "Ce soir"
}

/** « 6 exercices ». */
fun exerciseCountLabel(count: Int): String = if (count == 1) "1 exercice" else "$count exercices"
