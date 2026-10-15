package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RecipeIngredient(
    val id: String? = null,
    val ingredient: JsonElement? = null,
    val ingredientName: String? = null,
    val ciqualAlimCode: String? = null,
    val quantity: Float,
    val unit: CookbookUnit,
    // Whether the ingredient is in season this month (MAG-494); null: no season, no badge.
    val inSeason: Boolean? = null,
)
