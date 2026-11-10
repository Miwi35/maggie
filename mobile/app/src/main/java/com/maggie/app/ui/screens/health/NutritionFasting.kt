package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.FastingProtocol
import com.maggie.app.data.model.FastingStatus
import com.maggie.app.data.model.HealthDay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.max

/** How many nights the tablet's « Jeûnes · 14 jours » shows, today the last (MAG-770). */
const val FASTING_HISTORY_DAYS = 14

/** The chart's lowest top, in hours: a 16 h fast stands at 80 % of it, as the mockup draws it. */
const val FASTING_CHART_MIN_HOURS = 20

/**
 * What the « Jeûne » card draws at an instant (MAG-770). [open] is « Fenêtre ouverte », else « Jeûne ». [progress] fills
 * the ring: in the window, the time since it opened over the window's length; fasting, the time since the last intake
 * over the protocol's fast. [remainingMinutes] is the centre: until the window closes, or until it opens.
 */
data class FastingView(
    val protocol: FastingProtocol,
    val open: Boolean,
    val progress: Float,
    val remainingMinutes: Long,
    /** « 12 h 10 → 20 h 00 »: the first intake (or the window's start) to the window's end. */
    val span: String,
    /** « Jeûne de la nuit · 16 h 05 », null when the API has no night fast for the day. */
    val nightFast: String?,
) {
    val title: String get() = if (open) TITLE_OPEN else TITLE_FASTING

    companion object {
        const val TITLE_OPEN = "Fenêtre ouverte"
        const val TITLE_FASTING = "Jeûne"
    }
}

/**
 * The card for [status] at [now] in [zone]; null without a protocol (no card) or a day or window the app cannot read.
 * The API's state decides, except that a day with nothing eaten yet reads « Fenêtre ouverte » once its window's hour has
 * come — the ring then counts from the window's start.
 */
fun fastingView(status: FastingStatus, now: Instant, zone: ZoneId): FastingView? {
    val protocol = FastingProtocol.byKey(status.protocol) ?: return null
    val day = runCatching { LocalDate.parse(status.date) }.getOrNull() ?: return null
    val start = parseClock(status.windowStart) ?: return null
    val end = parseClock(status.windowEnd) ?: start.plusHours(protocol.windowHours.toLong())
    val windowStart = day.atTime(start).atZone(zone)
    val windowEnd = day.atTime(end).atZone(zone).let { if (end.isAfter(start)) it else it.plusDays(1) }
    val openedAt = parseInstant(status.openedAt)
    val nightFast = status.nightFastMinutes?.let { "Jeûne de la nuit · ${formatFastDuration(it.toLong())}" }

    val open = when (status.state) {
        FastingStatus.STATE_OPEN -> true
        FastingStatus.STATE_FASTING -> !now.isBefore(windowStart.toInstant()) && now.isBefore(windowEnd.toInstant())
        else -> false
    }
    if (open) {
        val from = openedAt?.takeIf { status.state == FastingStatus.STATE_OPEN } ?: windowStart.toInstant()
        return FastingView(
            protocol = protocol,
            open = true,
            progress = fraction(minutesBetween(from, now), protocol.windowHours * 60L),
            remainingMinutes = minutesLeft(now, windowEnd.toInstant()),
            span = "${fastingClock(from.atZone(zone).toLocalTime())} → ${fastingClock(end)}",
            nightFast = nightFast,
        )
    }
    val lastIntake = parseInstant(status.lastIntakeAt)
    return FastingView(
        protocol = protocol,
        open = false,
        progress = lastIntake?.let { fraction(minutesBetween(it, now), protocol.fastHours * 60L) } ?: 0f,
        remainingMinutes = minutesLeft(now, nextOccurrence(windowStart, now).toInstant()),
        span = "${fastingClock(start)} → ${fastingClock(end)}",
        nightFast = nightFast,
    )
}

/** « 2 h 20 », « 16 h 05 », « 0 h 45 »: a fast or what is left of it, as the mockup writes it. */
fun formatFastDuration(minutes: Long): String {
    val whole = max(0L, minutes)
    return "${whole / 60} h ${(whole % 60).toString().padStart(2, '0')}"
}

/** « 12 h 10 », « 20 h 00 ». */
fun fastingClock(time: LocalTime): String = "${time.hour} h ${time.minute.toString().padStart(2, '0')}"

/** `HH:MM` as the API writes a window's bound and the profile takes it. */
fun fastingTime(hour: Int, minute: Int): String = "%02d:%02d".format(java.util.Locale.ROOT, hour, minute)

/** The [FASTING_HISTORY_DAYS] nights ending [today], oldest first, each with its fast in minutes or null. */
fun fastingHistory(days: List<HealthDay>, today: LocalDate): List<Pair<LocalDate, Int?>> {
    val byDate = days.associateBy { it.date }
    return (FASTING_HISTORY_DAYS - 1 downTo 0).map { back ->
        val date = today.minusDays(back.toLong())
        date to byDate[date.toString()]?.nightFastMinutes
    }
}

/** The chart's top in minutes: [FASTING_CHART_MIN_HOURS], or the longest fast when one went beyond. */
fun fastingChartScale(history: List<Pair<LocalDate, Int?>>): Int =
    max(FASTING_CHART_MIN_HOURS * 60, history.maxOfOrNull { it.second ?: 0 } ?: 0)

private fun parseClock(text: String?): LocalTime? = text?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

private fun parseInstant(text: String?): Instant? = text?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

private fun minutesBetween(from: Instant, to: Instant): Long = Duration.between(from, to).toMinutes()

/** Whole minutes left, rounded up: « 0 h 00 » only once the time has come. */
private fun minutesLeft(now: Instant, until: Instant): Long = max(0L, (Duration.between(now, until).seconds + 59) / 60)

private fun fraction(part: Long, whole: Long): Float = if (whole <= 0) 0f else (part.toFloat() / whole).coerceIn(0f, 1f)

private fun nextOccurrence(time: ZonedDateTime, now: Instant): ZonedDateTime {
    var next = time
    while (!next.toInstant().isAfter(now)) next = next.plusDays(1)
    return next
}
