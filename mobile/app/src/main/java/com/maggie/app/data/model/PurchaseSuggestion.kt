package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * A product the user buys at a steady rhythm and that is due again (MAG-495),
 * as `GET /api/grocery-suggestions` sends it — a plain JSON array, no Hydra envelope.
 */
@Serializable
data class PurchaseSuggestion(
    val product: SuggestedProduct,
    /** The usual number of days between two purchases. */
    val everyDays: Int,
    val lastBoughtOn: String,
    val dueOn: String,
    val usualQuantity: Double? = null,
    val usualUnit: String? = null,
)

@Serializable
data class SuggestedProduct(
    val id: String,
    val name: String,
)
