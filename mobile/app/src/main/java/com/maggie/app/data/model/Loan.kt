package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Loan(
    val id: String,
    val name: String,
    val lender: String? = null,
    val principalRemainingCents: Int = 0,
    val monthlyPaymentCents: Int = 0,
    val annualRateBasisPoints: Int = 0,
    val priority: Int = 0,
    val currency: String = "EUR",
)

@Serializable
data class LoanSchedule(
    val id: String,
    val name: String,
    val lender: String? = null,
    val currency: String = "EUR",
    val principalRemainingCents: Int = 0,
    val monthlyPaymentCents: Int = 0,
    val annualRateBasisPoints: Int = 0,
    val priority: Int = 0,
    val monthsRemaining: Int? = null,
    val freedOn: String? = null,
    val totalInterestCents: Int = 0,
    val endsBeyondHorizon: Boolean = false,
)

@Serializable
data class MonthlyRelief(
    val month: String,
    val freedCents: Int = 0,
    val cumulativeFreedCents: Int = 0,
    val loans: List<String> = emptyList(),
)

@Serializable
data class SavingCapacity(
    val monthlyNetIncomeCents: Int = 0,
    val loanPaymentsCents: Int = 0,
    val estimatedLifestyleCents: Int = 0,
    val netCapacityCents: Int = 0,
    val isIncomeKnown: Boolean = false,
)

@Serializable
data class DebtTimeline(
    val horizonMonths: Int = 60,
    val totalPrincipalRemainingCents: Int = 0,
    val totalMonthlyPaymentCents: Int = 0,
    val totalInterestOverHorizonCents: Int = 0,
    val loans: List<LoanSchedule> = emptyList(),
    val reliefByMonth: List<MonthlyRelief> = emptyList(),
    val savingCapacity: SavingCapacity = SavingCapacity(),
)

/** "3,50 %" from 350 basis points. */
fun formatRate(basisPoints: Int): String =
    String.format(java.util.Locale.FRANCE, "%.2f %%", basisPoints / 100.0)

private val LOAN_MONTHS = listOf(
    "Janvier", "Février", "Mars", "Avril", "Mai", "Juin",
    "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre",
)

/** "Mars 2027" from the API's "2027-03". */
fun formatLoanMonth(month: String?): String {
    if (month == null) return "—"
    val parts = month.split("-")
    val name = parts.getOrNull(1)?.toIntOrNull()?.let { LOAN_MONTHS.getOrNull(it - 1) }

    return if (name != null) "$name ${parts[0]}" else month
}

/** How long is left, said the way a person would. */
fun formatRemaining(monthsRemaining: Int?): String = when {
    monthsRemaining == null -> "Au-delà de l'horizon"
    monthsRemaining < 12 -> "$monthsRemaining mois"
    else -> {
        val years = monthsRemaining / 12
        val months = monthsRemaining % 12
        val yearLabel = if (years > 1) "$years ans" else "$years an"
        if (months == 0) yearLabel else "$yearLabel et $months mois"
    }
}
