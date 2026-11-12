package com.maggie.app.ui.screens.shared

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot

fun taskDeleteTitle(title: String, subtaskCount: Int): String {
    val subtasks = when {
        subtaskCount <= 0 -> ""
        subtaskCount == 1 -> " et sa sous-tâche"
        else -> " et ses $subtaskCount sous-tâches"
    }
    return "Supprimer la tâche « $title »$subtasks ?"
}

@Composable
fun TaskDeleteDialog(
    title: String,
    subtaskCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.uiTagRoot(),
        onDismissRequest = onDismiss,
        title = { Text(taskDeleteTitle(title, subtaskCount)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(UiTags.TASK_DELETE_CONFIRM)) {
                Text("Supprimer", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(UiTags.TASK_DELETE_CANCEL)) { Text("Annuler") }
        },
    )
}
