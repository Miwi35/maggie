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
    // What was asked of a packaged product, in base units (1400 g in 3 packs); `quantity` counts the
    // packagings, rounded up. Null when nothing was asked in a quantity (MAG-573).
    val desiredQuantity: Double? = null,
    val desiredUnit: CookbookUnit? = null,
    // Its product's aisle in its store, null for « Sans rayon » (MAG-493).
    val aisle: AisleSummary? = null,
    // Whether its product is in season this month (MAG-494); null: no season, no badge.
    val inSeason: Boolean? = null,
)
