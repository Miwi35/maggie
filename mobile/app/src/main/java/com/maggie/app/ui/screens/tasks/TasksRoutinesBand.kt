package com.maggie.app.ui.screens.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.DayRoutine
import com.maggie.app.data.model.RoutinesBand
import com.maggie.app.ui.UiTags

/**
 * « Routines · soir » above the ring (MAG-512): the routines to tick now, each a pill that ticks or
 * unticks in one tap. Apart from the day: no ring, no « Maintenant », no star.
 */
@Composable
internal fun RoutinesBandRow(band: RoutinesBand, pending: Set<String>, onToggle: (DayRoutine) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(UiTags.ROUTINES_BAND),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = band.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(band.routines, key = { it.id }) { routine ->
                RoutinePill(routine = routine, enabled = routine.id !in pending, onToggle = { onToggle(routine) })
            }
        }
    }
}

@Composable
internal fun RoutinePill(routine: DayRoutine, enabled: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (routine.checked) RoutineGreen else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (routine.checked) OnRoutineGreen else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (routine.checked) RoutineGreen else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(UiTags.routinePill(routine.id))
            .toggleable(value = routine.checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (routine.checked) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = routine.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
