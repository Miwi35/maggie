package com.maggie.app.util

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class DateRange(val start: Instant, val end: Instant)

/** Whole days in Paris, [firstDay] included to [endDay] excluded: what the calendar asks the API for. */
data class DayPeriod(val firstDay: LocalDate, val endDay: LocalDate) {
    fun asRange(): DateRange = DateRange(
        firstDay.atStartOfDay(DateRanges.zone).toInstant(),
        endDay.atStartOfDay(DateRanges.zone).toInstant(),
    )
}

object DateRanges {

    internal val zone: ZoneId = ZoneId.of("Europe/Paris")

    /** The day it is in Paris — the zone the events are drawn in — whatever zone the device reports. */
    fun todayDate(clock: Clock = Clock.systemUTC()): LocalDate = LocalDate.now(clock.withZone(zone))

    /** The time of day in Paris, for the « now » line of the timelines. */
    fun nowTime(clock: Clock = Clock.systemUTC()): LocalTime = LocalTime.now(clock.withZone(zone))

    fun today(): DateRange {
        val now = LocalDate.now(zone)
        val start = now.atStartOfDay(zone).toInstant()
        val end = now.plusDays(1).atStartOfDay(zone).toInstant()
        return DateRange(start, end)
    }

    fun tomorrow(): DateRange {
        val now = LocalDate.now(zone)
        val start = now.plusDays(1).atStartOfDay(zone).toInstant()
        val end = now.plusDays(2).atStartOfDay(zone).toInstant()
        return DateRange(start, end)
    }

    fun thisWeek(): DateRange {
        val now = LocalDate.now(zone)
        val start = now.atStartOfDay(zone).toInstant()
        val end = now.plusDays(7).atStartOfDay(zone).toInstant()
        return DateRange(start, end)
    }

    fun thisMonth(): DateRange {
        val now = LocalDate.now(zone)
        val start = now.atStartOfDay(zone).toInstant()
        val end = now.plusMonths(1).atStartOfDay(zone).toInstant()
        return DateRange(start, end)
    }

    fun forDate(date: LocalDate): DateRange {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        return DateRange(start, end)
    }

    fun forWeek(date: LocalDate): DateRange {
        val monday = date.with(java.time.DayOfWeek.MONDAY)
        val start = monday.atStartOfDay(zone).toInstant()
        val end = monday.plusDays(7).atStartOfDay(zone).toInstant()
        return DateRange(start, end)
    }

    fun forMonth(date: LocalDate): DateRange {
        val first = date.withDayOfMonth(1)
        val start = first.atStartOfDay(zone).toInstant()
        val end = first.plusMonths(1).atStartOfDay(zone).toInstant()
        return DateRange(start, end)
    }

    fun periodOfDate(date: LocalDate) = DayPeriod(date, date.plusDays(1))

    fun periodOfWeek(date: LocalDate): DayPeriod {
        val monday = date.with(java.time.DayOfWeek.MONDAY)
        return DayPeriod(monday, monday.plusDays(7))
    }

    /** The month as the grid draws it: whole weeks, so the days of the neighbouring months show their events too. */
    fun periodOfMonthGrid(date: LocalDate): DayPeriod {
        val first = date.withDayOfMonth(1)
        val last = first.plusMonths(1).minusDays(1)
        return DayPeriod(first.with(java.time.DayOfWeek.MONDAY), last.with(java.time.DayOfWeek.SUNDAY).plusDays(1))
    }
}
