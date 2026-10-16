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

/** One line of the « À trier » pile, newest first. */
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
)

/** `GET /api/finance/transactions/to-sort`: [total] counts the whole pile, [transactions] is its first page. */
@Serializable
data class ToSortPile(
    val total: Int = 0,
    val transactions: List<TransactionToSort> = emptyList(),
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

/** « Il reste 3 lignes à trier » — and nothing to say about an empty pile. */
fun toSortCounter(remaining: Int): String = when (remaining) {
    0 -> "Rien à trier"
    1 -> "1 ligne à trier"
    else -> "$remaining lignes à trier"
}
