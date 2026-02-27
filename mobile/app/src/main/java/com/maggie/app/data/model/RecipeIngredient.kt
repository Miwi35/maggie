package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class RecipeIngredient(
    val id: String? = null,
    val ingredient: String,
    val ingredientName: String? = null,
    val quantity: Float,
    val unit: CookbookUnit,
)
