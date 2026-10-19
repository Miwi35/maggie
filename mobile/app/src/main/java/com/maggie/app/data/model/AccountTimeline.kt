package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * One month of an account, day by day (`GET /api/finance/accounts/{id}/timeline?month=YYYY-MM`, MAG-422).
 *
 * Not an API Platform resource: the names are the ones the controller writes, `isCushion` and
 * `isManual` included (DtoContractTest reads them from the recorded response).
 */
@Serializable
data class AccountTimeline(
    val month: String,
    // The month to ask for next to go back in time; null once the account has nothing older.
    val previousMonth: String? = null,
    val account: TimelineAccount,
    val days: List<TimelineDay> = emptyList(),
)

@Serializable
data class TimelineAccount(
    val id: String,
    val name: String,
    val bank: String? = null,
    val type: String = "checking",
    val currency: String = "EUR",
    val balanceCents: Int = 0,
    val isCushion: Boolean = false,
    // Typed in by hand, not fed by a bank link: the only accounts whose balance the owner corrects.
    val isManual: Boolean = false,
)

/** A day with at least one line or one missed occurrence; the API lists them from the most recent. */
@Serializable
data class TimelineDay(
    val date: String,
    val transactions: List<TimelineLine> = emptyList(),
    val missedOccurrences: List<TimelineMissedOccurrence> = emptyList(),
)

@Serializable
data class TimelineLine(
    val id: String,
    val label: String,
    val counterpartyName: String? = null,
    val amountCents: Int = 0,
    val currency: String = "EUR",
    val status: String = "spent",
    val categoryId: String? = null,
    val transferKind: String = "none",
    // The account on the other side of an internal transfer.
    val counterpartAccount: TimelineCounterpartAccount? = null,
    val recurringOperationId: String? = null,
    val recurringIncrease: RecurringIncrease? = null,
)

@Serializable
data class TimelineCounterpartAccount(
    val id: String,
    val name: String,
)

/** The attached series went up by [percent] % since the line before this one. */
@Serializable
data class RecurringIncrease(
    val percent: Int,
    val previousCents: Int = 0,
)

/** A recurring operation that should have shown up on [expectedOn] and has not. */
@Serializable
data class TimelineMissedOccurrence(
    val recurringOperationId: String,
    val label: String,
    val expectedOn: String,
    val amountCents: Int = 0,
)

/**
 * « depuis Compte courant » on the credit leg of a transfer, « vers Livret » on the debit leg.
 * Null for an ordinary line. Moves that say what they are for (advance, funding…) keep that
 * wording in front; a rejection keeps its own badge.
 */
fun TimelineLine.transferBadge(): String? {
    if (transferKind == "none") return null
    if (transferKind == "rejected") return transferBadgeLabel(transferKind, amountCents)
    val other = counterpartAccount?.name?.takeIf { it.isNotBlank() }
        ?: return transferBadgeLabel(transferKind, amountCents)
    val direction = if (amountCents > 0) "depuis $other" else "vers $other"
    return if (transferKind == "internal") direction else "${transferBadgeLabel(transferKind, amountCents)} · $direction"
}

/** « +33 % » for a line whose series went up. */
fun RecurringIncrease.label(): String = "+$percent %"

/** « Loyer attendu le 5 — en retard »; the first of the month reads « le 1er ». */
fun TimelineMissedOccurrence.lateLabel(): String {
    val day = runCatching { LocalDate.parse(expectedOn).dayOfMonth }.getOrNull()
    val on = when (day) {
        null -> expectedOn
        1 -> "1er"
        else -> day.toString()
    }
    return "$label attendu le $on — en retard"
}

/**
 * « Aujourd'hui », « Hier », otherwise « Lundi 5 octobre » (the year only when it is not [today]'s).
 * An unreadable date is given back as it came.
 */
fun timelineDayTitle(date: String, today: LocalDate = LocalDate.now()): String {
    val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    return when (day) {
        today -> "Aujourd'hui"
        today.minusDays(1) -> "Hier"
        else -> {
            val weekday = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRENCH).replaceFirstChar { it.uppercase() }
            val month = day.month.getDisplayName(TextStyle.FULL, Locale.FRENCH)
            val number = if (day.dayOfMonth == 1) "1er" else day.dayOfMonth.toString()
            val year = if (day.year != today.year) " ${day.year}" else ""
            "$weekday $number $month$year"
        }
    }
}

/** « mars 2026 », the heading of the month a « Mois précédent » loaded. */
fun timelineMonthTitle(month: String): String {
    val parsed = runCatching { java.time.YearMonth.parse(month) }.getOrNull() ?: return month
    return "${parsed.month.getDisplayName(TextStyle.FULL, Locale.FRENCH)} ${parsed.year}"
}
