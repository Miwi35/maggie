package com.maggie.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class DateRange(val start: Instant, val end: Instant)

object DateRanges {

    private val zone = ZoneId.of("Europe/Paris")

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
}
