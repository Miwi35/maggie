package com.maggie.app.ui.screens.cookbook.cooking

import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.ui.screens.cookbook.recipes.ingredientId

private val ARTICLE = Regex("^(le|la|les|un|une|des|du|de|[ld]['’].+)$", RegexOption.IGNORE_CASE)
private const val NAME_WORDS = 5

/** The recipe's steps in cooking order. */
fun Recipe.orderedSteps(): List<RecipeStep> = steps.sortedBy { it.position }

private fun joinAnd(items: List<String>): String = when {
    items.size <= 1 -> items.firstOrNull().orEmpty()
    else -> items.dropLast(1).joinToString(", ") + " et " + items.last()
}

/** « Cuire les pâtes » is awaited as « les pâtes »: from the first article on, or the whole text. */
fun awaitedName(text: String): String {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val from = words.indexOfFirst { ARTICLE.matches(it) }
    val picked = (if (from >= 0) words.drop(from) else words).take(NAME_WORDS).joinToString(" ").trimEnd(',', ';', '.', ':', '!', '?')
    return picked.replaceFirstChar { it.lowercase() }
}

/**
 * The earlier steps [position] still waits for. Before the timers (MAG-582) a step without a duration is
 * finished once passed, and a timed one only when the cook says « C'est prêt ».
 */
fun pendingWaits(steps: List<RecipeStep>, position: Int, ready: List<Int>): List<Int> =
    steps.getOrNull(position - 1)?.waitsFor.orEmpty().filter { awaited ->
        steps.getOrNull(awaited - 1)?.durationMinutes != null && awaited !in ready
    }

/** « Attend les pâtes et les légumes · étapes 3 et 4 ». */
fun waitLabel(steps: List<RecipeStep>, pending: List<Int>): String {
    val names = joinAnd(pending.map { awaitedName(steps.getOrNull(it - 1)?.text.orEmpty()) })
    return "Attend $names · ${if (pending.size == 1) "étape" else "étapes"} ${joinAnd(pending.map(Int::toString))}"
}

/** An ingredient a step uses, with the recipe's (scaled) line when there is one. */
data class StepNeed(val key: String, val name: String, val line: RecipeIngredient?)

fun stepIngredients(step: RecipeStep, lines: List<RecipeIngredient>): List<StepNeed> =
    step.ingredients.flatMap { ref ->
        val matching = lines.filter { it.ingredientId() == ref.id }
        if (matching.isEmpty()) {
            listOf(StepNeed(ref.id, ref.name, null))
        } else {
            matching.mapIndexed { index, line -> StepNeed("${ref.id}-${line.id ?: index}", ref.name, line) }
        }
    }
