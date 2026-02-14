package com.maggie.app.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Task
import com.maggie.app.ui.screens.shared.CriticalityChip
import com.maggie.app.ui.screens.shared.TaskConstants
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dueDateFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)

@Composable
fun DashboardTaskItem(
    task: Task,
    onToggleDone: (Boolean) -> Unit,
) {
    val isDone = task.isDone

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = isDone,
            onCheckedChange = { onToggleDone(!isDone) },
        )

        CriticalityChip(task.criticality)

        Text(
            text = task.title,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (isDone) TextDecoration.LineThrough else null,
            color = if (isDone) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (task.dueDate != null) {
            val zdt = Instant.parse(task.dueDate).atZone(ZoneId.of("Europe/Paris"))
            Text(
                text = zdt.format(dueDateFormatter),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
