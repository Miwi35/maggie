package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Task
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.CARD_RADIUS
import com.maggie.app.ui.components.ClockDialog
import com.maggie.app.ui.components.DateField
import com.maggie.app.ui.components.formatFrenchTime
import com.maggie.app.ui.components.maggieCardContainer
import com.maggie.app.ui.theme.isMaggieDark
import com.maggie.app.ui.uiTagRoot
import java.time.LocalDate

/**
 * The dates card of the task form (MAG-718), as the mockup draws it: « Échéance », the deadline;
 * « Programmée », the day it is planned for, then its « Moment » once a day is chosen; « Rappels »
 * only at a precise time. Both dates offer « Demain » / « Après-demain » in their picker.
 */
@Composable
fun TaskDatesCard(
    dueDate: LocalDate?,
    onDueDateChange: (LocalDate?) -> Unit,
    schedule: TaskSchedule,
    onScheduleChange: (TaskSchedule) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A background, not a clipped Card: a clipped layer swallows the taps of the fields under Robolectric.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(maggieCardContainer(isMaggieDark(), raised = false), RoundedCornerShape(CARD_RADIUS))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DateField(
            label = "Échéance",
            value = dueDate,
            onValueChange = onDueDateChange,
            onClear = { onDueDateChange(null) },
            tag = UiTags.TASK_FORM_DUE,
            quickPicks = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DateField(
            label = "Programmée",
            value = schedule.day,
            onValueChange = { onScheduleChange(schedule.onDay(it)) },
            onClear = { onScheduleChange(schedule.onDay(null)) },
            tag = UiTags.TASK_FORM_SCHEDULED,
            quickPicks = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (schedule.day != null) {
            MomentField(schedule = schedule, onChange = onScheduleChange)
        }
        if (schedule.isAtTime) {
            TaskReminderPicker(
                minutes = schedule.reminders,
                onChange = { onScheduleChange(schedule.withReminders(it)) },
                modifier = Modifier.testTag(UiTags.TASK_FORM_REMINDERS),
            )
        }
    }
}

/**
 * « Moment »: a menu of Matin, Après-midi, Soir and « Heure précise », which opens the 24 h clock;
 * the field then shows the time. The cross leaves a bare day.
 */
@Composable
private fun MomentField(schedule: TaskSchedule, onChange: (TaskSchedule) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(false) }
    val text = when {
        schedule.isAtTime -> schedule.time?.let(::formatFrenchTime).orEmpty()
        else -> schedule.moment?.let { TaskSchedule.MOMENT_LABELS[it] }.orEmpty()
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            label = { Text("Moment") },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .testTag(UiTags.TASK_FORM_MOMENT)
                .semantics { contentDescription = if (text.isEmpty()) "Moment" else "Moment, $text" }
                .clickable(onClickLabel = "Choisir : Moment", role = Role.Button) { expanded = true },
        )
        if (text.isNotEmpty()) {
            IconButton(
                onClick = { onChange(schedule.atMoment(null)) },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Effacer : Moment")
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.uiTagRoot()) {
            TaskSchedule.MOMENT_LABELS.forEach { (moment, label) ->
                val itemText = if (moment == Task.MOMENT_TIME && schedule.isAtTime) {
                    "$label · ${schedule.time?.let(::formatFrenchTime)} ›"
                } else {
                    label
                }
                DropdownMenuItem(
                    text = { Text(itemText) },
                    onClick = {
                        expanded = false
                        if (moment == Task.MOMENT_TIME) clock = true else onChange(schedule.atMoment(moment))
                    },
                    modifier = Modifier.testTag(UiTags.taskFormMoment(moment)),
                )
            }
        }
    }

    if (clock) {
        ClockDialog(
            title = "Heure précise",
            value = schedule.time ?: TaskSchedule.DEFAULT_TIME,
            onConfirm = {
                clock = false
                onChange(schedule.atTime(it))
            },
            onDismiss = { clock = false },
        )
    }
}
