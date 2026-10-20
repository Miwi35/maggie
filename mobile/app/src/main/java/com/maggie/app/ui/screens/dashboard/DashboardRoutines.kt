package com.maggie.app.ui.screens.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.DayRoutine
import com.maggie.app.data.model.RoutineDayView
import com.maggie.app.ui.UiTags

/**
 * The « Routines » widget of the home (MAG-512): how many of today's routines are done, then the
 * ones due by moment, each ticked in place. Its title opens the Routines tab. Not drawn when none
 * is due today.
 */
@Composable
fun DashboardRoutines(
    day: RoutineDayView?,
    onToggle: (DayRoutine) -> Unit,
    onOpen: () -> Unit,
) {
    if (day == null || day.dueCount == 0) return
    Card(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.DASHBOARD_ROUTINES),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .testTag(UiTags.DASHBOARD_ROUTINES_TITLE)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Routines", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Ouvrir les routines")
            }
            Text(
                text = day.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).testTag(UiTags.DASHBOARD_ROUTINES_SUMMARY),
            )
            day.moments.filter { it.routines.isNotEmpty() }.forEach { moment ->
                Text(
                    text = moment.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                )
                moment.routines.forEach { routine -> RoutineRow(routine, onToggle = { onToggle(routine) }) }
            }
        }
    }
}

@Composable
private fun RoutineRow(routine: DayRoutine, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = routine.checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.testTag(UiTags.dashboardRoutineCheck(routine.id)),
        )
        Text(
            text = routine.title,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (routine.checked) TextDecoration.LineThrough else null,
            color = if (routine.checked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
