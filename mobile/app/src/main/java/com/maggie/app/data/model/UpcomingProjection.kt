package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth

/**
 * What is coming (MAG-463): the week after this one and the month after this one, read from the
 * recurring operations and the planned and committed movements. Amounts are cents; expenses of a
 * window are positive, the items keep their sign.
 */
@Serializable
data class UpcomingProjection(
    val today: String = "",
    val week: UpcomingWeek = UpcomingWeek(),
    val month: UpcomingMonth = UpcomingMonth(),
)

/** What both windows carry; [until] is exclusive. */
interface UpcomingPeriod {
    val from: String
    val until: String
    val incomeCents: Int
    val expenseCents: Int
    val netCents: Int
    val incomes: List<UpcomingItem>
    val expenses: List<UpcomingItem>
    val days: List<UpcomingDay>
}

@Serializable
data class UpcomingWeek(
    override val from: String = "",
    override val until: String = "",
    override val incomeCents: Int = 0,
    override val expenseCents: Int = 0,
    override val netCents: Int = 0,
    override val incomes: List<UpcomingItem> = emptyList(),
    override val expenses: List<UpcomingItem> = emptyList(),
    override val days: List<UpcomingDay> = emptyList(),
) : UpcomingPeriod

/** The month after, with its envelopes as adjusted (MAG-461) and what is left toward the projects. */
@Serializable
data class UpcomingMonth(
    override val from: String = "",
    override val until: String = "",
    override val incomeCents: Int = 0,
    override val expenseCents: Int = 0,
    override val netCents: Int = 0,
    override val incomes: List<UpcomingItem> = emptyList(),
    override val expenses: List<UpcomingItem> = emptyList(),
    override val days: List<UpcomingDay> = emptyList(),
    /** `YYYY-MM`. */
    val month: String = "",
    val envelopes: List<UpcomingEnvelope> = emptyList(),
    val envelopesCents: Int = 0,
    /** Committed outside every envelope. */
    val unbudgetedCents: Int = 0,
    val projectedExpenseCents: Int = 0,
    /** Expected income less projected spending; negative when the month is short. */
    val remainingCents: Int = 0,
) : UpcomingPeriod

@Serializable
data class UpcomingItem(
    val date: String = "",
    val label: String = "",
    /** Signed: an expense is negative. */
    val amountCents: Int = 0,
    /** `recurring`, `planned` or `committed`. */
    val source: String = "",
    val categoryId: String? = null,
    val categoryName: String? = null,
)

@Serializable
data class UpcomingDay(
    val date: String = "",
    val incomeCents: Int = 0,
    val expenseCents: Int = 0,
)

@Serializable
data class UpcomingEnvelope(
    /** Null while the next month has no envelope of its own: the amount is what rolling over would give. */
    val envelopeId: String? = null,
    val categoryId: String = "",
    val categoryName: String = "",
    val categoryPath: String = "",
    val amountCents: Int = 0,
    val committedCents: Int = 0,
    /** The greater of the amount and what is committed. */
    val projectedCents: Int = 0,
    val isOvercommitted: Boolean = false,
)

fun upcomingSourceLabel(source: String): String = when (source) {
    "recurring" -> "récurrente"
    "planned" -> "prévue"
    "committed" -> "engagée"
    else -> source
}

/** « Novembre 2026 » from `2026-11`; the text itself if it cannot be read. */
fun upcomingMonthLabel(month: String): String =
    runCatching { YearMonth.parse(month) }
        .map { "${monthLabel(it.monthValue)} ${it.year}" }
        .getOrDefault(month)

/** « 19 octobre – 25 octobre », the last day being the day before the exclusive end. */
fun upcomingRangeLabel(from: String, until: String): String =
    runCatching {
        val last = LocalDate.parse(until).minusDays(1).toString()
        "${treasuryDate(from)} – ${treasuryDate(last)}"
    }.getOrDefault("$from – $until")

/** How much of the projected spending is already committed, for the bar of an envelope. */
fun UpcomingEnvelope.committedFraction(): Float = consumedFraction(committedCents, projectedCents)

/** Where the budgeted amount sits on the bar when the commitments go beyond it; null when they do not. */
fun UpcomingEnvelope.budgetMarker(): Float? =
    if (isOvercommitted && projectedCents > 0) (amountCents.toFloat() / projectedCents).coerceIn(0f, 1f) else null

/** The share of the expected income that the projected spending takes, 0 to 1; 1 when there is no income to speak of. */
fun UpcomingMonth.spendingShare(): Float = consumedFraction(projectedExpenseCents, incomeCents)
