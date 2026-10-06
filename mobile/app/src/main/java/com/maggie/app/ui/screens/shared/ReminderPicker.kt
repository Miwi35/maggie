package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.EventReminders
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

/**
 * The reminders of an event: a row per delay, added and removed one at a time.
 *
 * Until MAG-121 nothing but Google's import could fill this field, so the app
 * could show a reminder and never set one. What it emits is the shape the API
 * stores — built by `remindersFrom`, never by hand, because a bare list stores
 * fine and fires nothing.
 *
 * Controlled with no state of its own beyond which dropdown is open: the whole
 * state is the list of delays, which the screen owning the form already holds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderPicker(
    value: EventReminders?,
    onChange: (EventReminders?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val minutes = remindersOf(value)
    val emit: (List<Int>) -> Unit = { onChange(remindersFrom(it)) }
    val nextDelay = if (DEFAULT_DELAY in minutes) {
        DELAYS.firstOrNull { it.minutes !in minutes }?.minutes
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
                        value = reminderDelayLabel(chosen),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Rappel") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DELAYS.forEach { delay ->
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
                        contentDescription = "Supprimer le rappel ${reminderDelayLabel(chosen)}"
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
