package com.maggie.app.ui.screens.cookbook.cooking

import kotlinx.serialization.Serializable

/** What the activity cooks: a meal (`?meal=`) or one recipe (`?recipe=`). */
@Serializable
data class CookingOrigin(val kind: Kind, val id: String) {
    enum class Kind { MEAL, RECIPE }
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
    SERVINGS,
    COOKING,
    /** « Bon appétit ». */
    DONE,
}

/**
 * Where the activity stands. It lives in the `SavedStateHandle`, so a rotation or a process death resumes
 * where the cook was; the steps' details (MAG-581) and the shopping list (MAG-583) read `servings` from it.
 */
@Serializable
data class CookingSession(
    val origin: CookingOrigin,
    /** The recipes in the order they are cooked: a meal's, or the one recipe. */
    val queue: List<QueuedRecipe>,
    val recipeIndex: Int = 0,
    /** 0 is the preparation, the steps follow from 1. */
    val step: Int = 0,
    val guests: Int,
    val phase: CookingPhase,
) {
    val current: QueuedRecipe? get() = queue.getOrNull(recipeIndex)

    val isLastRecipe: Boolean get() = recipeIndex == queue.lastIndex

    /** A stored session worth resuming for [origin]: same launch, not finished, an index inside the queue. */
    fun resumes(origin: CookingOrigin): Boolean =
        this.origin == origin && phase != CookingPhase.DONE && queue.isNotEmpty() && recipeIndex in queue.indices
}

const val MIN_GUESTS = 1
const val MAX_GUESTS = 999

private fun clampGuests(guests: Int) = guests.coerceIn(MIN_GUESTS, MAX_GUESTS)

/** A meal: its recipes in order, all for the meal's guests, straight to the preparation. */
fun mealSession(mealId: String, recipeIds: List<String>, guests: Int): CookingSession {
    val count = clampGuests(guests)
    return CookingSession(
        origin = CookingOrigin(CookingOrigin.Kind.MEAL, mealId),
        queue = recipeIds.map { QueuedRecipe(it, count) },
        guests = count,
        phase = CookingPhase.COOKING,
    )
}

/** A recipe alone: « Pour combien ? » first, filled with the recipe's own servings. */
fun recipeSession(recipeId: String, servings: Int): CookingSession {
    val count = clampGuests(servings)
    return CookingSession(
        origin = CookingOrigin(CookingOrigin.Kind.RECIPE, recipeId),
        queue = listOf(QueuedRecipe(recipeId, count)),
        guests = count,
        phase = CookingPhase.SERVINGS,
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
private fun CookingSession.nextRecipe(): CookingSession =
    if (recipeIndex + 1 < queue.size) copy(recipeIndex = recipeIndex + 1, step = 0) else copy(phase = CookingPhase.DONE)

/** « Suivant »: the next step, then the next recipe. [stepCount] is the current recipe's. */
fun CookingSession.advance(stepCount: Int): CookingSession = when {
    phase != CookingPhase.COOKING -> this
    step < stepCount -> copy(step = step + 1)
    else -> nextRecipe()
}

/** « Passer »: leaves a recipe that has no step to cook. */
fun CookingSession.skipRecipe(): CookingSession = if (phase == CookingPhase.COOKING) nextRecipe() else this

/** « Précédent »: the step before, down to the preparation. */
fun CookingSession.back(): CookingSession =
    if (phase == CookingPhase.COOKING && step > 0) copy(step = step - 1) else this
