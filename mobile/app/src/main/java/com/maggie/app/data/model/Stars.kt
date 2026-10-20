package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.YearMonth

/**
 * The constellation of a month (MAG-509), `GET /api/stars?month=YYYY-MM`: one star per task done,
 * counted from the rewards. The totals only go up — a task reopened takes nothing back.
 *
 * - `month`: `YYYY-MM`, the user's own;
 * - `count`: the stars of that month; `total`: all of them, since always;
 * - `best`: the best month before this one, `null` while there is none;
 * - `latestAt`: when the month's latest star was earned, `null` when it has none.
 */
@Serializable
data class Stars(
    val month: String,
    val count: Int,
    val total: Int,
    val best: BestMonth? = null,
    val latestAt: String? = null,
) {
    @Serializable
    data class BestMonth(val month: String, val count: Int)

    /** « Ta constellation d'octobre · 5 étoiles ». */
    val title: String get() = "Ta constellation ${monthOf(month)} · ${starsLabelOf(count)}"

    /** Ten to a row, a hundred at most: past that, the last hundred and « +n ». */
    val drawn: Int get() = minOf(count, MAX_DRAWN)

    val hidden: Int get() = count - drawn

    /** The month before this one, as the API spells it. */
    val previousMonth: String get() = YearMonth.parse(month).minusMonths(1).toString()

    /** The month after this one, as the API spells it. */
    val nextMonth: String get() = YearMonth.parse(month).plusMonths(1).toString()

    companion object {
        const val ROW = 10
        const val MAX_DRAWN = 100

        private val NAMES = listOf(
            "janvier", "février", "mars", "avril", "mai", "juin",
            "juillet", "août", "septembre", "octobre", "novembre", "décembre",
        )

        /** « d'octobre », « de novembre »: the month as the title of its constellation reads it. */
        fun monthOf(month: String): String {
            val name = NAMES[YearMonth.parse(month).monthValue - 1]
            return if (name.first() in "aeiouéh") "d'$name" else "de $name"
        }

        /** « 1 étoile », « 12 étoiles ». */
        fun starsLabelOf(count: Int): String = "$count étoile" + if (count > 1) "s" else ""
    }
}
