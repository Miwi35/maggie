package com.maggie.app.ui.screens.shared

import androidx.compose.runtime.Composable
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ConfirmDeleteDialog

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
    ConfirmDeleteDialog(
        title = taskDeleteTitle(title, subtaskCount),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmLabel = "Supprimer",
        confirmTag = UiTags.TASK_DELETE_CONFIRM,
        cancelTag = UiTags.TASK_DELETE_CANCEL,
    )
}
