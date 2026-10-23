package com.maggie.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.shared.eventDateLabel
import com.maggie.app.ui.screens.shared.parseColor
import com.maggie.app.ui.screens.shared.remindersText
import com.maggie.app.ui.uiTagRoot

/**
 * The fiche of an event touched on the Accueil: title, schedule, place, agenda, notes, reminder, and the way
 * into the Agenda. Read only — editing is the Agenda's job. « Lancer » is offered for an event that launches
 * an activity, the monthly review.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaEventSheet(
    event: AgendaHomeEvent,
    onDismiss: () -> Unit,
    onOpenInAgenda: () -> Unit,
    onLaunch: () -> Unit,
) {
    val expanded = event.toExpandedEvent()
    val agendaColor = event.agendaColor?.let(::parseColor)
    val reminders = remindersText(event.reminders)

    // A sheet is a window of its own: its tags need their own root for Maestro to see them.
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.uiTagRoot()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .testTag(UiTags.HOME_AGENDA_SHEET),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (agendaColor != null) {
                    Box(Modifier.size(12.dp).background(agendaColor, CircleShape))
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = expanded.title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).testTag(UiTags.HOME_AGENDA_SHEET_TITLE),
                )
            }

            FicheLine(Icons.Outlined.CalendarToday) { Text(eventDateLabel(expanded), style = MaterialTheme.typography.bodyMedium) }
            event.location?.let { place ->
                FicheLine(Icons.Outlined.LocationOn) { Text(place, style = MaterialTheme.typography.bodyMedium) }
            }
            event.description?.let { notes ->
                FicheLine(Icons.Outlined.Description) { Text(notes, style = MaterialTheme.typography.bodyMedium) }
            }
            reminders?.let { delays ->
                FicheLine(Icons.Outlined.Notifications) { Text(delays, style = MaterialTheme.typography.bodyMedium) }
            }
            event.agendaName?.let { name ->
                Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (expanded.launchesFinanceReview) {
                Button(
                    onClick = onLaunch,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(UiTags.HOME_AGENDA_LAUNCH),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Lancer")
                }
            }
            OutlinedButton(
                onClick = onOpenInAgenda,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(UiTags.HOME_AGENDA_OPEN_EVENT),
            ) { Text("Ouvrir dans l'agenda") }
        }
    }
}

@Composable
private fun FicheLine(icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        content()
    }
}
