package com.maggie.app.ui.screens.shared

import com.maggie.app.data.model.ExpandedEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * only the dates count, and [end] is the last day, as the API has it (MAG-382).
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

    /**
     * Switching to all-day keeps the days (a 19:00–00:00 event is one day, not two);
     * switching back never leaves a zero-length event.
     */
    fun withAllDay(value: Boolean): EventFormState {
        if (value == allDay) return this
        if (value) {
            val lastDay = if (endTime == LocalTime.MIDNIGHT && endDate.isAfter(startDate)) endDate.minusDays(1) else endDate
            return copy(allDay = true, end = LocalDateTime.of(lastDay, endTime))
        }
        if (end.isAfter(start)) return copy(allDay = false)
        val newStart = LocalDateTime.of(startDate, LocalTime.of(9, 0))
        return copy(allDay = false, start = newStart, end = newStart.plusHours(1))
    }

    private fun withStart(newStart: LocalDateTime) =
        copy(start = newStart, end = newStart.plus(Duration.between(start, end)))

    /** The API's `startAt`, an instant in the event's zone; null for an all-day event. */
    fun startAt(zone: ZoneId): String? = if (allDay) null else start.atZone(zone).toInstant().toString()

    /** The API's `endAt`; null for an all-day event. */
    fun endAt(zone: ZoneId): String? = if (allDay) null else end.atZone(zone).toInstant().toString()

    /** The API's `startDate` (`YYYY-MM-DD`) of an all-day event; null for a timed one. */
    val allDayStartDate: String? get() = if (allDay) startDate.toString() else null

    /** The API's `endDate`, the last day included; null for a timed one. */
    val allDayEndDate: String? get() = if (allDay) endDate.toString() else null

    /**
     * The dates of a PATCH: both pairs, the unused one as an explicit null, so an event
     * switching between all-day and timed loses the bounds it no longer has.
     */
    fun patch(zone: ZoneId): JsonObject = buildJsonObject {
        put("allDay", allDay)
        put("startAt", startAt(zone))
        put("endAt", endAt(zone))
        put("startDate", allDayStartDate)
        put("endDate", allDayEndDate)
    }

    companion object {
        /** A new event on [date]: 09:00–10:00. */
        fun forNewEvent(date: LocalDate) = EventFormState(
            allDay = false,
            start = LocalDateTime.of(date, LocalTime.of(9, 0)),
            end = LocalDateTime.of(date, LocalTime.of(10, 0)),
        )

        fun fromEvent(event: ExpandedEvent): EventFormState {
            if (event.allDay && event.startDate != null) {
                val last = event.endDate ?: event.startDate
                return EventFormState(
                    allDay = true,
                    start = event.startDate.atStartOfDay(),
                    end = last.atStartOfDay(),
                )
            }
            val zone = ZoneId.of(event.timeZone)
            val start = LocalDateTime.ofInstant(Instant.parse(event.startAt), zone)
            val end = event.endAt?.let { LocalDateTime.ofInstant(Instant.parse(it), zone) } ?: start
            return EventFormState(
                allDay = event.allDay,
                start = start,
                // An all-day event served as instants (before MAG-382) ended at the next midnight.
                end = if (event.allDay) end.minusDays(1) else end,
            )
        }
    }
}
