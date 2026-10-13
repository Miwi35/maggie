package com.maggie.app.ui.screens.shared

import java.time.LocalDate

/**
 * A task's due date is a day, read and written as `YYYY-MM-DD` (MAG-404): Google
 * Tasks has no due time, so no zone ever shifts it to the day before.
 */
object TaskDueDate {
    fun fromApi(day: String?): LocalDate? =
        day?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun toApi(date: LocalDate?): String? = date?.toString()
}
