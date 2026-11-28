package com.maggie.app.ui.screens.cookbook.ingredients

import com.maggie.app.data.model.Ingredient
import com.maggie.app.ui.components.SubviewItem
import java.text.Normalizer
import kotlin.math.roundToInt

/** How an ingredient's nutrition values stand; hand-typed values have no state. */
enum class IngredientValuesState { CIQUAL, NONE }

/** The pills of the list: « Tous » shows everything, the two others one state. */
enum class IngredientPill(val key: String, val label: String) {
    ALL("all", "Tous"),
    NONE("none", "Sans valeurs"),
    CIQUAL("ciqual", "Ciqual"),
}

fun ingredientValuesState(ingredient: Ingredient): IngredientValuesState? = when {
    !ingredient.ciqualAlimCode.isNullOrBlank() -> IngredientValuesState.CIQUAL
    ingredient.kcalPer100g == null -> IngredientValuesState.NONE
    else -> null
}

private fun IngredientPill.matches(ingredient: Ingredient): Boolean = when (this) {
    IngredientPill.ALL -> true
    IngredientPill.NONE -> ingredientValuesState(ingredient) == IngredientValuesState.NONE
    IngredientPill.CIQUAL -> ingredientValuesState(ingredient) == IngredientValuesState.CIQUAL
}

fun ingredientPills(ingredients: List<Ingredient>): List<SubviewItem> =
    IngredientPill.entries.map { pill -> SubviewItem(pill.key, pill.label, ingredients.count(pill::matches)) }

fun filterIngredients(ingredients: List<Ingredient>, query: String, pill: IngredientPill): List<Ingredient> {
    val needle = query.trim().fold()
    return ingredients.filter { pill.matches(it) && it.name.fold().contains(needle) }
}

/** « 40 kcal · 1 pièce ≈ 100 g »: each part only when known; null when neither is. */
fun ingredientFacts(ingredient: Ingredient): String? = listOfNotNull(
    ingredient.kcalPer100g?.let { "${it.roundToInt()} kcal" },
    ingredient.pieceWeightGrams?.let { "1 pièce ≈ ${it.roundToInt()} g" },
).joinToString(" · ").ifEmpty { null }

private fun String.fold(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
