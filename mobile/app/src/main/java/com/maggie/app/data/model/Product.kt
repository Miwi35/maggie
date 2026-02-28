package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Product(
    val id: String,
    val name: String,
    val defaultUnit: CookbookUnit? = null,
    val category: ProductCategory,
    val kcalPer100g: Float? = null,
    val proteinPer100g: Float? = null,
    val carbsPer100g: Float? = null,
    val fatPer100g: Float? = null,
    val preferredStore: String? = null,
    val fallbackStore: String? = null,
    val shelfLifeDays: Int? = null,
)
