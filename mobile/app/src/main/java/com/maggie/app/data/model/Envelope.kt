package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Envelope(
    val id: String,
    val mode: String = "monthly",
    val amountCents: Int = 0,
    val currency: String = "EUR",
    val year: Int = 0,
    val month: Int? = null,
)

/** One category budget of a period, with what has already been spent on it. */
@Serializable
data class BudgetLine(
    val id: String,
    val categoryId: String,
    val categoryName: String,
    val mode: String = "monthly",
    val amountCents: Int = 0,
    val currency: String = "EUR",
    val year: Int = 0,
    val month: Int? = null,
    val spentCents: Int = 0,
    val remainingCents: Int = 0,
    val isOverspent: Boolean = false,
)

@Serializable
data class BudgetStatus(
    val year: Int = 0,
    val month: Int = 0,
    val totalBudgetedCents: Int = 0,
    val totalSpentCents: Int = 0,
    val totalRemainingCents: Int = 0,
    val budgets: List<BudgetLine> = emptyList(),
)

/** Human-readable French label for a budget mode code. */
fun budgetModeLabel(mode: String): String = when (mode) {
    "monthly" -> "Mensuel"
    "annual" -> "Annuel"
    else -> mode
}

private val MONTH_NAMES = listOf(
    "Janvier", "Février", "Mars", "Avril", "Mai", "Juin",
    "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre",
)

/** French label of a month number, or the number itself when out of range. */
fun monthLabel(month: Int): String = MONTH_NAMES.getOrNull(month - 1) ?: month.toString()

/** Label of the period a budget covers: "Juillet 2026" or "Année 2026". */
fun budgetPeriodLabel(mode: String, year: Int, month: Int?): String =
    if (mode == "annual" || month == null) "Année $year" else "${monthLabel(month)} $year"

/** Share of a budget already spent, in [0f, 1f], for a progress indicator. */
fun consumedFraction(spentCents: Int, amountCents: Int): Float {
    if (amountCents <= 0) return if (spentCents > 0) 1f else 0f
    return (spentCents.toFloat() / amountCents).coerceIn(0f, 1f)
}
