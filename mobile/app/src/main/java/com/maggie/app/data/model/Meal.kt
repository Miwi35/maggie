package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * A meal is a day and a slot, never an instant — MAG-251.
 *
 * [date] is `YYYY-MM-DD`. The API derives the instants the agenda shows from
 * it, and no client sends them.
 *
 * [summary] is the title the week shows: the owner's [customTitle] when he typed one, the
 * names of the recipes otherwise (MAG-378). [recipes] are their IRIs.
 */
@Serializable
data class Meal(
    val id: String,
    val summary: String,
    val date: String,
    val slot: MealSlot,
    val recipes: List<String> = emptyList(),
    val agenda: String? = null,
    val customTitle: String? = null,
    // The number that counts (the meal's own, or the user's default); [guestsIsDefault] says which (MAG-482).
    val guests: Int = 1,
    val guestsIsDefault: Boolean = true,
)
