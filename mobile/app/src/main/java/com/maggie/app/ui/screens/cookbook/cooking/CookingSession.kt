package com.maggie.app.ui.screens.cookbook.cooking

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the activity cooks: a meal (`?meal=`) or one recipe (`?recipe=`). */
data class CookingOrigin(val kind: Kind, val id: String) {
    /** [wire] is how the activity status spells it (`origin.kind`). */
    enum class Kind(val wire: String) {
        MEAL("meal"),
        RECIPE("recipe"),
        ;

        companion object {
            fun of(wire: String): Kind? = entries.firstOrNull { it.wire == wire }
        }
    }
}

@Serializable
data class QueuedRecipe(
    val recipeId: String,
    /** The number of people this recipe is cooked for: its quantities follow it. */
    val servings: Int,
)

@Serializable
enum class CookingPhase {
    /** « Pour combien ? », asked when the activity is launched from a recipe. */
    @SerialName("servings") SERVINGS,

    @SerialName("cooking") COOKING,

    /** « Bon appétit »: reached, never written — the status is deleted instead. */
    @SerialName("done") DONE,
}

/**
 * Where the activity stands. Every change is written to the activity status (MAG-615), so leaving, a rotation or
 * another device resumes where the cook was; the steps' details (MAG-581) and the shopping list (MAG-583) read
 * `servings` from it.
 */
data class CookingSession(
    val origin: CookingOrigin,
    /** The recipes in the order they are cooked: a meal's, or the one recipe. */
    val queue: List<QueuedRecipe>,
    val recipeIndex: Int = 0,
    /** 0 is the preparation, the steps follow from 1. */
    val step: Int = 0,
    val guests: Int,
    val phase: CookingPhase,
    /**
     * The steps ticked off, by recipe id (positions as in `step`, 1-based, sorted): « Suivant » ticks the step
     * it leaves. The admin keeps the same shape, so a resumed activity reads either.
     */
    val doneSteps: Map<String, List<Int>> = emptyMap(),
    /** Epoch millis: when the session began, and when « Bon appétit » was reached (0 until then, never written). */
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
) {
    val current: QueuedRecipe? get() = queue.getOrNull(recipeIndex)

    val isLastRecipe: Boolean get() = recipeIndex == queue.lastIndex

    fun isDone(recipeId: String, position: Int): Boolean = position in doneSteps[recipeId].orEmpty()

    /** Steps ticked off across the queue; [stepCounts] (by recipe id) leaves out positions a recipe no longer has. */
    fun doneCount(stepCounts: Map<String, Int>): Int =
        queue.sumOf { entry -> doneSteps[entry.recipeId].orEmpty().count { it in 1..(stepCounts[entry.recipeId] ?: 0) } }
}

const val MIN_GUESTS = 1
const val MAX_GUESTS = 999

private fun clampGuests(guests: Int) = guests.coerceIn(MIN_GUESTS, MAX_GUESTS)

/** A meal: its recipes in order, all for the meal's guests, straight to the preparation. */
fun mealSession(mealId: String, recipeIds: List<String>, guests: Int, now: Long = System.currentTimeMillis()): CookingSession {
    val count = clampGuests(guests)
    return CookingSession(
        origin = CookingOrigin(CookingOrigin.Kind.MEAL, mealId),
        queue = recipeIds.map { QueuedRecipe(it, count) },
        guests = count,
        phase = CookingPhase.COOKING,
        startedAt = now,
    )
}

/** A recipe alone: « Pour combien ? » first, filled with the recipe's own servings. */
fun recipeSession(recipeId: String, servings: Int, now: Long = System.currentTimeMillis()): CookingSession {
    val count = clampGuests(servings)
    return CookingSession(
        origin = CookingOrigin(CookingOrigin.Kind.RECIPE, recipeId),
        queue = listOf(QueuedRecipe(recipeId, count)),
        guests = count,
        phase = CookingPhase.SERVINGS,
        startedAt = now,
    )
}

/** Another number of people: every recipe of the queue follows it (the API keeps one count per meal). */
fun CookingSession.withGuests(guests: Int): CookingSession {
    val count = clampGuests(guests)
    return copy(guests = count, queue = queue.map { it.copy(servings = count) })
}

/** « Continuer » on « Pour combien ? »: the preparation of the recipe. */
fun CookingSession.confirmServings(): CookingSession = copy(phase = CookingPhase.COOKING, step = 0)

/** The next recipe's preparation, or « Bon appétit » after the last one. */
private fun CookingSession.nextRecipe(now: Long): CookingSession =
    if (recipeIndex + 1 < queue.size) {
        copy(recipeIndex = recipeIndex + 1, step = 0)
    } else {
        copy(phase = CookingPhase.DONE, finishedAt = now)
    }

/** The step on screen is ticked off; the preparation has nothing to tick. */
fun CookingSession.completeStep(): CookingSession {
    val recipeId = current?.recipeId
    if (phase != CookingPhase.COOKING || step < 1 || recipeId == null || isDone(recipeId, step)) return this
    return copy(doneSteps = doneSteps + (recipeId to (doneSteps[recipeId].orEmpty() + step).sorted()))
}

/**
 * « Suivant »: ticks the step it leaves, then the next step, then the next recipe. [stepCount] is the current
 * recipe's. « Lancer et continuer » goes on without ticking ([validate] false).
 */
fun CookingSession.advance(stepCount: Int, validate: Boolean = true, now: Long = System.currentTimeMillis()): CookingSession {
    if (phase != CookingPhase.COOKING) return this
    val from = if (validate) completeStep() else this
    return if (step < stepCount) from.copy(step = step + 1) else from.nextRecipe(now)
}

/** A step touched in the list, of this recipe or another of the queue: shown, never ticked. */
fun CookingSession.goToStep(recipeIndex: Int, position: Int, stepCount: Int): CookingSession =
    if (phase == CookingPhase.COOKING && recipeIndex in queue.indices && position in 1..stepCount) {
        copy(recipeIndex = recipeIndex, step = position)
    } else {
        this
    }

/** « Passer »: leaves a recipe that has no step to cook. */
fun CookingSession.skipRecipe(now: Long = System.currentTimeMillis()): CookingSession =
    if (phase == CookingPhase.COOKING) nextRecipe(now) else this

/** Whole minutes spent cooking, at least one. */
val CookingSession.minutesCooked: Int
    get() = ((finishedAt - startedAt) / 60_000L).toInt().coerceAtLeast(1)

/** « Précédent »: the step before, down to the preparation. */
fun CookingSession.back(): CookingSession =
    if (phase == CookingPhase.COOKING && step > 0) copy(step = step - 1) else this

/** « Recommencer »: the preparation of the first recipe again, for the same guests, nothing ticked. */
fun CookingSession.restarted(now: Long = System.currentTimeMillis()): CookingSession = copy(
    recipeIndex = 0,
    step = 0,
    phase = CookingPhase.COOKING,
    doneSteps = emptyMap(),
    startedAt = now,
    finishedAt = 0L,
)
