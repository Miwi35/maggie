package com.maggie.app.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.Task

@Composable
fun DashboardSection(
    title: String,
    events: List<ExpandedEvent>,
    tasks: List<Task>,
    showDate: Boolean = false,
    onToggleTaskDone: (String, Boolean) -> Unit,
    onEventClick: (ExpandedEvent) -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // Events section
            if (events.isNotEmpty()) {
                Text(
                    text = "Événements (${events.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                events.forEach { event ->
                    DashboardEventItem(
                        event = event,
                        showDate = showDate,
                        onClick = { onEventClick(event) },
                    )
                }
            }

            // Tasks section
            if (tasks.isNotEmpty()) {
                if (events.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                Text(
                    text = "Tâches (${tasks.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                tasks.forEach { task ->
                    DashboardTaskItem(
                        task = task,
                        onToggleDone = { done -> onToggleTaskDone(task.id, done) },
                    )
                }
            }

            if (events.isEmpty() && tasks.isEmpty()) {
                Text(
                    text = "Rien de prévu",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
