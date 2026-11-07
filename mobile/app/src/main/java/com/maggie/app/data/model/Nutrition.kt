package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * One local day of `GET /api/health/daily-balance` (MAG-748): what was eaten, what is still planned, what was spent,
 * and [balanceKcal] = eaten + planned − expended — null while the profile is incomplete (no expenditure). The
 * figures are the API's, never recomputed here. [plannedMeals] comes with `?date=` only; the range omits it.
 */
@Serializable
data class DailyBalance(
    val date: String,
    val eatenKcal: Int = 0,
    val plannedKcal: Int = 0,
    val expendedKcal: Int? = null,
    val balanceKcal: Int? = null,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val plannedMeals: List<PlannedMeal> = emptyList(),
)

/** A meal of the day not eaten yet: « Mangé » records one serving of each of its recipes. [kcal] is null without figures. */
@Serializable
data class PlannedMeal(
    val mealId: String,
    val slot: String,
    val label: String,
    val kcal: Int? = null,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
)

/** `GET /api/health/daily-balance?from=&to=`: one row per day, oldest first. */
@Serializable
data class DailyBalanceRange(val days: List<DailyBalance> = emptyList())

/**
 * An entry of the food journal, `/api/health/food-intakes` (MAG-748). [date] is the local day it counts for,
 * [eatenAt] an ISO date-time with its offset; [source] is `meal` (« Mangé »), `manual` or `conversation` (Maggie).
 */
@Serializable
data class FoodIntake(
    val id: String,
    val date: String,
    val eatenAt: String,
    val mealId: String? = null,
    val ciqualCode: String? = null,
    val label: String,
    val quantityGrams: Double? = null,
    val kcal: Int = 0,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val source: String,
) {
    companion object {
        const val SOURCE_MEAL = "meal"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_CONVERSATION = "conversation"
    }
}

/** The French word of a journal entry's origin: « Repas », « Saisie », « Maggie ». */
fun foodIntakeSourceLabel(source: String): String = when (source) {
    FoodIntake.SOURCE_MEAL -> "Repas"
    FoodIntake.SOURCE_CONVERSATION -> "Maggie"
    else -> "Saisie"
}
