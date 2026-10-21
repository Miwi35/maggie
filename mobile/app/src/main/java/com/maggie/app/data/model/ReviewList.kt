package com.maggie.app.data.model

import java.io.Serializable
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/** What the Revue's list asks for at most: the API's cap, the whole period in one read. */
const val REVIEW_LIST_LIMIT = 1000

/** The state pills of the Revue (MAG-539): the values are the API's `state`, the labels the owner's words. */
enum class ReviewState(val value: String, val label: String) {
    ALL("all", "Toutes"),
    UNRECOGNISED("unrecognised", "À qualifier"),
    RECOGNISED("recognised", "Reconnues"),
}

/** The dates the Revue covers: the values are the API's `period`. [MONTH] is read with a month. */
enum class ReviewPeriod(val value: String, val label: String) {
    MONTH("month", "Mois"),
    SINCE_REVIEW("since_review", "Depuis la dernière revue"),
    ALL("all", "Toutes les dates"),
}

/**
 * Where the Revue opens: its state pill, its period and, for a month, which ([month] `YYYY-MM`, null for the current one).
 * Handed from the entry point (the summary, a deep link, a todo pill) to the list, which owns the filters afterwards.
 */
data class ReviewPreset(
    val state: ReviewState = ReviewState.ALL,
    val period: ReviewPeriod = ReviewPeriod.MONTH,
    val month: String? = null,
) : Serializable {
    companion object {
        /** `maggie://finance/to-sort`, the « À qualifier » pills: everything still to qualify, whatever its date. */
        val TO_SORT = ReviewPreset(ReviewState.UNRECOGNISED, ReviewPeriod.ALL)

        /** `maggie://finance/to-control`: what an operation recognised since the last review. */
        val TO_CONTROL = ReviewPreset(ReviewState.RECOGNISED, ReviewPeriod.SINCE_REVIEW)

        /** `maggie://finance/review`: the whole of this month. */
        val MONTHLY = ReviewPreset(ReviewState.ALL, ReviewPeriod.MONTH)

        /** The Finance summary's « Revue » block: everything since the last review, the period its counts are read on. */
        val SUMMARY = ReviewPreset(ReviewState.ALL, ReviewPeriod.SINCE_REVIEW)
    }
}

/** `YYYY-MM` of [date]. */
fun reviewMonthOf(date: LocalDate): String = YearMonth.from(date).toString()

/** The month [delta] months from [month] (`YYYY-MM`); [month] itself when it does not parse. */
fun shiftReviewMonth(month: String, delta: Int): String =
    runCatching { YearMonth.parse(month).plusMonths(delta.toLong()).toString() }.getOrDefault(month)

/** « Octobre 2026 », the way the month selector reads a `YYYY-MM`. */
fun reviewMonthLabel(month: String): String {
    val parsed = runCatching { YearMonth.parse(month) }.getOrNull() ?: return month
    return "${monthLabel(parsed.monthValue)} ${parsed.year}"
}

/** « Toutes · 12 », « À qualifier · 2 », « Reconnues · 10 ». */
fun reviewPillLabel(state: ReviewState, count: Int): String = "${state.label} · $count"

/** Whether a line belongs to [state]'s pill. */
fun ReviewQueueItem.isIn(state: ReviewState): Boolean = when (state) {
    ReviewState.ALL -> true
    ReviewState.UNRECOGNISED -> !recognised
    ReviewState.RECOGNISED -> recognised
}

/** The pile in the order the list shows it: the lines to qualify, then the recognised, each by ascending date (stable). */
fun List<ReviewQueueItem>.inReviewOrder(): List<ReviewQueueItem> =
    sortedWith(compareBy<ReviewQueueItem> { it.recognised }.thenBy { it.bookedAt.orEmpty() })

/** The period's counts and weight of the lines to qualify, as the pills read them. */
data class ReviewCounts(
    val unrecognised: Int = 0,
    val unrecognisedCents: Int = 0,
    val recognised: Int = 0,
) {
    val all: Int get() = unrecognised + recognised

    fun of(state: ReviewState): Int = when (state) {
        ReviewState.ALL -> all
        ReviewState.UNRECOGNISED -> unrecognised
        ReviewState.RECOGNISED -> recognised
    }

    /** A line to qualify of [cents] was filed: it is now a recognised line. */
    fun unrecognisedBecameRecognised(cents: Int): ReviewCounts = copy(
        unrecognised = (unrecognised - 1).coerceAtLeast(0),
        unrecognisedCents = (unrecognisedCents - abs(cents)).coerceAtLeast(0),
        recognised = recognised + 1,
    )

    /** A line sits in the recognised pile that the read did not count: a gesture of this session put it there. */
    fun withRecognised(extra: Int): ReviewCounts = copy(recognised = recognised + extra)
}

/** The counts the API gave for a period. */
fun ReviewQueue.counts(): ReviewCounts = ReviewCounts(unrecognisedCount, unrecognisedCents, recognisedCount)
