package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Transaction(
    val id: String,
    val label: String,
    val amountCents: Int = 0,
    val currency: String = "EUR",
    val bookedAt: String? = null,
    val status: String = "spent",
    val isExceptional: Boolean = false,
)

/** Human-readable French label for a transaction status code. */
fun transactionStatusLabel(status: String): String = when (status) {
    "spent" -> "Dépensée"
    "committed" -> "Engagée"
    "planned" -> "Planifiée"
    "to_arbitrate" -> "À arbitrer"
    else -> status
}
