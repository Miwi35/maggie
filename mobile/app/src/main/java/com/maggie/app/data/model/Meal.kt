package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * A meal is a day and a slot, never an instant — MAG-251.
 *
 * [date] is `YYYY-MM-DD`. The API derives the instants the agenda shows from
 * it, and no client sends them.
 */
@Serializable
data class Meal(
    val id: String,
    val summary: String,
    val date: String,
    val slot: MealSlot,
    val recipes: List<String> = emptyList(),
    val agenda: String? = null,
)
