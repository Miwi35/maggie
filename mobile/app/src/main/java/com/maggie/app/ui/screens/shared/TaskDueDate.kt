package com.maggie.app.ui.screens.shared

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A task's due date is a day, sent to the API as that day's midnight in the user's zone. */
object TaskDueDate {
    private val ZONE: ZoneId = ZoneId.of("Europe/Paris")

    fun fromIso(iso: String?): LocalDate? =
        iso?.let { runCatching { Instant.parse(it).atZone(ZONE).toLocalDate() }.getOrNull() }

    fun toIso(date: LocalDate?): String? = date?.atStartOfDay(ZONE)?.toInstant()?.toString()
}
