package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Product(
    val id: String,
    val name: String,
    val defaultUnit: CookbookUnit? = null,
    val category: ProductCategory,
)
