package com.maggie.app.util

import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.ExpandedEvent
import java.time.Instant

object EventExpander {

    /**
     * The event as stored, not one of its occurrences: a master keeps its own start, an exception
     * instance carries its master's rule. An instance whose master is unknown is shown as a
     * standalone event, so the recurrence dialogs never get a rule they cannot edit.
     */
    fun single(event: Event, master: Event?, agendaMap: Map<String, Agenda>): ExpandedEvent {
        val agendaIri = event.agenda ?: master?.agenda
        val agenda = agendaIri?.let { agendaMap[it.removePrefix("/api/agendas/")] }
        val isException = event.recurringEvent != null && master != null
        return ExpandedEvent(
            id = event.id,
            summary = event.summary,
            description = event.description,
            location = event.location,
            allDay = event.allDay,
            startAt = event.startAt,
            endAt = event.endAt,
            timeZone = event.timeZone,
            status = event.status,
            masterEventId = if (isException) master?.id else event.id.takeIf { event.rrule != null },
            masterRrule = if (isException) master?.rrule else event.rrule,
            masterStartAt = if (isException) master?.startAt else event.startAt.takeIf { event.rrule != null },
            originalStartAt = if (isException) event.originalStartAt else null,
            agendaIri = agendaIri,
            agendaColor = agenda?.color,
            agendaName = agenda?.name,
        )
    }

    /**
     * Expand events (including RRULE recurring) for a given range.
     * Applies exception instances (cancelled = skip, modified = replace).
     * Returns sorted list (all-day first, then by startAt).
     */
    fun expandForRange(
        events: List<Event>,
        rangeStart: Instant,
        rangeEnd: Instant,
        agendaMap: Map<String, Agenda>,
    ): List<ExpandedEvent> {
        val result = mutableListOf<ExpandedEvent>()

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

            if (e.rrule != null) {
                val dtstart = Instant.parse(e.startAt)
                val durationMs = Instant.parse(e.endAt).toEpochMilli() - dtstart.toEpochMilli()
                val occurrences = RruleUtils.expandRrule(e.rrule, dtstart, rangeStart, rangeEnd)
                val eventIri = "/api/events/${e.id}"
                val exceptions = exceptionMap[eventIri]

                for (occ in occurrences) {
                    val occMs = occ.toEpochMilli()
                    val exception = exceptions?.get(occMs)

                    if (exception != null) {
                        if (exception.status == "cancelled") continue
                        result.add(
                            ExpandedEvent(
                                id = exception.id,
                                summary = exception.summary,
                                description = exception.description,
                                location = exception.location,
                                allDay = exception.allDay,
                                startAt = exception.startAt,
                                endAt = exception.endAt,
                                timeZone = exception.timeZone,
                                status = exception.status,
                                isVirtualOccurrence = false,
                                masterEventId = e.id,
                                masterRrule = e.rrule,
                                masterStartAt = e.startAt,
                                originalStartAt = exception.originalStartAt,
                                agendaIri = e.agenda,
                                agendaColor = agenda?.color,
                                agendaName = agenda?.name,
                            ),
                        )
                    } else {
                        val occEnd = Instant.ofEpochMilli(occMs + durationMs)
                        val occDate = occ.toString().substring(0, 10)
                        result.add(
                            ExpandedEvent(
                                id = "${e.id}__$occDate",
                                summary = e.summary,
                                description = e.description,
                                location = e.location,
                                allDay = e.allDay,
                                startAt = occ.toString(),
                                endAt = occEnd.toString(),
                                timeZone = e.timeZone,
                                status = e.status,
                                isVirtualOccurrence = true,
                                masterEventId = e.id,
                                masterRrule = e.rrule,
                                masterStartAt = e.startAt,
                                originalStartAt = occ.toString(),
                                agendaIri = e.agenda,
                                agendaColor = agenda?.color,
                                agendaName = agenda?.name,
                            ),
                        )
                    }
                }
            } else {
                // Non-recurring: include if event overlaps the range
                val eventStart = Instant.parse(e.startAt)
                val eventEnd = Instant.parse(e.endAt)
                if (eventStart < rangeEnd && eventEnd > rangeStart) {
                    result.add(
                        ExpandedEvent(
                            id = e.id,
                            summary = e.summary,
                            description = e.description,
                            location = e.location,
                            allDay = e.allDay,
                            startAt = e.startAt,
                            endAt = e.endAt,
                            timeZone = e.timeZone,
                            status = e.status,
                            agendaIri = e.agenda,
                            agendaColor = agenda?.color,
                            agendaName = agenda?.name,
                        ),
                    )
                }
            }
        }

        result.sortWith(compareBy<ExpandedEvent> { !it.allDay }.thenBy { it.startAt })
        return result
    }
}
