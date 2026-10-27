package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * The score of the week and of the month, each with the spending rhythm behind it (MAG-417).
 *
 * `GET /api/finance/scores`. [status] is `green`, `neutral`, `orange` or `red`; the API sends its
 * own French [PeriodScore.label] for each, so the app never rewords a status.
 */
@Serializable
data class FinanceScores(
    val date: String = "",
    val week: PeriodScore = PeriodScore(),
    val month: PeriodScore = PeriodScore(),
    val global: GlobalScore = GlobalScore(),
)

@Serializable
data class PeriodScore(
    val status: String = "neutral",
    val label: String = "",
    val reasons: List<ScoreReason> = emptyList(),
    val rhythm: Rhythm = Rhythm(),
)

@Serializable
data class GlobalScore(
    val status: String = "neutral",
    val label: String = "",
    val reasons: List<ScoreReason> = emptyList(),
)

@Serializable
data class ScoreReason(
    val code: String,
    val categoryName: String? = null,
    val amountCents: Int? = null,
)

/**
 * What a period has spent against what was planned ahead, two figures to read and never a verdict (MAG-643).
 *
 * [spentCents] is every real debit of the period to date, classified or not; [expectedSpentCents] the
 * recurring planned debits due by then; [plannedCents] the same over the whole period.
 */
@Serializable
data class Rhythm(
    val spentCents: Int = 0,
    val expectedSpentCents: Int = 0,
    val plannedCents: Int = 0,
    // The period's debits no category claims: they are in [spentCents], and worth inviting to classify.
    val unclassifiedCount: Int = 0,
    val unclassifiedCents: Int = 0,
    val curve: List<RhythmPoint> = emptyList(),
    val debits: List<RhythmDebit> = emptyList(),
)

/** One day of the curve; [spentCents] is null for a day that has not come yet. */
@Serializable
data class RhythmPoint(
    val date: String,
    val spentCents: Int? = null,
    val expectedCents: Int = 0,
)

/** A recurring debit, marked on the curve on its date. */
@Serializable
data class RhythmDebit(
    val date: String,
    val label: String = "",
    val amountCents: Int = 0,
    val recurringOperationId: String? = null,
)

/** Turns a reason code into the sentence a person reads; an unknown code is returned as it is. */
fun reasonText(reason: ScoreReason): String {
    val amount = formatCents(reason.amountCents ?: 0)
    val category = reason.categoryName.orEmpty()

    return when (reason.code) {
        "mandatory_category_exceeded" -> "$category (obligatoire) dépassée de $amount"
        "optional_category_exceeded" -> "$category dépassée de $amount"
        "plans_exceed_category_budget" -> "Les dépenses planifiées feraient dépasser $category de $amount"
        "annual_category_exceeded" -> "$category (annuelle) dépassée de $amount"
        "plans_exceed_annual_category_budget" ->
            "Les dépenses planifiées feraient dépasser $category (annuelle) de $amount"
        "cushion_incomplete" -> "Matelas incomplet : il manque $amount"
        "below_last_year" -> "$amount de moins qu'à la même période l'an dernier"
        "above_last_year" -> "$amount de plus qu'à la même période l'an dernier"
        "spending_above_reference_salary" -> "$amount de plus que le salaire de référence"
        "no_budget" -> "Aucune enveloppe sur cette période : rien à comparer"
        else -> reason.code
    }
}
