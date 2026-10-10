package com.maggie.app.util

import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.occurrenceKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.logging.Level
import java.util.logging.Logger

object EventExpander {
    private val logger = Logger.getLogger("EventExpander")

    private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

    /**
     * The event as stored, not one of its occurrences: a master keeps its own start, an exception
     * instance carries its master's rule. An instance whose master is unknown is shown as a
     * standalone event, so the recurrence dialogs never get a rule they cannot edit.
     */
    fun single(event: Event, master: Event?, agendaMap: Map<String, Agenda>): ExpandedEvent {
        val agendaIri = event.agenda ?: master?.agenda
        val agenda = agendaIri?.let { agendaMap[it.removePrefix("/api/agendas/")] }
        val isException = event.recurringEvent != null && master != null
        return expanded(event, agendaIri, agenda).copy(
            masterEventId = if (isException) master?.id else event.id.takeIf { event.rrule != null },
            masterRrule = if (isException) master?.rrule else event.rrule,
            masterStartAt = if (isException) master?.startKey else event.startKey.takeIf { event.rrule != null },
            originalStartAt = if (isException) event.originalStartAt else null,
        )
    }

    /**
     * Expand events (including RRULE recurring) for a given range.
     * Applies exception instances (cancelled = skip, modified = replace).
     * Returns sorted list (all-day first, then by start).
     *
     * An all-day event is matched by its dates against the days of the range in [zone]
     * — the zone the range was cut in — and a series of all-day events is expanded on
     * dates, never on instants (MAG-382).
     */
    fun expandForRange(
        events: List<Event>,
        rangeStart: Instant,
        rangeEnd: Instant,
        agendaMap: Map<String, Agenda>,
        zone: ZoneId = PARIS,
    ): List<ExpandedEvent> {
        val result = mutableListOf<ExpandedEvent>()
        val firstDay = rangeStart.atZone(zone).toLocalDate()
        val lastDay = rangeEnd.minusNanos(1).atZone(zone).toLocalDate()

        // Build exception map: masterIRI -> originalStartAt(epoch ms) -> exception event
        val exceptionMap = mutableMapOf<String, MutableMap<Long, Event>>()
        for (e in events) {
            val master = e.recurringEvent ?: continue
            val orig = e.originalStartAt ?: continue
            val origMs = Instant.parse(orig).toEpochMilli()
            exceptionMap.getOrPut(master) { mutableMapOf() }[origMs] = e
        }

        for (e in events) {
            // Skip exception instances — handled during master expansion
            if (e.recurringEvent != null) continue

            val agenda = e.agenda?.let { iri ->
                val id = iri.removePrefix("/api/agendas/")
                agendaMap[id]
            }
            val dated = e.allDay && e.firstDate != null

            // One unreadable rule must never take the whole calendar down: that event is shown
            // once, at its start, and flagged.
            val occurrences: List<Occurrence>? = if (e.rrule != null) {
                try {
                    if (dated) {
                        dateOccurrences(e, firstDay, lastDay)
                    } else {
                        timedOccurrences(e, rangeStart, rangeEnd)
                    }
                } catch (ex: Exception) {
                    logger.log(Level.WARNING, "recurrence_unreadable eventId=${e.id} rrule=${e.rrule}", ex)
                    null
                }
            } else {
                null
            }

            if (e.rrule != null && occurrences != null) {
                val exceptions = exceptionMap["/api/events/${e.id}"]

                for (occ in occurrences) {
                    val exception = exceptions?.get(Instant.parse(occ.key).toEpochMilli())

                    if (exception != null) {
                        if (exception.status == "cancelled") continue
                        result.add(
                            expanded(exception, e.agenda, agenda).copy(
                                masterEventId = e.id,
                                masterRrule = e.rrule,
                                masterStartAt = e.startKey,
                                originalStartAt = exception.originalStartAt,
                            ),
                        )
                    } else {
                        result.add(
                            expanded(e, e.agenda, agenda).copy(
                                id = "${e.id}__${occ.idDate}",
                                startAt = occ.startAt,
                                endAt = occ.endAt,
                                startDate = occ.startDate,
                                endDate = occ.endDate,
                                isVirtualOccurrence = true,
                                masterEventId = e.id,
                                masterRrule = e.rrule,
                                masterStartAt = e.startKey,
                                originalStartAt = occ.key,
                            ),
                        )
                    }
                }
            } else {
                // Non-recurring: include if event overlaps the range
                val overlaps = if (dated) {
                    e.firstDate!! <= lastDay && e.lastDate!! >= firstDay
                } else if (e.startAt != null && e.endAt != null) {
                    Instant.parse(e.startAt) < rangeEnd && Instant.parse(e.endAt) > rangeStart
                } else {
                    false
                }
                if (overlaps) {
                    result.add(expanded(e, e.agenda, agenda).copy(recurrenceUnreadable = e.rrule != null))
                }
            }
        }

        result.sortWith(compareBy<ExpandedEvent> { !it.allDay }.thenBy { it.sortKey })
        return result
    }

    /** One occurrence of a series: its key for the exceptions, and its bounds. */
    private data class Occurrence(
        val key: String,
        val idDate: String,
        val startAt: String? = null,
        val endAt: String? = null,
        val startDate: LocalDate? = null,
        val endDate: LocalDate? = null,
    )

    private fun timedOccurrences(e: Event, rangeStart: Instant, rangeEnd: Instant): List<Occurrence> {
        val dtstart = Instant.parse(e.startAt)
        val durationMs = Instant.parse(e.endAt).toEpochMilli() - dtstart.toEpochMilli()
        return RruleUtils.expandRrule(e.rrule!!, dtstart, rangeStart, rangeEnd, e.timeZone).map { occ ->
            Occurrence(
                key = occ.toString(),
                idDate = occ.toString().substring(0, 10),
                startAt = occ.toString(),
                endAt = Instant.ofEpochMilli(occ.toEpochMilli() + durationMs).toString(),
            )
        }
    }

    /**
     * Occurrence k starts and ends k days (or weeks, months…) after the first, both dates
     * moved by as many days. An occurrence that began before the range but still covers
     * its first day is kept.
     */
    private fun dateOccurrences(e: Event, firstDay: LocalDate, lastDay: LocalDate): List<Occurrence> {
        val first = e.firstDate!!
        val lengthDays = ChronoUnit.DAYS.between(first, maxOf(first, e.lastDate!!))
        return RruleUtils.expandRruleDates(e.rrule!!, first, firstDay.minusDays(lengthDays), lastDay).map { start ->
            Occurrence(
                key = occurrenceKey(start),
                idDate = start.toString(),
                startDate = start,
                endDate = start.plusDays(lengthDays),
            )
        }
    }

    private fun expanded(e: Event, agendaIri: String?, agenda: Agenda?) = ExpandedEvent(
        id = e.id,
        summary = e.summary,
        description = e.description,
        location = e.location,
        allDay = e.allDay,
        startAt = e.startAt.takeUnless { e.allDay && e.firstDate != null },
        endAt = e.endAt.takeUnless { e.allDay && e.firstDate != null },
        startDate = e.firstDate.takeIf { e.allDay },
        endDate = e.lastDate.takeIf { e.allDay },
        timeZone = e.timeZone,
        status = e.status,
        reminders = e.reminders,
        agendaIri = agendaIri,
        agendaColor = agenda?.color,
        agendaName = agenda?.name,
    )
}
