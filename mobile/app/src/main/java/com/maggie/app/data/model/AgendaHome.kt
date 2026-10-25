package com.maggie.app.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The events of one day for the Accueil's Agenda card (MAG-562), whole-day ones first then by start. [date] is the day,
 * `YYYY-MM-DD`. The card is composed client-side from the events and agendas resources (MAG-589). The API says nothing of
 * past, in progress or next: [timeline] works that out from the clock, so the line moves without a new read.
 */
data class AgendaHomeState(
    val date: String,
    val events: List<AgendaHomeEvent> = emptyList(),
)

/** One event of the card. [locationShort] is the place without its address (« Cabinet Martin »). */
data class AgendaHomeEvent(
    val id: String,
    val summary: String,
    val description: String? = null,
    val location: String? = null,
    val locationShort: String? = null,
    val allDay: Boolean = false,
    val startAt: String? = null,
    val endAt: String? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val timeZone: String = "Europe/Paris",
    val status: String = "confirmed",
    val reminders: EventReminders? = null,
    val activity: String? = null,
    val agendaId: String? = null,
    val agendaName: String? = null,
    val agendaColor: String? = null,
) {
    val start: Instant? get() = startAt?.let { OffsetDateTime.parse(it).toInstant() }

    /** The end; an event without one ends when it starts. */
    val end: Instant? get() = endAt?.let { OffsetDateTime.parse(it).toInstant() } ?: start

    /** The event as the rest of the app reads it, to reuse its date label, reminders and review title. */
    fun toExpandedEvent(): ExpandedEvent = ExpandedEvent(
        id = id,
        summary = summary,
        description = description,
        location = location,
        allDay = allDay,
        startAt = startAt,
        endAt = endAt,
        startDate = startDate,
        endDate = endDate,
        timeZone = timeZone,
        status = status,
        reminders = reminders,
        agendaColor = agendaColor,
        agendaName = agendaName,
        activity = activity,
    )

    companion object {
        /**
         * An occurrence of the events resource, with the name and colour of its [agenda] (null when unknown: the
         * agenda list does not hold it). [Event.agenda] is the IRI `/api/agendas/{id}`.
         */
        fun from(event: Event, agenda: Agenda?): AgendaHomeEvent = AgendaHomeEvent(
            id = event.id,
            summary = event.summary,
            description = event.description,
            location = event.location,
            locationShort = shortLocation(event.location),
            allDay = event.allDay,
            startAt = event.startAt,
            endAt = event.endAt,
            startDate = event.firstDate,
            endDate = event.endDate?.let(LocalDate::parse),
            timeZone = event.timeZone,
            status = event.status,
            reminders = event.reminders,
            activity = event.activity,
            agendaId = event.agenda?.substringAfterLast('/'),
            agendaName = agenda?.name,
            agendaColor = agenda?.color,
        )

        /** « Cabinet Dr Martin, 15 rue de la Paix » gives « Cabinet Dr Martin »: the place, not the address. */
        fun shortLocation(location: String?): String? {
            if (location.isNullOrBlank()) return null
            return location.substringBefore(',').trim().ifEmpty { location.trim() }
        }

        /** Whole-day events first, then by start, then by title. */
        val ORDER: Comparator<AgendaHomeEvent> = compareBy<AgendaHomeEvent>({ !it.allDay }, { it.sortInstant() }, { it.summary })
    }

    private fun sortInstant(): Instant? =
        if (allDay) startDate?.atStartOfDay(ZoneId.of(timeZone))?.toInstant() else start

    /** The day the event began on in its own zone; null for a whole-day event, which has dates, not instants. */
    fun firstDay(): LocalDate? = start?.atZone(ZoneId.of(timeZone))?.toLocalDate()
}

/** How a row stands against the « maintenant » line. [PLAIN] is a row of a day that has no line (tomorrow). */
enum class AgendaRowKind { PAST, CURRENT, NEXT, LATER, PLAIN }

data class AgendaRow(val event: AgendaHomeEvent, val kind: AgendaRowKind)

/**
 * The card's rows: the whole-day block on top, then [past] (done, above the line), the line, then [following].
 * [hidden] counts the rows a phone leaves out; they are in the Agenda.
 */
data class AgendaTimeline(
    val allDay: List<AgendaRow>,
    val past: List<AgendaRow>,
    val following: List<AgendaRow>,
    val hasNowLine: Boolean,
    val hidden: Int = 0,
) {
    val isEmpty: Boolean get() = allDay.isEmpty() && past.isEmpty() && following.isEmpty()
}

/** At most this many done events above the line on a phone, and this many in progress or coming below it. */
const val PHONE_MAX_PAST = 2
const val PHONE_MAX_FOLLOWING = 4

/**
 * Sorts the day against [now]: an event over is [AgendaRowKind.PAST], one in progress is
 * [AgendaRowKind.CURRENT] (below the line: it is not done), the first to come is [AgendaRowKind.NEXT].
 * Without a clock (tomorrow) every row is [AgendaRowKind.PLAIN] and there is no line.
 * With [compact] (a phone) today keeps the [PHONE_MAX_PAST] last done events and the [PHONE_MAX_FOLLOWING] first others;
 * tomorrow, and the whole-day block, are never cut.
 */
fun AgendaHomeState.timeline(now: Instant?, compact: Boolean): AgendaTimeline {
    val allDay = events.filter { it.allDay }.map { AgendaRow(it, AgendaRowKind.PLAIN) }
    val timed = events.filterNot { it.allDay }
    if (now == null) {
        return AgendaTimeline(allDay, emptyList(), timed.map { AgendaRow(it, AgendaRowKind.PLAIN) }, hasNowLine = false)
    }

    val (over, rest) = timed.partition { (it.end ?: Instant.MAX) <= now }
    var nextTaken = false
    val following = rest.map { event ->
        val started = (event.start ?: Instant.MAX) <= now
        val kind = when {
            started -> AgendaRowKind.CURRENT
            !nextTaken -> AgendaRowKind.NEXT.also { nextTaken = true }
            else -> AgendaRowKind.LATER
        }
        AgendaRow(event, kind)
    }
    val past = over.map { AgendaRow(it, AgendaRowKind.PAST) }

    if (!compact) return AgendaTimeline(allDay, past, following, hasNowLine = true)

    val shownPast = past.takeLast(PHONE_MAX_PAST)
    val shownFollowing = following.take(PHONE_MAX_FOLLOWING)
    return AgendaTimeline(
        allDay,
        shownPast,
        shownFollowing,
        hasNowLine = true,
        hidden = (past.size - shownPast.size) + (following.size - shownFollowing.size),
    )
}
