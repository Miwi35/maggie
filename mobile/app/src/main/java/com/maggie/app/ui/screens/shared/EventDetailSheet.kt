package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.ui.screens.dashboard.parseColor
import com.maggie.app.util.RruleUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailSheet(
    event: ExpandedEvent,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val zone = ZoneId.of(event.timeZone)
    val startZdt = Instant.parse(event.startAt).atZone(zone)
    val endZdt = Instant.parse(event.endAt).atZone(zone)
    val agendaColor = event.agendaColor?.let { parseColor(it) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Title + color dot
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (agendaColor != null) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(agendaColor, CircleShape),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(
                    text = event.summary,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
            }

            // Date & time
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (event.allDay) {
                        startZdt.format(dateFormatter).replaceFirstChar { it.uppercase() }
                    } else {
                        "${startZdt.format(dateFormatter).replaceFirstChar { it.uppercase() }}, ${startZdt.format(timeFormatter)} - ${endZdt.format(timeFormatter)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Recurrence
            if (event.recurrenceUnreadable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Repeat, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Répétition illisible",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else if (event.masterRrule != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Repeat, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = RruleUtils.rruleToFrenchText(event.masterRrule),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Reminders — where the owner checks what he will be told, and when
            val reminders = remindersText(event.reminders)
            if (reminders != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Notifications, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = reminders, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Location
            if (event.location != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LocationOn, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = event.location, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Description
            if (event.description != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = event.description, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Agenda name
            if (event.agendaName != null) {
                Text(
                    text = event.agendaName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Modifier")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Supprimer",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
