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
    val createdAt: String? = null,
    val updatedAt: String? = null,
)
