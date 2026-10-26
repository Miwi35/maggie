package com.maggie.app.ui.screens.cookbook.cooking

import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import com.maggie.app.data.model.CookingTimer
import com.maggie.app.ui.navigation.DeepLinks
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The activity status' name for Cuisine (`GET /api/activity-status/cuisine`). */
const val COOKING_ACTIVITY = "cuisine"

private val stateJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Cuisine's `state` in the activity status (MAG-613), the same JSON the admin writes: `step` 0 is the preparation,
 * the steps follow from 1. `done` is never written — reaching « Repas servi » deletes the status instead.
 */
@Serializable
internal data class CookingState(
    val queue: List<QueuedRecipe>,
    val recipeIndex: Int = 0,
    val step: Int = 0,
    val doneSteps: Map<String, List<Int>> = emptyMap(),
    val guests: Int,
    val phase: CookingPhase,
    val startedAt: Long = 0L,
)

fun CookingSession.statusOrigin(): ActivityOrigin = ActivityOrigin(origin.kind.wire, origin.id)

fun CookingSession.statusState(): JsonObject = stateJson.encodeToJsonElement(
    CookingState.serializer(),
    CookingState(queue, recipeIndex, step, doneSteps, guests, phase, startedAt),
).jsonObject

/**
 * The session a status holds, or null when there is nothing to resume: a state this version cannot read, a
 * finished one, a queue or an index that points nowhere.
 */
fun ActivityStatus.toCookingSession(): CookingSession? {
    val kind = CookingOrigin.Kind.of(origin.kind) ?: return null
    if (!DeepLinks.isValidId(origin.id)) return null
    val state = runCatching { stateJson.decodeFromJsonElement(CookingState.serializer(), state) }.getOrNull() ?: return null
    if (state.phase == CookingPhase.DONE || state.queue.isEmpty() || state.recipeIndex !in state.queue.indices || state.step < 0) return null
    if (state.queue.any { !DeepLinks.isValidId(it.recipeId) }) return null
    return CookingSession(
        origin = CookingOrigin(kind, origin.id),
        queue = state.queue.map { it.copy(servings = it.servings.coerceIn(MIN_GUESTS, MAX_GUESTS)) },
        recipeIndex = state.recipeIndex,
        step = state.step,
        guests = state.guests.coerceIn(MIN_GUESTS, MAX_GUESTS),
        phase = state.phase,
        doneSteps = state.doneSteps,
        startedAt = state.startedAt,
    )
}

/** A Cuisine in progress as the launch points read it: its session and the timers still counting. */
data class CookingResume(val session: CookingSession, val timers: List<CookingTimer> = emptyList())

/** « ▶ Reprendre » on a meal's pill: the status was started from this very meal. */
fun CookingResume?.resumesMeal(mealId: String): Boolean =
    this?.session?.origin == CookingOrigin(CookingOrigin.Kind.MEAL, mealId)

/** « ▶ Reprendre » on a recipe's sheet: the recipe is in the queue being cooked. */
fun CookingResume?.resumesRecipe(recipeId: String): Boolean =
    this?.session?.queue?.any { it.recipeId == recipeId } == true

/**
 * « étape 7/11 · mijotage 1 h 47 »: where the cook stands in [recipeId] of [stepCount] steps, then the label and rest
 * of the recipe's timer that rings first, if one runs. A recipe the cook is not on yet reads « à venir », one it has
 * left « terminée ».
 */
fun CookingResume.detailFor(recipeId: String, stepCount: Int): String? {
    val index = session.queue.indexOfFirst { it.recipeId == recipeId }
    if (index < 0) return null
    val place = when {
        index < session.recipeIndex -> "terminée"
        index > session.recipeIndex -> "à venir"
        session.step == 0 -> "préparation"
        else -> "étape ${session.step}/$stepCount"
    }
    val timer = timers.filter { it.recipeId == recipeId && it.status.isCounting }.minByOrNull { it.remainingSeconds }
        ?: return place
    return "$place · ${timer.label.replaceFirstChar { it.lowercase() }} ${restLabel(timer.remainingSeconds)}"
}

/** A timer's rest in a sentence: « 1 h 47 », « 12 min », « 40 s ». */
fun restLabel(seconds: Int): String {
    val left = seconds.coerceAtLeast(0)
    return when {
        left >= 3600 -> "%d h %02d".format(left / 3600, left % 3600 / 60)
        left >= 60 -> "${(left + 59) / 60} min"
        else -> "$left s"
    }
}

/** What stands in the way of a launch: a Cuisine already in progress on another origin, with the recipe it is on. */
data class CookingConflict(
    val session: CookingSession,
    /** What this launch wanted to cook: « Recommencer avec cette recette » or « ce repas ». */
    val launchKind: CookingOrigin.Kind,
    /** The recipe the cook is on, when it could be read. */
    val recipeName: String? = null,
    val stepCount: Int? = null,
) {
    val replaceLabel: String
        get() = if (launchKind == CookingOrigin.Kind.MEAL) "Recommencer avec ce repas" else "Recommencer avec cette recette"

    /** « Pâtes aux légumes · étape 3/5 ». */
    val detail: String
        get() {
            val place = when {
                session.step == 0 -> "préparation"
                stepCount != null -> "étape ${session.step}/$stepCount"
                else -> "étape ${session.step}"
            }
            return listOfNotNull(recipeName, place).joinToString(" · ")
        }
}
