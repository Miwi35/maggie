package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * A rule the statement already implies.
 *
 * Nothing is stored until the user says yes: a suggestion is an offer, with a
 * guessed heading at best. `categoryId` is that guess, and it is null whenever
 * the dictionary had nothing to say.
 */
@Serializable
data class RuleSuggestion(
    val pattern: String,
    val occurrences: Int = 0,
    val totalCents: Int = 0,
    val direction: String = "any",
    val categoryId: String? = null,
    val categoryName: String? = null,
    val samples: List<String> = emptyList(),
)

@Serializable
data class RuleSuggestionsResponse(
    val suggestions: List<RuleSuggestion> = emptyList(),
)

@Serializable
data class AcceptedRuleSuggestion(
    val pattern: String,
    val categoryId: String,
    val direction: String = "any",
)

@Serializable
data class AcceptRuleSuggestionsRequest(
    val rules: List<AcceptedRuleSuggestion>,
)

@Serializable
data class AcceptRuleSuggestionsResult(
    val success: Boolean = false,
    val created: Int = 0,
    val categorized: Int = 0,
    val patterns: List<String> = emptyList(),
)

/** How often the merchant came back, and for how much, in one line. */
fun suggestionWeight(suggestion: RuleSuggestion): String =
    "${suggestion.occurrences} fois · ${formatCents(suggestion.totalCents)}"

/** What was accepted, and what it did to the history straight away. */
fun acceptedRulesSummary(result: AcceptRuleSuggestionsResult): String =
    "${result.created} règle(s) créée(s), ${result.categorized} opération(s) rangée(s)."
