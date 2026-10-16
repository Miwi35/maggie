package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class GroceryItem(
    val id: String? = null,
    val label: String = "Unknown",
    val product: Product? = null,
    val customLabel: String? = null,
    val quantity: Float? = null,
    val unit: CookbookUnit? = null,
    val checked: Boolean = false,
    val source: GroceryItemSource = GroceryItemSource.MANUAL,
    val store: Store? = null,
    val buyAfter: String? = null,
    val position: Int = 0,
    // What the meals need, in base units (MAG-380); `quantity` is what to buy.
    val needed: List<Need>? = null,
    // Its product's aisle in its store, null for « Sans rayon » (MAG-493).
    val aisle: AisleSummary? = null,
)
