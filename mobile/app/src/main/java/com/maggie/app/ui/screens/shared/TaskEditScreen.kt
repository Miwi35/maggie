package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Task
import com.maggie.app.ui.components.DateField
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(
    task: Task,
    subtasks: SubtasksUiState,
    subtaskActions: SubtaskActions,
    onConfirm: (JsonObject) -> Unit,
    onBack: () -> Unit,
) {
    var title by remember { mutableStateOf(task.title) }
    var description by remember { mutableStateOf(task.description ?: "") }
    var priority by remember { mutableStateOf(task.priority) }
    var criticality by remember { mutableStateOf(task.criticality) }
    var done by remember { mutableStateOf(task.isDone) }
    var dueDate by remember { mutableStateOf(TaskDueDate.fromApi(task.dueDate)) }
    var estimate by remember { mutableStateOf(task.estimatedMinutes?.toString().orEmpty()) }
    var firstStep by remember { mutableStateOf(task.firstStep.orEmpty()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modifier la tâche") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
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
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Notes") },
                minLines = 2,
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

            DateField(
                label = "Date d'échéance",
                value = dueDate,
                onValueChange = { dueDate = it },
                onClear = { dueDate = null },
                modifier = Modifier.fillMaxWidth(),
            )

            EstimateFields(
                estimate = estimate,
                onEstimateChange = { estimate = it },
                firstStep = firstStep,
                onFirstStepChange = { firstStep = it },
            )

            // Subtasks apply at once, as in the sheet: « Enregistrer » is for the fields above.
            if (!task.isSubtask) {
                SubtasksSection(state = subtasks, actions = subtaskActions)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Terminée")
                Switch(checked = done, onCheckedChange = { done = it })
            }

            Button(
                onClick = {
                    onConfirm(buildJsonObject {
                        put("title", title)
                        put("description", description.ifBlank { null })
                        put("priority", priority)
                        put("criticality", criticality)
                        put("dueDate", TaskDueDate.toApi(dueDate))
                        put("completedAt", if (done) Instant.now().toString() else null)
                        // An emptied field clears the task's: null is sent, not left out.
                        put("estimatedMinutes", TaskEstimate.toApi(estimate))
                        put("firstStep", TaskEstimate.firstStepToApi(firstStep))
                    })
                },
                enabled = title.isNotBlank() && TaskEstimate.isValid(estimate),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Enregistrer")
            }
        }
    }
}
