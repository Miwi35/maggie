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

/** What a period was meant to spend by a date, and what it has spent. */
@Serializable
data class Rhythm(
    val budgetCents: Int = 0,
    val spentCents: Int = 0,
    val expectedSpentCents: Int = 0,
    val remainingCents: Int = 0,
    val expectedRemainingCents: Int = 0,
    // `on_track` or `above`.
    val status: String = "on_track",
    val label: String = "",
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
        "total_budget_exceeded" -> "Budget du mois dépassé de $amount"
        "week_budget_exceeded" -> "Budget de la semaine dépassé de $amount"
        "above_rhythm" -> "$amount de plus que le rythme attendu"
        "below_rhythm" -> "$amount de moins que le rythme attendu"
        "on_rhythm" -> "Dans le rythme : $amount sous l'attendu"
        "cushion_incomplete" -> "Matelas incomplet : il manque $amount"
        "below_last_year" -> "$amount de moins qu'à la même période l'an dernier"
        "above_last_year" -> "$amount de plus qu'à la même période l'an dernier"
        "spending_above_reference_salary" -> "$amount de plus que le salaire de référence"
        "no_budget" -> "Aucune enveloppe sur cette période : rien à comparer"
        else -> reason.code
    }
}
