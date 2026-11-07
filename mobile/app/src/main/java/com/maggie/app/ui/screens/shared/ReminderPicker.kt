package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import com.maggie.app.data.model.EventReminders
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.FIELD_RADIUS
import com.maggie.app.ui.components.LabeledField
import com.maggie.app.ui.components.LabeledTextField
import com.maggie.app.ui.components.filledFieldColors
import com.maggie.app.ui.uiTagRoot
import com.maggie.app.data.model.remindersFrom
import com.maggie.app.data.model.remindersOf

private data class Delay(val minutes: Int, val label: String)

/** The delays offered, in minutes before the start. Zero is not one: the cron skips it. */
private val DELAYS = listOf(
    Delay(5, "5 minutes avant"),
    Delay(10, "10 minutes avant"),
    Delay(15, "15 minutes avant"),
    Delay(30, "30 minutes avant"),
    Delay(60, "1 heure avant"),
    Delay(120, "2 heures avant"),
    Delay(1440, "1 jour avant"),
    Delay(2880, "2 jours avant"),
    Delay(10080, "1 semaine avant"),
)

/** As many as Google accepts on one event, and as many as the API validates. */
private const val MAX_REMINDERS = 5

private const val DEFAULT_DELAY = 30

/** The label of a delay, falling back to the raw minutes for anything Google sent. */
fun reminderDelayLabel(minutes: Int): String =
    DELAYS.find { it.minutes == minutes }?.label ?: "$minutes minutes avant"

/** The reminders of an event in French, or null when it has none. */
fun remindersText(reminders: EventReminders?): String? =
    remindersOf(reminders).takeIf { it.isNotEmpty() }?.joinToString(", ") { reminderDelayLabel(it) }

/** What an event offers, as Google does: minutes before the start; anything else is « Personnaliser… ». */
private val EVENT_OPTIONS = listOf(5, 10, 15, 30, 60, 1440)

/**
 * The reminders of an event in one « Rappels » field (MAG-719): it opens a sheet of boxes, several
 * can be ticked, and « Personnaliser… » adds a delay of one's own. What it emits is the shape the
 * API stores — built by `remindersFrom`, never by hand, because a bare list stores fine and
 * fires nothing. Controlled: the list of delays is the whole state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderPicker(
    value: EventReminders?,
    onChange: (EventReminders?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val minutes = remindersOf(value)
    val text = remindersText(value) ?: "Aucun"
    val shown = (EVENT_OPTIONS + minutes.filter { it !in EVENT_OPTIONS }.sorted())
    val full = minutes.size >= MAX_REMINDERS

    LabeledField(label = "Rappels", modifier = modifier.fillMaxWidth()) {
        Box {
            TextField(
                value = text,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                shape = RoundedCornerShape(FIELD_RADIUS),
                colors = filledFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .testTag(UiTags.EVENT_REMINDERS)
                    .semantics { contentDescription = "Rappels, $text" }
                    .clickable(onClickLabel = "Choisir : Rappels", role = Role.Button) { open = true },
            )
        }
    }

    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, modifier = Modifier.uiTagRoot()) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                shown.forEach { delay ->
                    val checked = delay in minutes
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = checked,
                                enabled = checked || !full,
                                role = Role.Checkbox,
                                onValueChange = { on ->
                                    val next = if (on) (minutes + delay).sorted() else minutes - delay
                                    onChange(remindersFrom(next))
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = checked || !full)
                        Text(reminderDelayLabel(delay), modifier = Modifier.padding(start = 16.dp))
                    }
                }
                TextButton(
                    onClick = {
                        open = false
                        custom = true
                    },
                    enabled = !full,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("Personnaliser…") }
            }
        }
    }

    if (custom) {
        CustomReminderDialog(
            onConfirm = { delay ->
                custom = false
                if (delay > 0 && delay !in minutes) onChange(remindersFrom((minutes + delay).sorted()))
            },
            onDismiss = { custom = false },
        )
    }
}

private val CUSTOM_UNITS = listOf("minutes" to 1, "heures" to 60, "jours" to 1440)

/** A delay of one's own: a number and its unit, handed over in minutes. */
@Composable
private fun CustomReminderDialog(onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("30") }
    var unit by remember { mutableStateOf(CUSTOM_UNITS.first().second) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rappel personnalisé") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledTextField(
                    label = "Délai avant le début",
                    value = amount,
                    onValueChange = { amount = it.filter(Char::isDigit).take(4) },
                    modifier = Modifier.testTag(UiTags.EVENT_REMINDER_CUSTOM),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CUSTOM_UNITS.forEach { (label, factor) ->
                        FilterChip(selected = unit == factor, onClick = { unit = factor }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm((amount.toIntOrNull() ?: 0) * unit) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        modifier = Modifier.uiTagRoot(),
    )
}

/** « À l'heure »: a task's reminder at its very time (MAG-718), which an event's list does not offer. */
private val TASK_DELAYS = listOf(Delay(0, "À l'heure")) + DELAYS

/** A task's reminder delay in French (MAG-718): « À l'heure » for 0, else as an event's. */
fun taskReminderLabel(minutes: Int): String =
    TASK_DELAYS.find { it.minutes == minutes }?.label ?: reminderDelayLabel(minutes)

/**
 * The reminders of a task at a precise time (MAG-718), in the Agenda's format: the same rows as
 * [ReminderPicker], over the bare list of minutes the task stores, « À l'heure » first.
 */
@Composable
fun TaskReminderPicker(
    minutes: List<Int>,
    onChange: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    ReminderRows(minutes = minutes, delays = TASK_DELAYS, emit = onChange, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderRows(
    minutes: List<Int>,
    delays: List<Delay>,
    emit: (List<Int>) -> Unit,
    modifier: Modifier,
) {
    fun labelOf(minutes: Int) = delays.find { it.minutes == minutes }?.label ?: reminderDelayLabel(minutes)
    val nextDelay = if (DEFAULT_DELAY in minutes) {
        delays.firstOrNull { it.minutes !in minutes }?.minutes
    } else {
        DEFAULT_DELAY
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Rappels", style = MaterialTheme.typography.bodyMedium)

        if (minutes.isEmpty()) {
            Text(
                "Aucun rappel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        minutes.forEachIndexed { index, chosen ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = labelOf(chosen),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Rappel") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.uiTagRoot(),
                    ) {
                        delays.forEach { delay ->
                            DropdownMenuItem(
                                text = { Text(delay.label) },
                                // A delay already chosen is not offered twice.
                                enabled = delay.minutes == chosen || delay.minutes !in minutes,
                                onClick = {
                                    emit(minutes.mapIndexed { i, m -> if (i == index) delay.minutes else m })
                                    expanded = false
                                },
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { emit(minutes.filterIndexed { i, _ -> i != index }) },
                    modifier = Modifier.semantics {
                        contentDescription = "Supprimer le rappel ${labelOf(chosen)}"
                    },
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                }
            }
        }

        if (minutes.size < MAX_REMINDERS && nextDelay != null) {
            TextButton(onClick = { emit(minutes + nextDelay) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Ajouter un rappel")
            }
        }
    }
}
