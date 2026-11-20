package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
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
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The dates card of the task form (MAG-718), as the mockup draws it: « Échéance », the deadline;
 * « Programmée », the day it is planned for, then its « Moment » once a day is chosen; « Rappels »
 * only at a precise time; « Répéter » and « Jusqu’au » under the programmation (MAG-887). Both dates offer « Demain » / « Après-demain » in their picker.
 */
@Composable
fun TaskDatesCard(
    dueDate: LocalDate?,
    onDueDateChange: (LocalDate?) -> Unit,
    schedule: TaskSchedule,
    onScheduleChange: (TaskSchedule) -> Unit,
    repeat: TaskRepeat,
    onRepeatChange: (TaskRepeat) -> Unit,
    anchor: LocalDate?,
    canRepeat: Boolean,
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
        if (canRepeat) {
            RepeatField(repeat = repeat, anchor = anchor, onChange = onRepeatChange)
            if (repeat.repeats(anchor)) {
                DateField(
                    label = "Jusqu’au",
                    value = repeat.until,
                    onValueChange = { onRepeatChange(repeat.withUntil(it)) },
                    onClear = { onRepeatChange(repeat.withUntil(null)) },
                    tag = UiTags.TASK_FORM_UNTIL,
                    emptyText = TaskRepeatText.untilLabel(null),
                    format = TaskRepeatText::untilLabel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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

/**
 * « Répéter » (MAG-887): a menu of the six choices, the current one checked; « Personnalisé… » opens its dialog.
 * Inactive, at « Jamais », while the task has no day to anchor the rule on.
 */
@Composable
private fun RepeatField(repeat: TaskRepeat, anchor: LocalDate?, onChange: (TaskRepeat) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(false) }
    val rule = repeat.toApi(anchor)
    val text = TaskRepeatText.ruleText(rule, anchor)
    val checked = repeat.checked(anchor)
    val enabled = anchor != null

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Répéter") },
            leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .testTag(UiTags.TASK_FORM_REPEAT)
                    .semantics { contentDescription = "Répéter, $text" }
                    .clickable(onClickLabel = "Choisir : Répéter", role = Role.Button) { expanded = true },
            )
        } else {
            Box(modifier = Modifier.matchParentSize().testTag(UiTags.TASK_FORM_REPEAT).semantics {
                contentDescription = "Répéter, $text"
                disabled()
            })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.uiTagRoot()) {
            RepeatPreset.entries.forEach { preset ->
                val current = preset == checked
                DropdownMenuItem(
                    text = {
                        Text(preset.label, color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified)
                    },
                    trailingIcon = if (current) {
                        { Icon(Icons.Default.Check, contentDescription = "Choix actuel", tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        if (preset == RepeatPreset.CUSTOM) dialog = true else onChange(repeat.pick(preset))
                    },
                    modifier = Modifier.testTag(UiTags.taskFormRepeat(preset.key)),
                )
            }
        }
    }

    if (dialog && anchor != null) {
        TaskRepeatDialog(
            initial = TaskRepeatText.parse(rule) ?: TaskRepeatText.defaultRule(anchor),
            anchor = anchor,
            onConfirm = {
                dialog = false
                onChange(repeat.withCustom(it))
            },
            onDismiss = { dialog = false },
        )
    }
}

private val UNIT_LABELS = listOf(RepeatUnit.DAY to "jours", RepeatUnit.WEEK to "semaines", RepeatUnit.MONTH to "mois")

private val DAY_LETTERS = listOf(
    DayOfWeek.MONDAY to "L", DayOfWeek.TUESDAY to "M", DayOfWeek.WEDNESDAY to "M", DayOfWeek.THURSDAY to "J",
    DayOfWeek.FRIDAY to "V", DayOfWeek.SATURDAY to "S", DayOfWeek.SUNDAY to "D",
)

/** « Répétition personnalisée »: the fields live in [RepeatRuleFields], the dialog only frames them. */
@Composable
private fun TaskRepeatDialog(
    initial: RepeatRule,
    anchor: LocalDate,
    onConfirm: (RepeatRule) -> Unit,
    onDismiss: () -> Unit,
) {
    var rule by remember { mutableStateOf<RepeatRule?>(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.uiTagRoot(),
        title = { Text("Répétition personnalisée") },
        text = { RepeatRuleFields(initial, anchor, onRuleChange = { rule = it }) },
        confirmButton = {
            TextButton(
                onClick = { rule?.let(onConfirm) },
                enabled = rule != null,
                modifier = Modifier.testTag(UiTags.TASK_REPEAT_OK),
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** « Toutes les » / « Tous les » n, jours / semaines / mois, the days of a week, the rule in words; null when n is not a number ≥ 1. */
@Composable
internal fun RepeatRuleFields(initial: RepeatRule, anchor: LocalDate, onRuleChange: (RepeatRule?) -> Unit) {
    var interval by remember { mutableStateOf(initial.interval.toString()) }
    var unit by remember { mutableStateOf(initial.unit) }
    var days by remember { mutableStateOf(initial.days.ifEmpty { setOf(anchor.dayOfWeek) }) }
    val count = interval.toIntOrNull()?.takeIf { it >= 1 }
    val rule = count?.let { RepeatRule(unit, it, if (unit == RepeatUnit.WEEK) days else emptySet()) }
    SideEffect { onRuleChange(rule) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = interval,
            onValueChange = { value -> interval = value.filter(Char::isDigit).take(3) },
            label = { Text(if (unit == RepeatUnit.WEEK) "Toutes les" else "Tous les") },
            singleLine = true,
            isError = count == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag(UiTags.TASK_REPEAT_INTERVAL),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            UNIT_LABELS.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = unit == value,
                    onClick = { unit = value },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = UNIT_LABELS.size),
                    modifier = Modifier.testTag(UiTags.taskRepeatUnit(value.name.lowercase())),
                ) { Text(label) }
            }
        }
        if (unit == RepeatUnit.WEEK) {
            MultiChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                DAY_LETTERS.forEachIndexed { index, (day, letter) ->
                    val on = day in days
                    SegmentedButton(
                        checked = on,
                        onCheckedChange = { checked ->
                            if (checked) days = days + day else if (days.size > 1) days = days - day
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = DAY_LETTERS.size),
                        icon = {},
                        modifier = Modifier
                            .testTag(UiTags.taskRepeatDay(TaskRepeatText.dayCode(day)))
                            .semantics { contentDescription = TaskRepeatText.dayName(day) },
                    ) { Text(letter) }
                }
            }
        }
        Text(
            rule?.let { TaskRepeatText.ruleText(it, anchor) } ?: "Indiquez un nombre d’au moins 1",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
