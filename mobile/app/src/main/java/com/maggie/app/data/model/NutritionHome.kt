package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * The Nutrition card of the Accueil (MAG-564): `GET /api/home/nutrition?day=`. Both slots are always there,
 * lunch first; an empty one has no meal.
 */
@Serializable
data class NutritionHomeState(
    val day: String = "",
    val date: String = "",
    val slots: List<NutritionHomeSlot> = emptyList(),
)

@Serializable
data class NutritionHomeSlot(
    /** `lunch` or `dinner`. */
    val slot: String,
    val meals: List<NutritionHomeMeal> = emptyList(),
)

@Serializable
data class NutritionHomeMeal(
    val id: String,
    val title: String = "",
    val guests: Int? = null,
    val recipes: List<NutritionHomeName> = emptyList(),
    /** The ingredients of the recipes that are low or out of stock, once each. */
    val missing: List<NutritionHomeName> = emptyList(),
)

@Serializable
data class NutritionHomeName(val id: String, val name: String)

/** All the meal's recipes on one line, « Pâtes · Salade »; the meal's own title when it has none. */
fun NutritionHomeMeal.recipesLabel(): String =
    recipes.takeIf { it.isNotEmpty() }?.joinToString(" · ") { it.name } ?: title

/** « manque : parmesan, basilic », or null when nothing is missing. */
fun NutritionHomeMeal.missingLabel(): String? =
    missing.takeIf { it.isNotEmpty() }
        ?.joinToString(", ", prefix = "manque : ") { it.name.replaceFirstChar { c -> c.lowercase() } }

fun NutritionHomeSlot.label(): String = when (slot) {
    "lunch" -> "Déjeuner"
    "dinner" -> "Dîner"
    else -> slot
}
