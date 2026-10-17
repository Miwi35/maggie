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
    // "internal" when the line is one leg of a move between the owner's own accounts — and so
    // are "advance", "repayment" and "funding", which say what the move is for (MAG-276);
    // "rejected" when it is a payment the bank rejected or the credit that gave it back.
    // Anything but "none" is neutral: neither an expense nor an income.
    val transferKind: String = "none",
    val transferSource: String = "auto",
    // IRIs, as API Platform hands relations back ("/api/categories/01H…"); category is null while the line is not filed.
    val account: String? = null,
    val category: String? = null,
    val counterpartyName: String? = null,
    val recurringOperation: String? = null,
    val recurringOccurrenceOn: String? = null,
    val recurringSource: String? = null,
    // Free text of the owner, at most 2000 characters; the API omits it while empty.
    val note: String? = null,
    // IRI of the one-off envelope (Hellfest 2026) the spend is attributed to by hand; it then
    // keeps its category but leaves the monthly and annual envelopes (MAG-414).
    val oneOffEnvelope: String? = null,
    // Whether the line is mandatory and what kind of movement it is, next to its category.
    val qualification: Qualification = Qualification.NONE,
    // Who set the qualification: "manual", "series", "rule", or null while nobody did; the two
    // IRIs name the series or the rule when it was one of them.
    val qualifiedBy: String? = null,
    val qualifiedBySeries: String? = null,
    val qualifiedByRule: String? = null,
    // The part of a shared spend the owner kept, in absolute cents: only it counts in the
    // envelopes, the rest is owed by others (MAG-451). Null while the spend is not shared.
    val myShareCents: Int? = null,
    // What a transfer to or from someone close is (MAG-453): "income", "repayment", "new_debt"
    // for money received, "repayment", "new_claim", "other" for money sent; null until answered.
    val peerKind: String? = null,
) {
    val categoryId: String? get() = category?.substringAfterLast('/')

    val oneOffEnvelopeId: String? get() = oneOffEnvelope?.substringAfterLast('/')

    val isAttachedToRecurring: Boolean get() = recurringOperation != null

    val isInternalTransfer: Boolean get() = transferKind != "none" && transferKind != "rejected"

    val isRejected: Boolean get() = transferKind == "rejected"

    val isShared: Boolean get() = myShareCents != null
}

/**
 * The badge a neutral line wears: « Virement interne », « Rejeté » on the rejected debit,
 * « Rejet de … » on the credit that gave it back — « Rejet » alone while the rejected
 * payment's label is unknown, as in the list. Null for an ordinary line.
 */
fun transferBadgeLabel(transferKind: String, amountCents: Int, counterpartLabel: String? = null): String? =
    when (transferKind) {
        "internal" -> "Virement interne"
        "advance" -> "Avance d'épargne"
        "repayment" -> "Remboursement d'avance"
        "funding" -> "Approvisionnement"
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
