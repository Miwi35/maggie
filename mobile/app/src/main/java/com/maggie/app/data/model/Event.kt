package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * An event as the API serves it (MAG-382): a timed event has [startAt]/[endAt]
 * (instants), an all-day one has [startDate]/[endDate] (`YYYY-MM-DD`, the end
 * included) and no instant at all — a day is a date, never shifted by a zone.
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
    val lastDate: LocalDate? get() = endDate?.let(LocalDate::parse) ?: firstDate

    /** What names this event's start for its exceptions: its instant, or its date as an [occurrenceKey]. */
    val startKey: String? get() = startAt ?: firstDate?.let(::occurrenceKey)
}
