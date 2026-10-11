package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CategorizationRule(
    val id: String,
    val labelPattern: String,
    val matchType: String = "contains",
    val direction: String = "any",
    val minAmountCents: Int? = null,
    val maxAmountCents: Int? = null,
    val priority: Int = 0,
    val isActive: Boolean = true,
    val categoryId: String? = null,
    // What the API actually sends for the category: an IRI ("/api/categories/01H…").
    // `categoryId` is the spelling of the search index and of Mercure payloads.
    val category: String? = null,
)

/** Whether the rule files its matches under the category [id], whichever spelling it came in. */
fun CategorizationRule.targetsCategory(id: String): Boolean =
    categoryId == id || category?.substringAfterLast('/') == id

@Serializable
data class ApplyRulesResult(
    val success: Boolean = false,
    val categorized: Int = 0,
    val scanned: Int = 0,
)

/** Human-readable French label for a match type code. */
fun matchTypeLabel(matchType: String): String = when (matchType) {
    "contains" -> "Contient"
    "starts_with" -> "Commence par"
    "equals" -> "Égal à"
    else -> matchType
}

/** Human-readable French label for an amount direction code. */
fun directionLabel(direction: String): String = when (direction) {
    "any" -> "Peu importe"
    "debit" -> "Dépense"
    "credit" -> "Revenu"
    else -> direction
}

/**
 * What a rule narrows on beyond the label. Amount bounds are absolute cents,
 * as the API stores them.
 */
fun describeRuleScope(direction: String, minAmountCents: Int?, maxAmountCents: Int?): String {
    val parts = mutableListOf<String>()

    if (direction != "any") {
        parts += directionLabel(direction)
    }

    when {
        minAmountCents != null && maxAmountCents != null ->
            parts += "entre ${formatCents(minAmountCents)} et ${formatCents(maxAmountCents)}"
        minAmountCents != null -> parts += "à partir de ${formatCents(minAmountCents)}"
        maxAmountCents != null -> parts += "jusqu'à ${formatCents(maxAmountCents)}"
    }

    return parts.joinToString(" · ")
}
