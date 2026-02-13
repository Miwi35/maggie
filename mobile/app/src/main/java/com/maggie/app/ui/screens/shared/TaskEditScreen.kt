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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(
    task: Task,
    onConfirm: (JsonObject) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(task.name) }
    var description by remember { mutableStateOf(task.description ?: "") }
    var priority by remember { mutableStateOf(task.priority) }
    var criticality by remember { mutableStateOf(task.criticality) }
    var done by remember { mutableStateOf(task.isDone) }
    var dueDate by remember {
        mutableStateOf(
            task.dueDate?.let {
                try {
                    ZonedDateTime.ofInstant(Instant.parse(it), ZoneId.of("Europe/Paris"))
                        .toLocalDate().toString()
                } catch (_: Exception) { "" }
            } ?: "",
        )
    }

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
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
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

            OutlinedTextField(
                value = dueDate,
                onValueChange = { dueDate = it },
                label = { Text("Date d'échéance (YYYY-MM-DD)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

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
                    val dueDateIso = if (dueDate.isNotBlank()) {
                        try {
                            java.time.LocalDate.parse(dueDate)
                                .atStartOfDay(java.time.ZoneId.of("Europe/Paris"))
                                .toInstant().toString()
                        } catch (_: Exception) { null }
                    } else null
                    onConfirm(buildJsonObject {
                        put("name", name)
                        put("description", description.ifBlank { null })
                        put("priority", priority)
                        put("criticality", criticality)
                        put("dueDate", dueDateIso)
                        put("doneDate", if (done) Instant.now().toString() else null)
                    })
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Enregistrer")
            }
        }
    }
}
