package com.maggie.app.ui.screens.fullcalendar

import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.Trip
import java.time.Instant

/** One row of the day's list (MAG-859): an event, or the trip that leads to one, at its departure. */
sealed interface DayRow {
    val at: Instant

    /** [highlighted]: the first event not yet started, whose time and title are Medium. */
    data class Event(val event: ExpandedEvent, val past: Boolean, val highlighted: Boolean) : DayRow {
        override val at: Instant get() = Instant.parse(event.startAt)
    }

    /** [next]: the first departure still to come, drawn as a card. */
    data class Trip(val trip: com.maggie.app.data.model.Trip, val event: ExpandedEvent?, val past: Boolean, val next: Boolean) : DayRow {
        override val at: Instant get() = trip.departInstant ?: Instant.EPOCH
    }
}

/**
 * The timed [events] and the [trips] of the day in one list, by time; a trip comes before its event on a tie.
 * A trip is a moment of the day: it sits at its departure, never under its event.
 */
fun dayRows(events: List<ExpandedEvent>, trips: List<Trip>, now: Instant): List<DayRow> {
    val byId = events.associateBy { it.id }
    val eventRows = events.filter { it.startAt != null }.map { event ->
        val end = (event.endAt ?: event.startAt)?.let(Instant::parse)
        DayRow.Event(event, past = end?.isAfter(now) == false, highlighted = false)
    }
    val tripRows = trips.filter { it.eventId in byId && it.departInstant != null }
        .map { DayRow.Trip(it, byId[it.eventId], past = it.departInstant?.isAfter(now) == false, next = false) }

    val sorted = (eventRows + tripRows).sortedWith(
        compareBy<DayRow> { it.at }.thenBy { if (it is DayRow.Trip) 0 else 1 },
    )
    val nextTrip = sorted.firstOrNull { it is DayRow.Trip && !it.past }
    val firstToStart = sorted.firstOrNull { it is DayRow.Event && it.at.isAfter(now) }
    return sorted.map { row ->
        when {
            row === nextTrip && row is DayRow.Trip -> row.copy(next = true)
            row === firstToStart && row is DayRow.Event -> row.copy(highlighted = true)
            else -> row
        }
    }
}
