package com.maggie.app.ui.screens.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Task
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.shared.CriticalityChip
import com.maggie.app.ui.screens.shared.TaskConstants
import com.maggie.app.ui.screens.shared.UrgencyBadge
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dueDateFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)

/** « En retard de 2 j » — the wording the admin shows too. */
fun lateLabel(days: Int): String = "En retard de $days j"

@Composable
fun DashboardTaskItem(
    task: Task,
    onToggleDone: (Boolean) -> Unit,
    onClick: () -> Unit = {},
    nested: Boolean = false,
    /** The tag of the box, for a journey to tick this row: the dashboard's by default. */
    checkTag: String = UiTags.dashboardTaskCheck(task.id),
) {
    val isDone = task.isDone
    val daysLate = task.daysLate

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (nested && task.isSubtask) 32.dp else 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = isDone,
            onCheckedChange = { onToggleDone(!isDone) },
            modifier = Modifier.testTag(checkTag),
        )

        CriticalityChip(task.criticality)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (isDone) TextDecoration.LineThrough else null,
                color = if (isDone) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Urgency (MAG-406) and lateness (MAG-405) are said in words and with an icon,
            // never by colour alone.
            if (task.urgency != null || daysLate != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UrgencyBadge(task.urgency)
                    if (daysLate != null) LateLabel(daysLate)
                }
            }
        }

        task.dueDay?.let { day ->
            Text(
                text = if (daysLate != null) "prévue le ${day.format(dueDateFormatter)}" else day.format(dueDateFormatter),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LateLabel(daysLate: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Default.EventBusy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = lateLabel(daysLate),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
