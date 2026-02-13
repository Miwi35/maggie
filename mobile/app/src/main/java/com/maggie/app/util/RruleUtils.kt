package com.maggie.app.util

import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone

object RruleUtils {

    /**
     * Expand an RRULE string into occurrence instants within a given range.
     */
    fun expandRrule(
        rruleString: String,
        dtstart: Instant,
        rangeStart: Instant,
        rangeEnd: Instant,
    ): List<Instant> {
        val rule = RecurrenceRule(rruleString)
        val start = DateTime(TimeZone.getTimeZone("UTC"), dtstart.toEpochMilli())
        val iterator = rule.iterator(start)
        val results = mutableListOf<Instant>()
        val rangeEndMs = rangeEnd.toEpochMilli()
        val rangeStartMs = rangeStart.toEpochMilli()

        while (iterator.hasNext()) {
            val next = iterator.nextDateTime()
            val ms = next.timestamp
            if (ms >= rangeEndMs) break
            if (ms >= rangeStartMs) {
                results.add(Instant.ofEpochMilli(ms))
            }
        }

        return results
    }

    private val FREQ_LABELS = mapOf(
        "DAILY" to ("jour" to "jours"),
        "WEEKLY" to ("semaine" to "semaines"),
        "MONTHLY" to ("mois" to "mois"),
        "YEARLY" to ("an" to "ans"),
    )

    private val RRULE_DAY_TO_FRENCH = mapOf(
        "MO" to "lun.", "TU" to "mar.", "WE" to "mer.",
        "TH" to "jeu.", "FR" to "ven.", "SA" to "sam.", "SU" to "dim.",
    )

    /**
     * Convert an RRULE string to a human-readable French description.
     */
    fun rruleToFrenchText(rruleString: String): String {
        val parts = rruleString.split(";").associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }

        val freq = parts["FREQ"] ?: return rruleString
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        val labels = FREQ_LABELS[freq] ?: return rruleString

        var text = when {
            interval == 1 -> when (freq) {
                "DAILY" -> "Tous les jours"
                "WEEKLY" -> "Toutes les semaines"
                "MONTHLY" -> "Tous les mois"
                "YEARLY" -> "Tous les ans"
                else -> return rruleString
            }
            freq == "WEEKLY" -> "Toutes les $interval ${labels.second}"
            else -> "Tous les $interval ${labels.second}"
        }

        // Weekly day list
        if (freq == "WEEKLY") {
            val byDay = parts["BYDAY"]
            if (byDay != null) {
                val dayNames = byDay.split(",").mapNotNull { RRULE_DAY_TO_FRENCH[it] }
                if (dayNames.isNotEmpty()) {
                    text += " le ${dayNames.joinToString(", ")}"
                }
            }
        }

        // End condition
        val count = parts["COUNT"]?.toIntOrNull()
        val until = parts["UNTIL"]
        if (count != null) {
            text += ", $count fois"
        } else if (until != null) {
            val date = parseUntilDate(until)
            if (date != null) {
                val formatted = date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.FRENCH))
                text += ", jusqu'au $formatted"
            }
        }

        return text
    }

    private fun parseUntilDate(until: String): LocalDate? {
        return try {
            // Format: YYYYMMDDTHHmmssZ or YYYYMMDD
            val dateStr = until.substringBefore("T")
            LocalDate.of(
                dateStr.substring(0, 4).toInt(),
                dateStr.substring(4, 6).toInt(),
                dateStr.substring(6, 8).toInt(),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build an RRULE string from picker options.
     */
    fun buildRruleString(
        freq: String,
        interval: Int = 1,
        byweekday: List<String>? = null,
        count: Int? = null,
        until: LocalDate? = null,
    ): String {
        val parts = mutableListOf("FREQ=$freq")

        if (interval > 1) {
            parts.add("INTERVAL=$interval")
        }

        if (freq == "WEEKLY" && !byweekday.isNullOrEmpty()) {
            parts.add("BYDAY=${byweekday.joinToString(",")}")
        }

        if (count != null) {
            parts.add("COUNT=$count")
        } else if (until != null) {
            val pad = { n: Int -> n.toString().padStart(2, '0') }
            parts.add("UNTIL=${until.year}${pad(until.monthValue)}${pad(until.dayOfMonth)}T235959Z")
        }

        return parts.joinToString(";")
    }

    /**
     * Truncate an RRULE at a given date by adding an UNTIL clause.
     * The UNTIL is set to 23:59:59 UTC on the day before [beforeDate].
     */
    fun addUntilToRrule(rruleString: String, beforeDate: Instant): String {
        val parts = rruleString.split(";")
            .filter { !it.startsWith("UNTIL=") && !it.startsWith("COUNT=") }
            .toMutableList()

        val day = ZonedDateTime.ofInstant(beforeDate, ZoneId.of("UTC")).minusDays(1)
        val pad = { n: Int -> n.toString().padStart(2, '0') }
        val until = "${day.year}${pad(day.monthValue)}${pad(day.dayOfMonth)}T235959Z"
        parts.add("UNTIL=$until")

        return parts.joinToString(";")
    }
}
