package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.HealthDay
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How many days « Jours précédents » reads back, today the last (MAG-750). */
const val JOURNAL_HISTORY_DAYS = 30

private val WEEKDAY_NAMES = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
private val SHORT_MONTHS = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/** `samedi 7 nov.` */
fun journalDate(date: LocalDate): String =
    "${WEEKDAY_NAMES[date.dayOfWeek.value - 1]} ${date.dayOfMonth} ${SHORT_MONTHS[date.monthValue - 1]}"

/** The header of the day chosen: « Aujourd’hui samedi 7 nov. », « Hier vendredi 6 nov. », else « Jeudi 5 nov. ». */
fun journalDayTitle(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Aujourd’hui ${journalDate(date)}"
    today.minusDays(1) -> "Hier ${journalDate(date)}"
    else -> journalDate(date).replaceFirstChar { it.uppercase() }
}

/** `21:42` in [zone], or `null` when the API sent no parsable time. */
fun journalTime(iso: String?, zone: ZoneId): String? =
    iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone).format(CLOCK) }.getOrNull() }

/** « avec Maggie · 21:42 » when Maggie noted the day, `null` when the owner did or nobody has. */
fun notedByMaggie(day: HealthDay?, zone: ZoneId): String? {
    if (day?.notedBy != HealthDay.NOTED_BY_MAGGIE) return null
    return listOfNotNull("avec Maggie", journalTime(day.notedAt, zone)).joinToString(" · ")
}

/** « M 7 · F 4 », what a past day's row says of its scales; an unset one is a dash. */
fun journalScales(day: HealthDay): String = "M ${day.mood ?: DASH} · F ${day.fatigue ?: DASH}"

/** `71,6 kg` and the time of the first weighing, `71,6 kg · 07:12`; a dash without one. */
fun journalWeight(day: HealthDay?, zone: ZoneId): String {
    val kg = day?.weightKg ?: return DASH
    return listOfNotNull("${formatKg(kg)} kg", journalTime(day.weighedAt, zone)).joinToString(" · ")
}

/** `45 min`, or a dash. */
fun journalSport(minutes: Int?): String = minutes?.let { "$it min" } ?: DASH

/** The past days of « Jours précédents », newest first: the journal's days before [today]. */
fun journalHistory(days: List<HealthDay>, today: LocalDate): List<HealthDay> =
    days.filter { it.date < today.toString() }.sortedByDescending { it.date }

/** The days of [period] ending [today], one per day, oldest first: a day the journal does not hold has no mood. */
fun moodWindow(days: List<HealthDay>, period: SuiviPeriod, today: LocalDate): List<HealthDay> {
    val byDate = days.associateBy { it.date }
    val start = energyStart(period, today)
    return (0 until period.dayCount).map { offset ->
        val date = start.plusDays(offset.toLong()).toString()
        byDate[date] ?: HealthDay(date = date)
    }
}

/** Without a mood or a fatigue in the window there is nothing to draw: the card says so, with no line. */
fun hasScales(window: List<HealthDay>): Boolean = window.any { it.mood != null || it.fatigue != null }
