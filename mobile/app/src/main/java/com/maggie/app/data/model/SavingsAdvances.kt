package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One withdrawal from a savings account towards the current account, with what was given back. */
@Serializable
data class SavingsAdvance(
    val id: String = "",
    val sourceAccountId: String = "",
    val sourceAccountName: String = "",
    val amountCents: Int = 0,
    // yyyy-MM-dd
    val openedOn: String? = null,
    val repaidCents: Int = 0,
    val outstandingCents: Int = 0,
    val isClosed: Boolean = false,
)

@Serializable
data class SavingsSource(
    val sourceAccountId: String = "",
    val sourceAccountName: String = "",
    val outstandingCents: Int = 0,
    val openCount: Int = 0,
)

/** Where the salary stands against the day the owner expects it. */
@Serializable
data class SalaryDelay(
    val isConfigured: Boolean = false,
    val salaryExpectedDay: Int? = null,
    val referenceCents: Int? = null,
    val isLate: Boolean = false,
    val delayDays: Int = 0,
    val shortfallCents: Int = 0,
)

/** The counter-transfer that would give an advance back: [fromAccountId] (current) to [toAccountId] (savings). */
@Serializable
data class AdvanceRepaymentProposal(
    val fromAccountId: String = "",
    val toAccountId: String = "",
    val amountCents: Int = 0,
    val advanceId: String = "",
    val label: String = "",
    val currency: String = "EUR",
    val isPartial: Boolean = false,
)

/** An open advance the server could not propose a counter-transfer for. */
@Serializable
data class UnproposedAdvance(
    val advanceId: String = "",
    val sourceAccountId: String = "",
    val sourceAccountName: String = "",
    val amountCents: Int = 0,
    val reason: String = "",
)

@Serializable
data class SavingsAdvances(
    val advances: List<SavingsAdvance> = emptyList(),
    val bySource: List<SavingsSource> = emptyList(),
    val totalOutstandingCents: Int = 0,
    val salaryDelay: SalaryDelay = SalaryDelay(),
    val proposals: List<AdvanceRepaymentProposal> = emptyList(),
    val proposalsTotalCents: Int = 0,
    val unproposed: List<UnproposedAdvance> = emptyList(),
    val unproposedCents: Int = 0,
) {
    val openAdvances: List<SavingsAdvance> get() = advances.filter { !it.isClosed }

    val closedAdvances: List<SavingsAdvance> get() = advances.filter { it.isClosed }

    /** The name of the savings account a proposal gives money back to. */
    fun destinationName(proposal: AdvanceRepaymentProposal): String =
        (advances.map { it.sourceAccountId to it.sourceAccountName } +
            bySource.map { it.sourceAccountId to it.sourceAccountName })
            .firstOrNull { (id, _) -> id == proposal.toAccountId }
            ?.second
            ?: "le compte d'épargne"
}

@Serializable
data class TreasurySettingsResult(
    val salaryExpectedDay: Int? = null,
    val salaryDelay: SalaryDelay = SalaryDelay(),
)

/** `null` forgets the day: the key is always sent, the API refuses a body without it. */
@Serializable
data class SalaryExpectedDayRequest(val salaryExpectedDay: Int?)

fun unproposedReasonLabel(reason: String): String = when (reason) {
    "insufficient_balance" -> "solde insuffisant sur le compte courant"
    "no_checking_account" -> "aucun compte courant"
    else -> reason
}

private val ADVANCE_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/** « 11/10/2026 » for the API's `2026-10-11`; the text as it came when it is not a date. */
fun formatAdvanceDate(date: String?): String? {
    if (date == null) return null
    return runCatching { LocalDate.parse(date).format(ADVANCE_DATE) }.getOrDefault(date)
}
