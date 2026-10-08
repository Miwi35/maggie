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
    // "internal" when the line is one leg of a move between the owner's own accounts,
    // "rejected" when it is a payment the bank rejected or the credit that gave it back.
    // Anything but "none" is neutral: neither an expense nor an income.
    val transferKind: String = "none",
    val transferSource: String = "auto",
) {
    val isInternalTransfer: Boolean get() = transferKind == "internal"

    val isRejected: Boolean get() = transferKind == "rejected"
}

/**
 * The badge a neutral line wears: « Virement interne », « Rejeté » on the rejected debit,
 * « Rejet de … » on the credit that gave it back — « Rejet » alone while the rejected
 * payment's label is unknown, as in the list. Null for an ordinary line.
 */
fun transferBadgeLabel(transferKind: String, amountCents: Int, counterpartLabel: String? = null): String? =
    when (transferKind) {
        "internal" -> "Virement interne"
        "rejected" -> when {
            amountCents < 0 -> "Rejeté"
            counterpartLabel.isNullOrBlank() -> "Rejet"
            else -> "Rejet de $counterpartLabel"
        }
        else -> null
    }

/** One leg of an internal transfer or a rejection, with the account it sits on. */
@Serializable
data class TransferLeg(
    val id: String,
    val label: String,
    val amountCents: Int = 0,
    val currency: String = "EUR",
    val bookedAt: String? = null,
    val accountId: String? = null,
    val accountName: String? = null,
)

/** What `GET /api/finance/transactions/{id}/transfer` says about a line. */
@Serializable
data class TransferInfo(
    val transferKind: String = "none",
    val transferSource: String = "auto",
    val counterpart: TransferLeg? = null,
)

@Serializable
data class TransferCandidates(
    val candidates: List<TransferLeg> = emptyList(),
)

@Serializable
data class TransferUpdateRequest(
    val transferKind: String,
    val counterpartId: String? = null,
)

/** « Courant · 2026-09-13 · Virement du Livret », the line the screens show under the badge. */
fun transferLegSummary(leg: TransferLeg): String = listOfNotNull(
    leg.accountName,
    leg.bookedAt?.take(10),
    leg.label,
).joinToString(" · ")

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
