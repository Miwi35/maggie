package com.maggie.app.data.model

import com.maggie.app.util.RruleUtils
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A period of the owner's life (MAG-809, MAG-815): the custody of his son, holidays, a trip. It holds no slot — it
 * says what kind of days these are — and its colour is its agenda's. The schedule is an event's: instants when
 * timed, days when [allDay], the end day **excluded** (shown included, see [lastDayOf]), and an RRULE. An occurrence
 * of an expanded series is a row with its own dates and the series' [id].
 */
@Serializable
data class Period(
    val id: String,
    val name: String,
    /** The agenda's IRI, `/api/agendas/{id}`. */
    val agenda: String? = null,
    val allDay: Boolean = false,
    val startAt: String? = null,
    val endAt: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val timeZone: String = "Europe/Paris",
    val rrule: String? = null,
) {
    val zone: ZoneId get() = runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneId.of("Europe/Paris"))

    val agendaId: String? get() = agenda?.substringAfterLast('/')

    val startInstant: Instant? get() = startAt?.let { runCatching { Instant.parse(it) }.getOrNull() }

    val endInstant: Instant? get() = endAt?.let { runCatching { Instant.parse(it) }.getOrNull() }

    val firstDay: LocalDate? get() =
        if (allDay) startDate?.let(LocalDate::parse) else startInstant?.atZone(zone)?.toLocalDate()

    /** The last day the period covers, as the screens show it: included. */
    val lastDay: LocalDate? get() {
        if (allDay) return endDate?.let { lastDayOf(LocalDate.parse(it)) } ?: firstDay
        val end = endInstant?.atZone(zone) ?: return firstDay
        val start = startInstant?.atZone(zone)
        // A period ending at midnight ends the evening before.
        return if (end.toLocalTime() == java.time.LocalTime.MIDNIGHT && start != null && end.isAfter(start)) end.toLocalDate().minusDays(1) else end.toLocalDate()
    }

    /** The instant the period starts: its start of day in [zone] when all-day. */
    fun startsAt(): Instant? = if (allDay) firstDay?.atStartOfDay(zone)?.toInstant() else startInstant

    /** The instant the period ends, exclusive. */
    fun endsAt(): Instant? =
        if (allDay) lastDay?.plusDays(1)?.atStartOfDay(zone)?.toInstant() else endInstant

    /** Whether the period covers [day]. */
    fun coversDay(day: LocalDate): Boolean {
        val first = firstDay ?: return false
        val last = lastDay ?: first
        return !day.isBefore(first) && !day.isAfter(last)
    }

    /** A series with no COUNT nor UNTIL never ends. */
    val isOpenEnded: Boolean get() = rrule != null && !rrule.contains("COUNT=") && !rrule.contains("UNTIL=")

    /** The end, exclusive, of the last occurrence of a series that ends (COUNT or UNTIL); the period's own end otherwise. */
    fun seriesEndsAt(): Instant? {
        val start = startsAt() ?: return null
        val end = endsAt() ?: return null
        val rule = rrule ?: return end
        if (isOpenEnded) return null
        val last = runCatching {
            if (allDay) {
                val first = firstDay ?: return end
                RruleUtils.expandRruleDates(rule, first, first, first.plusYears(200)).lastOrNull()
                    ?.atStartOfDay(zone)?.toInstant()
            } else {
                RruleUtils.expandRrule(rule, start, start, start.plus(java.time.Duration.ofDays(365L * 200)), timeZone).lastOrNull()
            }
        }.getOrNull() ?: return end
        return last.plus(java.time.Duration.between(start, end))
    }

    fun hasEnded(now: Instant): Boolean {
        if (isOpenEnded) return false
        val end = seriesEndsAt() ?: return false
        return !end.isAfter(now)
    }

    /**
     * The occurrence whose end is after [now] — the one in progress or the next — as (start, end exclusive); the
     * period's own dates when it does not repeat; null when a series has no occurrence left.
     */
    fun occurrenceAt(now: Instant): Pair<Instant, Instant>? {
        val start = startsAt() ?: return null
        val end = endsAt() ?: start
        val rule = rrule ?: return start to end
        val length = java.time.Duration.between(start, end)
        val next = runCatching {
            if (allDay) {
                val first = firstDay ?: return null
                val days = length.toDays()
                val from = now.atZone(zone).toLocalDate().minusDays(days)
                RruleUtils.expandRruleDates(rule, first, maxOf(from, first), from.plusYears(10))
                    .map { it.atStartOfDay(zone).toInstant() }
                    .map { it to it.atZone(zone).toLocalDate().plusDays(days).atStartOfDay(zone).toInstant() }
                    .firstOrNull { it.second.isAfter(now) }
            } else {
                RruleUtils.expandRrule(rule, start, maxOf(start, now.minus(length)), now.plus(java.time.Duration.ofDays(3650)), timeZone)
                    .firstOrNull { it.plus(length).isAfter(now) }?.let { it to it.plus(length) }
            }
        }.getOrNull()
        return next
    }

    /** The period as its current or next occurrence: same series, the dates of that occurrence. */
    fun atOccurrence(now: Instant): Period {
        if (rrule == null) return this
        val (from, to) = occurrenceAt(now) ?: return this
        return if (allDay) copy(startDate = from.atZone(zone).toLocalDate().toString(), endDate = to.atZone(zone).toLocalDate().toString())
        else copy(startAt = from.toString(), endAt = to.toString())
    }

    fun isOngoing(now: Instant): Boolean {
        val start = startsAt() ?: return false
        if (start.isAfter(now)) return false
        if (rrule == null) return !hasEnded(now)
        val (from, to) = occurrenceAt(now) ?: return false
        return !from.isAfter(now) && to.isAfter(now)
    }
}
