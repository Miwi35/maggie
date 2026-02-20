package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class GroceryList(
    val id: String,
    val weekStart: String,
    val status: GroceryListStatus = GroceryListStatus.DRAFT,
    val items: List<GroceryItem> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
)
