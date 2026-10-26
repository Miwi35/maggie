package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Recipe(
    val id: String,
    val name: String,
    val servings: Int = 4,
    val tags: List<String> = emptyList(),
    val notes: String? = null,
    val ingredients: List<RecipeIngredient> = emptyList(),
    val utensils: List<Utensil> = emptyList(),
    val steps: List<RecipeStep> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val micronutrientsPerServing: List<Micronutrient> = emptyList(),
)

/**
 * A vitamin, mineral or fatty acid of one serving, computed by the API from Ciqual (MAG-648).
 * [group] is vitamins, minerals or fattyAcids; [referencePercent] the share of the daily
 * reference value, null for a micronutrient without one.
 */
@Serializable
data class Micronutrient(
    val key: String,
    val label: String,
    val group: String,
    val amount: Float,
    val unit: String,
    val referencePercent: Int? = null,
)

/** A piece of equipment of the user's catalogue, on a recipe or on one of its steps. */
@Serializable
data class Utensil(
    val id: String,
    val name: String,
)

/** An ingredient a step uses: the recipe's ingredient (not its line), by id and name. */
@Serializable
data class StepIngredient(
    val id: String,
    val name: String,
)

/**
 * One step of a recipe. [waitsFor] lists the positions of the earlier steps it waits for;
 * [background] means its timer runs while the cook moves on (only with a [durationMinutes]).
 */
@Serializable
data class RecipeStep(
    val id: String? = null,
    val position: Int,
    val text: String,
    val durationMinutes: Int? = null,
    val background: Boolean = false,
    val ingredients: List<StepIngredient> = emptyList(),
    val utensils: List<Utensil> = emptyList(),
    val waitsFor: List<Int> = emptyList(),
)
