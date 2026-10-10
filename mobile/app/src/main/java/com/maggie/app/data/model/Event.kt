package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * An event as the API serves it (MAG-382): a timed event has [startAt]/[endAt]
 * (instants), an all-day one has [startDate]/[endDate] (`YYYY-MM-DD`) and no
 * instant at all — a day is a date, never shifted by a zone. As in Google's
 * `start.date`/`end.date`, [endDate] is **exclusive**: a 1 January event ends on
 * 2 January. The screens show and take the last day included; [lastDayOf] and
 * [endDateAfter] are the only crossing between the two.
 */
@Serializable
data class Event(
    val id: String,
    val summary: String,
    val description: String? = null,
    val location: String? = null,
    val allDay: Boolean = false,
    val startAt: String? = null,
    val endAt: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val timeZone: String = "Europe/Paris",
    val status: String = "confirmed",
    val rrule: String? = null,
    val recurringEvent: String? = null,
    val originalStartAt: String? = null,
    val reminders: EventReminders? = null,
    val agenda: String? = null,
) {
    val firstDate: LocalDate? get() = startDate?.let(LocalDate::parse)
    /** The exclusive end date; one day after the start when the API left it out. */
    val endDateExclusive: LocalDate? get() = endDate?.let(LocalDate::parse) ?: firstDate?.let(::endDateAfter)

    /** What names this event's start for its exceptions: its instant, or its date as an [occurrenceKey]. */
    val startKey: String? get() = startAt ?: firstDate?.let(::occurrenceKey)
}

/** The last day an all-day event covers, as the screens show it: the day before its exclusive [endDate]. */
fun lastDayOf(endDate: LocalDate): LocalDate = endDate.minusDays(1)

/** The API's exclusive end date of an all-day event whose last day shown is [lastDay]. */
fun endDateAfter(lastDay: LocalDate): LocalDate = lastDay.plusDays(1)
