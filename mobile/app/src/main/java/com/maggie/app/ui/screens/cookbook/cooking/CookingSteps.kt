package com.maggie.app.ui.screens.cookbook.cooking

import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.ui.screens.cookbook.recipes.ingredientId

/** The recipe's steps in cooking order. */
fun Recipe.orderedSteps(): List<RecipeStep> = steps.sortedBy { it.position }

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
