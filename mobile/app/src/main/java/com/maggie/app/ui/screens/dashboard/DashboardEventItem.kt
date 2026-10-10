package com.maggie.app.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ExpandedEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)
private val dateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.FRENCH)
private val dateFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)

/**
 * The time column of a dashboard row. An all-day event shows its first date as it
 * is — no zone moves a date (MAG-382) — or « Journée » when the section is its day.
 */
internal fun dashboardEventTime(event: ExpandedEvent, showDate: Boolean): String {
    if (event.allDay) {
        if (!showDate) return "Journée"
        event.startDate?.let { return it.format(dateFormatter) }
    }
    val start = Instant.parse(event.startAt ?: return "").atZone(ZoneId.of(event.timeZone))
    return when {
        event.allDay -> start.format(dateFormatter)
        showDate -> start.format(dateTimeFormatter)
        else -> start.format(timeFormatter)
    }
}

@Composable
fun DashboardEventItem(
    event: ExpandedEvent,
    showDate: Boolean = false,
    onClick: () -> Unit = {},
) {
    val agendaColor = event.agendaColor?.let { parseColor(it) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Agenda color dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = agendaColor ?: MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ),
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Time
        Text(
            text = dashboardEventTime(event, showDate),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(if (showDate) 80.dp else 48.dp),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.summary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (event.location != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Outlined.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = event.location,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal fun parseColor(hex: String): Color? {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        null
    }
}
