package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/** The user's meal settings (MAG-482), `GET /api/meal-settings/me`: how many people a meal is cooked for unless it says otherwise. */
@Serializable
data class MealSettings(
    val id: String,
    val defaultGuests: Int,
    val defaults: MealSettingsValues,
) {
    val values: MealSettingsValues get() = MealSettingsValues(defaultGuests)
}

/** The editable part of [MealSettings]: what `defaults` holds and what `PUT` takes. */
@Serializable
data class MealSettingsValues(val defaultGuests: Int)
