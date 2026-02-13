package com.maggie.app.util

import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.ExpandedEvent
import java.time.Instant

object EventExpander {

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
                                originalStartAt = occ.toString(),
                                agendaIri = e.agenda,
                                agendaColor = agenda?.color,
                                agendaName = agenda?.name,
                            ),
                        )
                    }
                }
            } else {
                // Non-recurring: include if startAt falls within range
                val eventStart = Instant.parse(e.startAt)
                if (eventStart >= rangeStart && eventStart < rangeEnd) {
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
