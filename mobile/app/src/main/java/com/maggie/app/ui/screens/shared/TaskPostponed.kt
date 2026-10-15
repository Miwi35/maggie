package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * « Reportée » (MAG-497): a task left undone is put off, never late — no red, no count of days
 * owed. Said from the day it was first put off: « depuis hier », « depuis lundi » within the week,
 * « depuis le 3 oct. » beyond. A critical task keeps its own mark instead, so it gets no label here.
 */
object TaskPostponed {
    private val weekday = DateTimeFormatter.ofPattern("EEEE", Locale.FRENCH)
    private val dayOfMonth = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)
    private val dayOfYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRENCH)

    fun label(since: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(since, today)
        val from = when {
            days <= 1 -> "hier"
            days <= 6 -> since.format(weekday)
            since.year == today.year -> "le " + since.format(dayOfMonth)
            else -> "le " + since.format(dayOfYear)
        }
        return "reportée depuis $from"
    }

    /** The label of a task put off since [postponedSince] (`YYYY-MM-DD`), or null: not put off, or critical. */
    fun labelOf(postponedSince: String?, urgency: String?, today: LocalDate): String? {
        if (urgency == "critical") return null
        val since = TaskDueDate.fromApi(postponedSince) ?: return null
        return label(since, today)
    }
}

/** « 20 min », « 1 h », « 1 h 30 »: an estimate, read at a glance. */
fun minutesLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
}

/** « reportée depuis … » in the text's own neutral colour, with an icon so it is not the words alone. */
@Composable
fun PostponedLabel(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
