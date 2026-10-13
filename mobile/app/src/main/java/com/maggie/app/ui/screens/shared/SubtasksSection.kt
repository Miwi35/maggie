package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskLink

/** What the subtasks section can ask for; the same gestures in the sheet, the pane and the edit screen. */
data class SubtaskActions(
    val onToggleDone: (subtaskId: String, done: Boolean) -> Unit = { _, _ -> },
    val onAdd: (title: String) -> Unit = {},
    val onMoveUp: (subtaskId: String) -> Unit = {},
    val onMoveDown: (subtaskId: String) -> Unit = {},
) {
    companion object {
        fun of(viewModel: SubtasksViewModel) = SubtaskActions(
            onToggleDone = viewModel::toggleDone,
            onAdd = viewModel::add,
            onMoveUp = viewModel::moveUp,
            onMoveDown = viewModel::moveDown,
        )
    }
}

/** The subtasks in Google's order, each ticked on its own, moved up or down, and a field to add one (MAG-404). */
@Composable
fun SubtasksSection(state: SubtasksUiState, actions: SubtaskActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sous-tâches", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (state.isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }

        state.error?.let {
            Text(
                text = "Erreur : $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.subtasks.forEachIndexed { index, subtask ->
            SubtaskRow(
                subtask = subtask,
                canMoveUp = index > 0,
                canMoveDown = index < state.subtasks.lastIndex,
                actions = actions,
            )
        }

        var newTitle by rememberSaveable(state.parentId) { mutableStateOf("") }
        val submit = {
            actions.onAdd(newTitle)
            newTitle = ""
        }
        OutlinedTextField(
            value = newTitle,
            onValueChange = { newTitle = it },
            placeholder = { Text("Ajouter une sous-tâche") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (newTitle.isNotBlank()) submit() }),
            trailingIcon = {
                IconButton(onClick = submit, enabled = newTitle.isNotBlank()) {
                    Icon(Icons.Default.Add, contentDescription = "Ajouter la sous-tâche")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SubtaskRow(subtask: Task, canMoveUp: Boolean, canMoveDown: Boolean, actions: SubtaskActions) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = subtask.isDone,
            onCheckedChange = { actions.onToggleDone(subtask.id, it) },
            modifier = Modifier.semantics { contentDescription = "Terminer « ${subtask.title} »" },
        )
        Text(
            text = subtask.title,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (subtask.isDone) TextDecoration.LineThrough else null,
            color = if (subtask.isDone) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { actions.onMoveUp(subtask.id) }, enabled = canMoveUp) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Monter « ${subtask.title} »")
        }
        IconButton(onClick = { actions.onMoveDown(subtask.id) }, enabled = canMoveDown) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Descendre « ${subtask.title} »")
        }
    }
}

/** What Google attached to the task — a mail, a document. Read only: a tap opens it. */
@Composable
fun TaskLinks(links: List<TaskLink>) {
    if (links.isEmpty()) return
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        links.forEach { link ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    // No app to open it with is not worth a crash.
                    .clickable { runCatching { uriHandler.openUri(link.link) } }
                    .padding(vertical = 4.dp),
            ) {
                Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = link.description?.takeIf { it.isNotBlank() } ?: link.link,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
