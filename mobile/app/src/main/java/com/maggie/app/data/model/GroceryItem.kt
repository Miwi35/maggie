package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class GroceryItem(
    val id: String? = null,
    val label: String = "Unknown",
    val product: String? = null,
    val customLabel: String? = null,
    val quantity: Float? = null,
    val unit: CookbookUnit? = null,
    val checked: Boolean = false,
    val source: GroceryItemSource = GroceryItemSource.MANUAL,
    val store: Store? = null,
    val buyAfter: String? = null,
)
