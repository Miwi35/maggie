package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * The monthly envelopes read against what was really spent (MAG-461): [month] is the month read,
 * [targetMonth] the one the proposed amounts are for. Amounts are cents.
 */
@Serializable
data class EnvelopeAdjustments(
    val month: String = "",
    val targetMonth: String = "",
    val suggestionCount: Int = 0,
    val envelopes: List<EnvelopeAdjustment> = emptyList(),
)

@Serializable
data class EnvelopeAdjustment(
    val envelopeId: String,
    val categoryId: String,
    val categoryName: String = "",
    val categoryPath: String = "",
    val currency: String = "EUR",
    /** Budgeted for the month read. */
    val amountCents: Int = 0,
    /** Spent in the month read. */
    val actualCents: Int = 0,
    /** The envelope of the target month; null while it does not exist yet. */
    val targetEnvelopeId: String? = null,
    /** What the target month is budgeted now. */
    val targetAmountCents: Int = 0,
    /** Only an envelope that does not fit has one. */
    val suggestion: AdjustmentSuggestion? = null,
)

@Serializable
data class AdjustmentSuggestion(
    val proposedCents: Int,
    /** `raise` or `lower`. */
    val direction: String = RAISE,
    val reason: String = "",
    /** Set when the proposal is a season of the year rather than a change of the base amount. */
    val seasonal: AdjustmentSeason? = null,
) {
    companion object {
        const val RAISE = "raise"
        const val LOWER = "lower"
    }
}

@Serializable
data class AdjustmentSeason(val fromMonth: Int, val toMonth: Int)

@Serializable
data class AdjustmentLine(
    val categoryId: String,
    val amountCents: Int,
    val seasonal: Boolean = false,
)

@Serializable
data class EnvelopeAdjustmentsRequest(
    val month: String,
    val adjustments: List<AdjustmentLine>,
)

@Serializable
data class AppliedAdjustment(
    val categoryId: String,
    val envelopeId: String,
    val amountCents: Int,
    val created: Boolean = false,
)

@Serializable
data class EnvelopeAdjustmentsResult(
    val success: Boolean = true,
    val applied: List<AppliedAdjustment> = emptyList(),
)

/** « Juin–août », « Juillet », or null when the proposal is not a season. */
fun AdjustmentSeason.label(): String =
    if (fromMonth == toMonth) monthLabel(fromMonth) else "${monthLabel(fromMonth)}–${monthLabel(toMonth).lowercase()}"
