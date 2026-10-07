package com.maggie.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Transaction(
    val id: String,
    val label: String,
    val amountCents: Int = 0,
    val currency: String = "EUR",
    val bookedAt: String? = null,
    val status: String = "spent",
    // Serialised as "exceptional" by the API, for the same reason as
    // Account.isCushion.
    @SerialName("exceptional") val isExceptional: Boolean = false,
)

/**
 * A transaction is an expense or an income, and the sign of its amount says
 * which. The status codes are shared; their wording is not: a salary is never
 * "Dépensée".
 */
enum class TransactionNature { Expense, Income }

fun transactionNature(amountCents: Int): TransactionNature =
    if (amountCents > 0) TransactionNature.Income else TransactionNature.Expense

/** The amount is typed without a sign; the nature gives it back. */
fun signedAmountCents(magnitudeCents: Int, nature: TransactionNature): Int {
    val magnitude = kotlin.math.abs(magnitudeCents)
    return if (nature == TransactionNature.Income) magnitude else -magnitude
}

/** "Engagée" means nothing for money coming in: it is not offered for an income. */
fun transactionStatusCodes(nature: TransactionNature): List<String> = when (nature) {
    TransactionNature.Expense -> listOf("spent", "committed", "planned", "to_arbitrate")
    TransactionNature.Income -> listOf("spent", "planned", "to_arbitrate")
}

/** The status to keep when the nature changes: "Engagée" becomes "Attendue". */
fun statusForNature(status: String, nature: TransactionNature): String =
    if (status in transactionStatusCodes(nature)) status else "planned"

/** Human-readable French label for a transaction status code, by the nature of the transaction. */
fun transactionStatusLabel(status: String, nature: TransactionNature): String = when (nature) {
    TransactionNature.Expense -> when (status) {
        "spent" -> "Dépensée"
        "committed" -> "Engagée"
        "planned" -> "Planifiée"
        "to_arbitrate" -> "À arbitrer"
        else -> status
    }
    TransactionNature.Income -> when (status) {
        "spent" -> "Reçue"
        // A line saved as "committed" before the form knew better reads as expected.
        "planned", "committed" -> "Attendue"
        "to_arbitrate" -> "À arbitrer"
        else -> status
    }
}

/** Same label, nature read from the sign of the amount. */
fun transactionStatusLabel(status: String, amountCents: Int): String =
    transactionStatusLabel(status, transactionNature(amountCents))

/** Whether a category can be used on a transaction of this nature (income categories only on an income). */
fun categoryMatchesNature(category: Category, nature: TransactionNature): Boolean =
    (category.obligation == "income") == (nature == TransactionNature.Income)
