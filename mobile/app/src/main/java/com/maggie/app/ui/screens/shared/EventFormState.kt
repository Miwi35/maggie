package com.maggie.app.ui.screens.shared

import com.maggie.app.data.model.ExpandedEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The dates of the event form: a start and an end, never a typed duration.
 * Moving the start moves the end by as much, so the duration the owner set
 * survives (19:00–00:00 stays five hours on another day). For an all-day event
 * [end] is the last day shown, the form's side of the API's exclusive end.
 */
data class EventFormState(
    val allDay: Boolean,
    val start: LocalDateTime,
    val end: LocalDateTime,
) {
    val startDate: LocalDate get() = start.toLocalDate()
    val startTime: LocalTime get() = start.toLocalTime()
    val endDate: LocalDate get() = end.toLocalDate()
    val endTime: LocalTime get() = end.toLocalTime()

    /** An event cannot end before it starts; an all-day one may end on its first day. */
    val endsBeforeStart: Boolean
        get() = if (allDay) endDate.isBefore(startDate) else end.isBefore(start)

    fun withStartDate(date: LocalDate) = withStart(LocalDateTime.of(date, startTime))

    fun withStartTime(time: LocalTime) = withStart(LocalDateTime.of(startDate, time))

    fun withEndDate(date: LocalDate) = copy(end = LocalDateTime.of(date, endTime))

    fun withEndTime(time: LocalTime) = copy(end = LocalDateTime.of(endDate, time))

    fun withAllDay(value: Boolean) = copy(allDay = value)

    private fun withStart(newStart: LocalDateTime) =
        copy(start = newStart, end = newStart.plus(Duration.between(start, end)))

    /** The API's `startAt`, an instant in the event's zone. */
    fun startAt(zone: ZoneId): String =
        (if (allDay) startDate.atStartOfDay(zone) else start.atZone(zone)).toInstant().toString()

    /** The API's `endAt`: the day after the last one for an all-day event. */
    fun endAt(zone: ZoneId): String =
        (if (allDay) endDate.plusDays(1).atStartOfDay(zone) else end.atZone(zone)).toInstant().toString()

    companion object {
        /** A new event on [date]: 09:00–10:00. */
        fun forNewEvent(date: LocalDate) = EventFormState(
            allDay = false,
            start = LocalDateTime.of(date, LocalTime.of(9, 0)),
            end = LocalDateTime.of(date, LocalTime.of(10, 0)),
        )

        fun fromEvent(event: ExpandedEvent): EventFormState {
            val zone = ZoneId.of(event.timeZone)
            val start = LocalDateTime.ofInstant(Instant.parse(event.startAt), zone)
            val end = LocalDateTime.ofInstant(Instant.parse(event.endAt), zone)
            return EventFormState(
                allDay = event.allDay,
                start = start,
                // The API ends an all-day event at the next midnight; the form shows its last day.
                end = if (event.allDay) end.minusDays(1) else end,
            )
        }
    }
}
