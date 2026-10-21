package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/** The heading the app would file a line under, and why: a suggestion without its reason is a guess taken on trust. */
@Serializable
data class SortSuggestion(
    val categoryId: String,
    val categoryPath: String,
    // "rule" (the owner's own) or "merchant" (the dictionary's guess).
    val source: String = "rule",
    val reason: String = "",
)

/** One line of the « À qualifier » pile. [judgeable] is true for a spend that can be given a [retrospect]: "unrated", "keep" or "avoidable". */
@Serializable
data class TransactionToSort(
    val id: String,
    val label: String,
    val counterpartyName: String? = null,
    val amountCents: Int = 0,
    val currency: String = "EUR",
    val bookedAt: String? = null,
    val accountId: String? = null,
    val accountName: String? = null,
    val suggestion: SortSuggestion? = null,
    val judgeable: Boolean = false,
    val retrospect: String = RETROSPECT_UNRATED,
    // The contact one of the owner's counterparties recognises the line as (MAG-450).
    val contact: ContactRef? = null,
    // Whether « Associer à un contact ? » is worth asking: a transfer nobody claims yet.
    val asksForContact: Boolean = false,
    // The label to link when a contact is picked: the counterparty the bank named, or the label without its references.
    val counterpartyLabel: String = "",
    // Why the line waits (MAG-453): "category" (none yet) or "peer_transfer" (a transfer to or from a contact not yet qualified).
    val reason: String = TO_SORT_REASON_CATEGORY,
    // Whether the card asks « Ce virement est », saved through `PUT …/allocation`.
    val asksForPeerKind: Boolean = false,
)

const val TO_SORT_REASON_CATEGORY = "category"
const val TO_SORT_REASON_PEER_TRANSFER = "peer_transfer"

const val RETROSPECT_UNRATED = "unrated"
const val RETROSPECT_KEEP = "keep"
const val RETROSPECT_AVOIDABLE = "avoidable"

/** `GET /api/finance/transactions/to-sort`: [total] counts the whole pile and [totalCents] weighs it, [transactions] is its first page. */
@Serializable
data class ToSortPile(
    val total: Int = 0,
    val transactions: List<TransactionToSort> = emptyList(),
    val totalCents: Int = 0,
)

@Serializable
data class InternalTransferAnswer(
    val counterpartId: String? = null,
)

/** Either a [categoryId] (with [alwaysForMerchant]) or an [internalTransfer], never both. */
@Serializable
data class SortRequest(
    val categoryId: String? = null,
    val alwaysForMerchant: Boolean? = null,
    val internalTransfer: InternalTransferAnswer? = null,
)

@Serializable
data class SortedRule(
    val id: String,
    val labelPattern: String? = null,
)

@Serializable
data class SortResult(
    val success: Boolean = false,
    val transactionId: String? = null,
    val categoryId: String? = null,
    val rule: SortedRule? = null,
    val ruleCreated: Boolean = false,
    // The other lines of the same merchant the new rule filed in the same stroke.
    val alsoSorted: Int = 0,
    val remaining: Int = 0,
)

/** What follows a « Toujours pour ce marchand », or null when no rule was written. */
fun sortedSummary(result: SortResult): String? {
    val pattern = result.rule?.labelPattern ?: return null
    if (!result.ruleCreated) {
        return if (result.alsoSorted > 0) "Règle « $pattern » · ${otherLines(result.alsoSorted)}" else null
    }

    return "Règle « $pattern » créée" + if (result.alsoSorted > 0) " · ${otherLines(result.alsoSorted)}" else ""
}

private fun otherLines(count: Int): String =
    if (count == 1) "1 autre ligne classée" else "$count autres lignes classées"

/** The pill of the dashboard and the budget: « À qualifier · 3 · 65.20 EUR », or null when there is nothing to qualify. */
fun toSortPillLabel(pile: ToSortPile): String? =
    if (pile.total > 0) "À qualifier · ${pile.total} · ${formatCents(pile.totalCents)}" else null
