package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.TaskCreateRequest
import com.maggie.app.data.model.Task
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.DateField
import com.maggie.app.ui.components.markdown.MarkdownEditor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The one form of a task (MAG-721): « Nouvelle tâche » / « Créer » when [task] is null, « Modifier la tâche » /
 * « Enregistrer » otherwise, with the same fields in the mockup's order. [initialDueDay] pre-fills a new task's
 * due day. Subtasks and « Supprimer » only exist for a task that already does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskFormScreen(
    task: Task?,
    onCreate: (TaskCreateRequest) -> Unit,
    onUpdate: (JsonObject) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtasks: SubtasksUiState = SubtasksUiState(),
    subtaskActions: SubtaskActions = SubtaskActions(),
    initialDueDay: String? = null,
) {
    var title by remember { mutableStateOf(task?.title.orEmpty()) }
    var description by remember { mutableStateOf(task?.description.orEmpty()) }
    var priority by remember { mutableStateOf(task?.priority ?: "medium") }
    var criticality by remember { mutableStateOf(task?.criticality ?: "low") }
    var dueDate by remember { mutableStateOf(TaskDueDate.fromApi(task?.dueDate ?: initialDueDay)) }
    var estimate by remember { mutableStateOf(task?.estimatedMinutes?.toString().orEmpty()) }
    var firstStep by remember { mutableStateOf(task?.firstStep.orEmpty()) }
    val canSave = title.isNotBlank() && TaskEstimate.isValid(estimate)

    fun save() {
        if (task == null) {
            onCreate(
                TaskCreateRequest(
                    title = title,
                    description = description.ifBlank { null },
                    priority = priority,
                    criticality = criticality,
                    dueDate = TaskDueDate.toApi(dueDate),
                    estimatedMinutes = TaskEstimate.toApi(estimate),
                    firstStep = TaskEstimate.firstStepToApi(firstStep),
                ),
            )
        } else {
            onUpdate(
                buildJsonObject {
                    put("title", title)
                    put("description", description.ifBlank { null })
                    put("priority", priority)
                    put("criticality", criticality)
                    put("dueDate", TaskDueDate.toApi(dueDate))
                    // An emptied field clears the task's: null is sent, not left out.
                    put("estimatedMinutes", TaskEstimate.toApi(estimate))
                    put("firstStep", TaskEstimate.firstStepToApi(firstStep))
                },
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (task == null) "Nouvelle tâche" else "Modifier la tâche") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Fermer")
                    }
                },
                actions = {
                    TextButton(onClick = ::save, enabled = canSave, modifier = Modifier.testTag(UiTags.TASK_FORM_SUBMIT)) {
                        Text(if (task == null) "Créer" else "Enregistrer")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Titre *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.TASK_FORM_TITLE),
            )

            DateField(
                label = "Échéance",
                value = dueDate,
                onValueChange = { dueDate = it },
                onClear = { dueDate = null },
                modifier = Modifier.fillMaxWidth(),
            )

            DropdownField(
                label = "Priorité",
                value = priority,
                options = TaskConstants.PRIORITY_LABELS,
                onValueChange = { priority = it },
            )

            DropdownField(
                label = "Criticité",
                value = criticality,
                options = TaskConstants.CRITICALITY_LABELS,
                onValueChange = { criticality = it },
            )

            EstimateFields(
                estimate = estimate,
                onEstimateChange = { estimate = it },
                firstStep = firstStep,
                onFirstStepChange = { firstStep = it },
            )

            MarkdownEditor(
                value = description,
                onValueChange = { description = it },
                label = "Notes",
                modifier = Modifier.testTag(UiTags.TASK_NOTES),
            )

            // Subtasks apply at once: « Enregistrer » is for the fields above.
            if (task != null && !task.isSubtask) {
                SubtasksSection(state = subtasks, actions = subtaskActions)
            }

            if (task != null) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.TASK_FORM_DELETE),
                ) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
