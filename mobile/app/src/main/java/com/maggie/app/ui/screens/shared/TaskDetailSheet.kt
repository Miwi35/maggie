package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Task
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot
import com.maggie.app.ui.components.markdown.MarkdownText
import com.maggie.app.util.DateRanges
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dueDateFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailSheet(
    task: Task,
    subtasks: SubtasksUiState,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onToggleDone: (Boolean) -> Unit,
    onDescriptionChange: ((String) -> Unit)? = null,
) {
    TaskDetailContent(
        task = task,
        subtasks = subtasks,
        onEdit = onEdit,
        onToggleDone = onToggleDone,
        onDescriptionChange = onDescriptionChange,
        onBack = onDismiss,
        // A long list of subtasks must not push the actions off the sheet.
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) { body ->
        ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.uiTagRoot()) { body() }
    }
}

/**
 * A task read, never edited (MAG-721): ← (when [onBack] is given) and the pencil in the header, then the rows of the
 * mockup, the notes whose boxes can be ticked, the subtasks as lines and « Marquer comme faite ». Every change goes
 * through the pencil's form; [frame] wraps it as in [EventDetailContent].
 */
@Composable
fun TaskDetailContent(
    task: Task,
    subtasks: SubtasksUiState,
    onEdit: () -> Unit,
    onToggleDone: (Boolean) -> Unit,
    onDescriptionChange: ((String) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    frame: @Composable (body: @Composable () -> Unit) -> Unit = { body -> body() },
) {
    frame {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onEdit, modifier = Modifier.testTag(UiTags.TASK_DETAIL_EDIT)) {
                    Icon(Icons.Default.Edit, contentDescription = "Modifier")
                }
            }

            // « Reportée », never « en retard » (MAG-497).
            if (!task.isDone) {
                TaskPostponed.labelOf(task.postponedSince, task.urgency, DateRanges.todayDate())?.let { PostponedLabel(it) }
            }

            // Due day: a plain date, no zone to read it in (MAG-404).
            task.dueDay?.let { day ->
                DetailRow("Échéance", day.format(dueDateFormatter).replaceFirstChar { it.uppercase() })
            }
            DetailRow("Criticité", TaskConstants.CRITICALITY_LABELS[task.criticality] ?: task.criticality)
            DetailRow("Priorité", TaskConstants.PRIORITY_LABELS[task.priority] ?: task.priority)
            task.estimatedMinutes?.let { DetailRow("Durée estimée", minutesLabel(it)) }
            task.firstStep?.takeIf { it.isNotBlank() }?.let { DetailRow("Premier pas", it) }

            // Urgency: computed by the API, read only (MAG-406).
            if (task.urgency != null && TaskConstants.URGENCY_LABELS.containsKey(task.urgency)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Urgence :",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    UrgencyBadge(task.urgency)
                }
            }

            if (!task.description.isNullOrBlank()) {
                MarkdownText(
                    text = task.description,
                    onTextChange = onDescriptionChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            TaskLinks(task.links)

            // One level only: a subtask has none of its own.
            if (!task.isSubtask && subtasks.subtasks.isNotEmpty()) {
                Text("Sous-tâches", style = MaterialTheme.typography.titleSmall)
                subtasks.subtasks.forEach { subtask ->
                    Text(
                        text = subtask.title,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (subtask.isDone) TextDecoration.LineThrough else null,
                    )
                }
            }

            Button(
                onClick = { onToggleDone(!task.isDone) },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.TASK_DETAIL_DONE),
            ) {
                Text(if (task.isDone) "Marquer comme non faite" else "Marquer comme faite")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
