package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class RecurringGroceryItem(
    val id: String,
    val product: String? = null,
    val customLabel: String? = null,
    val quantity: Float? = null,
    val unit: CookbookUnit? = null,
    val frequency: RecurringFrequency,
)
