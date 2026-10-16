package com.maggie.app.ui.screens.cookbook.recipes

import com.maggie.app.data.api.ApiRefusalException

/** Why a recipe was not saved: the API's own sentence, on the step it names when it names one. */
internal class RecipeRefusal(val general: String?, val steps: Map<Int, String>) {
    companion object {
        const val UNREACHABLE = "Impossible d'enregistrer la recette."
        private val stepNumber = Regex("""[Ss]tep (\d+)""")

        fun of(sent: RecipeForm, failure: Throwable): RecipeRefusal {
            if (failure !is ApiRefusalException) return RecipeRefusal(UNREACHABLE, emptyMap())
            val message = failure.message ?: UNREACHABLE
            val step = stepNumber.find(message)?.groupValues?.get(1)?.toIntOrNull()?.let { sent.steps.getOrNull(it - 1) }
            return if (step != null) RecipeRefusal(null, mapOf(step.key to message)) else RecipeRefusal(message, emptyMap())
        }
    }
}

/** A refusal shown on a step goes when that step changes; any move, add or removal renumbers the steps and drops them all. */
internal fun Map<Int, String>.stillValid(before: RecipeForm, after: RecipeForm): Map<Int, String> {
    if (after.steps.map { it.key } != before.steps.map { it.key }) return emptyMap()
    val untouched = after.steps.filter { step -> before.steps.any { it == step } }.map { it.key }.toSet()
    return filterKeys { it in untouched }
}
