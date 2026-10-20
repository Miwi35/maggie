package com.maggie.app.ui.screens.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Routine
import com.maggie.app.data.model.RoutineRecurrence
import com.maggie.app.data.model.RoutineText
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.InFrench
import com.maggie.app.ui.uiTagRoot

private val WEEKDAY_LETTERS = listOf("L", "M", "M", "J", "V", "S", "D")

/**
 * The sheet of a routine (MAG-511): Quoi, Quand, Répéter, « Me le rappeler »; on an existing one,
 * « Active » and « Supprimer » too. Stateless: the draft is the view model's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineEditSheet(
    draft: RoutineDraft,
    isSaving: Boolean,
    message: String?,
    onChange: (RoutineDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.uiTagRoot(),
    ) {
        RoutineEditContent(
            draft = draft,
            isSaving = isSaving,
            message = message,
            onChange = onChange,
            onSave = onSave,
            onDelete = onDelete,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun RoutineEditContent(
    draft: RoutineDraft,
    isSaving: Boolean,
    message: String?,
    onChange: (RoutineDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    var pickingTime by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = RoutineGreen,
        selectedLabelColor = OnRoutineGreen,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag(UiTags.ROUTINE_SHEET),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = draft.title,
            onValueChange = { onChange(draft.copy(title = it)) },
            label = { Text("Quoi") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().testTag(UiTags.ROUTINE_TITLE),
        )

        Text("Quand", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Routine.ANCHORS.forEach { anchor ->
                FilterChip(
                    selected = draft.anchor == anchor,
                    onClick = {
                        onChange(draft.copy(anchor = anchor))
                        if (anchor == Routine.ANCHOR_TIME) pickingTime = true
                    },
                    label = { Text(RoutineText.chipOf(anchor)) },
                    colors = chipColors,
                    modifier = Modifier.testTag(UiTags.routineAnchor(anchor)),
                )
            }
        }
        if (draft.anchor == Routine.ANCHOR_TIME) {
            Text(
                text = RoutineText.hourOf(draft.anchorTime),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { pickingTime = true }
                    .padding(vertical = 4.dp)
                    .testTag(UiTags.ROUTINE_TIME),
            )
        }

        Text("Répéter", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoutineRecurrence.KINDS.forEach { kind ->
                FilterChip(
                    selected = draft.kind == kind,
                    onClick = { onChange(draft.copy(kind = kind)) },
                    label = { Text(RoutineText.repeatOf(kind, draft.weekday)) },
                    colors = chipColors,
                    modifier = Modifier.testTag(UiTags.routineRepeat(kind)),
                )
            }
        }
        when (draft.kind) {
            RoutineRecurrence.WEEKLY -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WEEKDAY_LETTERS.forEachIndexed { index, letter ->
                    val weekday = index + 1
                    FilterChip(
                        selected = draft.weekday == weekday,
                        onClick = { onChange(draft.copy(weekday = weekday)) },
                        label = { Text(letter) },
                        colors = chipColors,
                        modifier = Modifier
                            .semantics { contentDescription = RoutineText.weekdayOf(weekday) }
                            .testTag(UiTags.routineWeekday(weekday)),
                    )
                }
            }
            RoutineRecurrence.MONTHLY -> Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                (1..31).forEach { day ->
                    FilterChip(
                        selected = draft.monthDay == day,
                        onClick = { onChange(draft.copy(monthDay = day)) },
                        label = { Text(day.toString()) },
                        colors = chipColors,
                        modifier = Modifier.testTag(UiTags.routineMonthDay(day)),
                    )
                }
            }
            else -> Unit
        }

        SheetSwitch(
            label = "Me le rappeler",
            checked = draft.remind,
            tag = UiTags.ROUTINE_REMIND,
            onChange = { onChange(draft.copy(remind = it)) },
        )
        if (!draft.isNew) {
            SheetSwitch(
                label = "Active",
                checked = draft.active,
                tag = UiTags.ROUTINE_ACTIVE,
                onChange = { onChange(draft.copy(active = it)) },
            )
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!draft.isNew) {
                TextButton(
                    onClick = { confirmingDelete = true },
                    enabled = !isSaving,
                    modifier = Modifier.testTag(UiTags.ROUTINE_DELETE),
                ) { Text("Supprimer", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = onSave,
                    enabled = draft.canSave && !isSaving,
                    modifier = Modifier.testTag(UiTags.ROUTINE_SAVE),
                ) { Text("Enregistrer") }
            }
        }
    }

    if (pickingTime) {
        RoutineTimeDialog(
            time = draft.anchorTime,
            onPick = { hour, minute -> onChange(draft.copy(anchor = Routine.ANCHOR_TIME, anchorTime = "%02d:%02d".format(hour, minute))) },
            onClose = { pickingTime = false },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Supprimer « ${draft.title.trim()} » ?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                    modifier = Modifier.testTag(UiTags.ROUTINE_DELETE_CONFIRM),
                ) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Annuler") } },
            modifier = Modifier.uiTagRoot(),
        )
    }
}

/** A switch and its word on one line, the whole line tappable. */
@Composable
private fun SheetSwitch(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .testTag(tag)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** The Material 3 clock, 24 h, on [time]; OK hands the time over, either button closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineTimeDialog(time: String, onPick: (hour: Int, minute: Int) -> Unit, onClose: () -> Unit) {
    val (hour, minute) = time.split(':').map { it.toIntOrNull() ?: 0 } + listOf(0, 0)
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onClose,
        text = { InFrench { TimePicker(state = state) } },
        confirmButton = {
            TextButton(
                onClick = {
                    onClose()
                    onPick(state.hour, state.minute)
                },
                modifier = Modifier.testTag(UiTags.PICKER_CONFIRM),
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Annuler") } },
        modifier = Modifier.uiTagRoot(),
    )
}
