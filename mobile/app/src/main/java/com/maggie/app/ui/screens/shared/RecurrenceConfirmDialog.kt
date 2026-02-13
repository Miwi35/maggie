package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

enum class RecurrenceAction {
    THIS,
    THIS_AND_FOLLOWING,
    ALL,
}

@Composable
fun RecurrenceConfirmDialog(
    isDelete: Boolean,
    onConfirm: (RecurrenceAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(RecurrenceAction.THIS) }

    val title = if (isDelete) "Supprimer l'événement récurrent" else "Modifier l'événement récurrent"
    val options = listOf(
        RecurrenceAction.THIS to "Cet événement",
        RecurrenceAction.THIS_AND_FOLLOWING to "Cet événement et tous les suivants",
        RecurrenceAction.ALL to "Tous les événements",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (action, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected == action,
                                onClick = { selected = action },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == action,
                            onClick = null,
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(if (isDelete) "Supprimer" else "Modifier")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        },
    )
}
