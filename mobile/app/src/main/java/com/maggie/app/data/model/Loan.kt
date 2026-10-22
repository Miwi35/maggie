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
    val type: String = LoanLabels.AMORTIZING_FIXED,
    val projected: Boolean = false,
    val initialAmountCents: Int? = null,
    val aprBasisPoints: Int? = null,
    val durationMonths: Int? = null,
    val firstPaymentOn: String? = null,
    val periodicity: String = "monthly",
    val insuranceRateBasisPoints: Int? = null,
    val insuranceMonthlyCents: Int? = null,
    val feesCents: Int? = null,
    val referenceIndex: String? = null,
    val marginBasisPoints: Int? = null,
    val rateCapBasisPoints: Int? = null,
    val rateFloorBasisPoints: Int? = null,
    val steps: List<LoanStep>? = null,
    val deferralMonths: Int? = null,
    val earlyRepaymentAllowed: Boolean = true,
    val prepaymentPenaltyMode: String = LoanLabels.LEGAL_CAP,
    val prepaymentPenaltyFixedCents: Int? = null,
    val minPartialRepaymentCents: Int? = null,
    val partialRepaymentEffect: String? = null,
    val noticeDays: Int? = null,
)

@Serializable
data class LoanStep(
    val fromMonth: Int,
    val monthlyPaymentCents: Int,
)

/** The capital owed month by month until the loan ends: `GET /api/finance/loans/{id}/schedule`. */
@Serializable
data class LoanCurve(
    val principalRemainingCents: Int = 0,
    val freedOn: String? = null,
    val monthsRemaining: Int? = null,
    val totalInterestCents: Int = 0,
    val months: List<LoanCurvePoint> = emptyList(),
)

@Serializable
data class LoanCurvePoint(
    val month: String,
    val remainingCents: Int = 0,
)

/** What repaying [amountCents] early costs: `GET /api/finance/loans/{id}/early-repayment`. */
@Serializable
data class EarlyRepayment(
    val amountCents: Int = 0,
    val penaltyMode: String = LoanLabels.LEGAL_CAP,
    val penaltyCents: Int = 0,
    val remainingInterestCents: Int = 0,
    val realGainCents: Int = 0,
)

/** The French words of the loan types and early repayment modes, the admin's own. */
object LoanLabels {
    const val AMORTIZING_FIXED = "amortizing_fixed"
    const val AMORTIZING_VARIABLE = "amortizing_variable"
    const val IN_FINE = "in_fine"
    const val STEPPED = "stepped"
    const val DEFERRED_PARTIAL = "deferred_partial"
    const val DEFERRED_TOTAL = "deferred_total"
    const val BRIDGE = "bridge"

    const val LEGAL_CAP = "legal_cap"
    const val FIXED = "fixed"
    const val EXEMPT = "exempt"
    const val CONSUMER = "consumer"

    const val SHORTEN = "shorten"
    const val LOWER_PAYMENT = "lower_payment"

    val types: Map<String, String> = linkedMapOf(
        AMORTIZING_FIXED to "Amortissable taux fixe",
        AMORTIZING_VARIABLE to "Amortissable taux variable",
        IN_FINE to "In fine",
        STEPPED to "À paliers",
        DEFERRED_PARTIAL to "Différé partiel",
        DEFERRED_TOTAL to "Différé total",
        BRIDGE to "Prêt relais",
    )

    val penaltyModes: Map<String, String> = linkedMapOf(
        LEGAL_CAP to "Plafond légal",
        FIXED to "Montant fixe",
        EXEMPT to "Aucune IRA",
        CONSUMER to "Crédit à la consommation",
    )

    val partialEffects: Map<String, String> = linkedMapOf(
        SHORTEN to "Réduire la durée",
        LOWER_PAYMENT to "Baisser la mensualité",
    )

    const val DEFERRAL = "Durée du différé"
    const val STEPS = "Paliers"
    const val INDEX = "Indice"
    const val MARGIN = "Marge"
    const val RATE_CAP = "Plafond du taux"
    const val RATE_FLOOR = "Plancher du taux"
    const val PENALTY_FIXED = "Montant de l'IRA"

    fun type(key: String): String = types[key] ?: key

    fun penaltyMode(key: String): String = penaltyModes[key] ?: key
}

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
    val type: String = LoanLabels.AMORTIZING_FIXED,
    val insuranceMonthlyCents: Int? = null,
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

/** The conditions of the loan that are set, label then value, in the order of the sheet; an empty one is left out. */
fun Loan.conditionRows(): List<Pair<String, String>> = buildList {
    add("Taux annuel" to formatRate(annualRateBasisPoints))
    aprBasisPoints?.let { add("TAEG" to formatRate(it)) }
    add("Mensualité" to formatCents(monthlyPaymentCents, currency))
    initialAmountCents?.let { add("Capital emprunté" to formatCents(it, currency)) }
    durationMonths?.let { add("Durée" to formatRemaining(it)) }
    firstPaymentOn?.let { add("Première échéance" to formatLoanMonth(it.take(7))) }
    insuranceMonthlyCents?.let { add("Assurance" to "${formatCents(it, currency)}/mois") }
    insuranceRateBasisPoints?.let { add("Taux d'assurance" to formatRate(it)) }
    feesCents?.let { add("Frais" to formatCents(it, currency)) }
    referenceIndex?.takeIf { it.isNotBlank() }?.let { add(LoanLabels.INDEX to it) }
    marginBasisPoints?.let { add(LoanLabels.MARGIN to formatRate(it)) }
    rateCapBasisPoints?.let { add(LoanLabels.RATE_CAP to formatRate(it)) }
    rateFloorBasisPoints?.let { add(LoanLabels.RATE_FLOOR to formatRate(it)) }
    steps?.takeIf { it.isNotEmpty() }?.let { list ->
        add(
            LoanLabels.STEPS to list.joinToString("\n") {
                "Dès le mois ${it.fromMonth} : ${formatCents(it.monthlyPaymentCents, currency)}"
            },
        )
    }
    deferralMonths?.let { add(LoanLabels.DEFERRAL to "$it mois") }
    if (!earlyRepaymentAllowed) add("Remboursement anticipé" to "Non autorisé")
    minPartialRepaymentCents?.let { add("Remboursement partiel minimum" to formatCents(it, currency)) }
    partialRepaymentEffect?.let { key -> add("Effet d'un remboursement partiel" to (LoanLabels.partialEffects[key] ?: key)) }
    noticeDays?.let { add("Préavis" to "$it jours") }
}
