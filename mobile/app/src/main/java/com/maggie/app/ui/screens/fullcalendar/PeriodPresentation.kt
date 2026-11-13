package com.maggie.app.ui.screens.fullcalendar

import com.maggie.app.data.model.Period
import com.maggie.app.util.RruleUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PeriodTab(val key: String, val label: String) {
    UPCOMING("upcoming", "À venir"),
    PAST("past", "Passées"),
}

/** A block of the list: « En cours », or a month. */
data class PeriodGroup(val title: String, val periods: List<Period>)

private val FRENCH = Locale.FRENCH
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", FRENCH)
private val WEEKDAY_DAY_MONTH = DateTimeFormatter.ofPattern("EEE d MMM", FRENCH)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", FRENCH)
private val MONTH_YEAR = DateTimeFormatter.ofPattern("LLLL yyyy", FRENCH)

private fun LocalDate.dayMonth(): String = if (dayOfMonth == 1) "1er ${format(DateTimeFormatter.ofPattern("MMM", FRENCH))}" else format(DAY_MONTH)

private fun LocalDate.weekdayDayMonth(): String =
    if (dayOfMonth == 1) "${format(DateTimeFormatter.ofPattern("EEE", FRENCH))} 1er ${format(DateTimeFormatter.ofPattern("MMM", FRENCH))}" else format(WEEKDAY_DAY_MONTH)

/** « une semaine sur deux », « tous les jours »: the repetition in a row of the list; null when the period does not repeat. */
fun recurrenceLabel(rrule: String?): String? {
    if (rrule == null) return null
    val parts = rrule.split(";").associate { it.substringBefore("=") to it.substringAfter("=", "") }
    val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
    if (parts["FREQ"] == "WEEKLY" && interval == 2 && parts["BYDAY"] == null) return "une semaine sur deux"
    return RruleUtils.rruleToFrenchText(rrule).replaceFirstChar { it.lowercase() }
}

/**
 * « ven. 23 oct. 18:00 → ven. 30 oct. 18:00 · une semaine sur deux », or « 18 oct. → 1er nov. » for days. The end
 * of a day-long period is shown included.
 */
fun periodDatesLabel(period: Period): String {
    val zone = period.zone
    val range = if (period.allDay) {
        val first = period.firstDay
        val last = period.lastDay
        when {
            first == null -> ""
            last == null || last == first -> first.dayMonth()
            else -> "${first.dayMonth()} → ${last.dayMonth()}"
        }
    } else {
        val start = period.startInstant?.atZone(zone)
        val end = period.endInstant?.atZone(zone)
        when {
            start == null -> ""
            end == null -> "${start.toLocalDate().weekdayDayMonth()} ${start.format(TIME)}"
            else -> "${start.toLocalDate().weekdayDayMonth()} ${start.format(TIME)} → ${end.toLocalDate().weekdayDayMonth()} ${end.format(TIME)}"
        }
    }
    return listOfNotNull(range.ifEmpty { null }, recurrenceLabel(period.rrule)).joinToString(" · ")
}

/** « jusqu'au ven. 18:00 » on a chip of the day and week views, « jusqu'au 1er nov. » for a period of days. */
fun periodUntilLabel(period: Period): String {
    val zone = period.zone
    if (period.allDay) return period.lastDay?.let { "jusqu'au ${it.dayMonth()}" } ?: ""
    val end = period.endInstant?.atZone(zone) ?: return ""
    val weekday = end.format(DateTimeFormatter.ofPattern("EEE", FRENCH))
    return "jusqu'au $weekday ${end.format(TIME)}"
}

private fun monthTitle(day: LocalDate): String = day.format(MONTH_YEAR).replaceFirstChar { it.uppercase() }

/**
 * The rows of a tab: « À venir » is the period in progress (« En cours ») then the coming ones by month; « Passées »
 * the ended ones, the latest first. A series is one row, and one that never ends is never past.
 */
fun periodGroups(periods: List<Period>, tab: PeriodTab, now: Instant, zone: ZoneId): List<PeriodGroup> {
    fun startOf(period: Period): Instant = period.startsAt() ?: Instant.EPOCH
    fun monthOf(period: Period): LocalDate = (period.firstDay ?: startOf(period).atZone(zone).toLocalDate()).withDayOfMonth(1)
    return when (tab) {
        PeriodTab.UPCOMING -> {
            val live = periods.filterNot { it.hasEnded(now) }.sortedBy(::startOf)
            val ongoing = live.filter { it.isOngoing(now) }
            val coming = live.filterNot { it.isOngoing(now) }
            buildList {
                if (ongoing.isNotEmpty()) add(PeriodGroup("En cours", ongoing))
                coming.groupBy(::monthOf).forEach { (month, rows) -> add(PeriodGroup(monthTitle(month), rows)) }
            }
        }
        PeriodTab.PAST -> periods.filter { it.hasEnded(now) }
            .sortedByDescending(::startOf)
            .groupBy(::monthOf)
            .map { (month, rows) -> PeriodGroup(monthTitle(month), rows) }
    }
}
