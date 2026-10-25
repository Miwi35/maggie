package com.maggie.app.ui.screens.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.TaskView
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot

/** What the header and the menu call the « Routines » entry, which is not a [TaskView]. */
internal const val ROUTINES_LABEL = "Routines"

/** The name of a view in the breadcrumb: a null view is « Routines ». */
internal fun viewNameOf(view: TaskView?): String = view?.label ?: ROUTINES_LABEL

/**
 * The header of the mockup, « Tâches › Aujourd'hui ▾ » (MAG-626): the current view's name opens the
 * menu of [entries] (a null entry is « Routines »), each worded by [labelOf]. [current] is the one shown.
 */
@Composable
internal fun TasksHeader(
    current: TaskView?,
    entries: List<TaskView?>,
    labelOf: (TaskView?) -> String,
    onPick: (TaskView?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable { open = true }
                .testTag(UiTags.TASKS_VIEW_MENU),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = muted)) { append("Tâches › ") }
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(viewNameOf(current)) }
                },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.uiTagRoot()) {
            entries.forEach { view ->
                DropdownMenuItem(
                    text = { Text(labelOf(view), maxLines = 1) },
                    onClick = {
                        open = false
                        onPick(view)
                    },
                    modifier = Modifier.testTag(UiTags.tasksTab(view?.key ?: ROUTINES_TAB)),
                )
            }
        }
    }
}
