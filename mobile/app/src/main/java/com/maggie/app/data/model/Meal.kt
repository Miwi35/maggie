package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Meal(
    val id: String,
    val summary: String,
    val startAt: String,
    val endAt: String,
    val slot: MealSlot,
    val recipes: List<String> = emptyList(),
    val allDay: Boolean = false,
    val agenda: String? = null,
)
