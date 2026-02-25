package com.maggie.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object ChatDateFormatter {

    private val zone = ZoneId.of("Europe/Paris")
    private val locale = Locale.FRENCH
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale).withZone(zone)
    private val shortDateFormatter = DateTimeFormatter.ofPattern("EEE d MMM", locale).withZone(zone)
    private val fullDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", locale).withZone(zone)

    fun parseIso(iso: String): Instant? = try {
        Instant.parse(iso)
    } catch (_: Exception) {
        null
    }

    fun formatTime(instant: Instant): String = timeFormatter.format(instant)

    fun formatDayLabel(instant: Instant): String {
        val messageDate = instant.atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        return when (messageDate) {
            today -> "Aujourd'hui"
            yesterday -> "Hier"
            else -> {
                if (messageDate.year == today.year) {
                    shortDateFormatter.format(instant)
                } else {
                    fullDateFormatter.format(instant)
                }
            }
        }
    }

    fun formatDayAndTime(instant: Instant): String =
        "${formatDayLabel(instant)} ${formatTime(instant)}"

    /**
     * Returns a separator label to insert before the current message, or null if none needed.
     * - First message or different day → "DayLabel HH:MM"
     * - Same day, >15 min gap → "HH:MM" only
     * - Otherwise → null
     */
    fun getTimeSeparatorLabel(prevCreatedAt: String?, currentCreatedAt: String): String? {
        val current = parseIso(currentCreatedAt) ?: return null

        if (prevCreatedAt == null) {
            return formatDayAndTime(current)
        }

        val prev = parseIso(prevCreatedAt) ?: return formatDayAndTime(current)

        val prevDate = prev.atZone(zone).toLocalDate()
        val currentDate = current.atZone(zone).toLocalDate()

        if (prevDate != currentDate) {
            return formatDayAndTime(current)
        }

        val minutesGap = ChronoUnit.MINUTES.between(prev, current)
        if (minutesGap > 15) {
            return formatTime(current)
        }

        return null
    }
}
