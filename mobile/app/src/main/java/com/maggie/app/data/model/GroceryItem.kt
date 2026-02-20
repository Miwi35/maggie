package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class GroceryItem(
    val id: String? = null,
    val product: String? = null,
    val customLabel: String? = null,
    val quantity: Float? = null,
    val unit: CookbookUnit? = null,
    val checked: Boolean = false,
    val source: GroceryItemSource = GroceryItemSource.MANUAL,
) {
    val label: String get() = customLabel ?: product ?: "Unknown"
}
