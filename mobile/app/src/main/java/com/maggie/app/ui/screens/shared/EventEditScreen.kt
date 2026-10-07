package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.EventReminders
import com.maggie.app.data.model.ExpandedEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditScreen(
    event: ExpandedEvent,
    agendas: List<Agenda>,
    onConfirm: (JsonObject) -> Unit,
    onBack: () -> Unit,
) {
    val zone = ZoneId.of(event.timeZone)

    var summary by remember { mutableStateOf(event.summary) }
    var description by remember { mutableStateOf(event.description ?: "") }
    var location by remember { mutableStateOf(event.location ?: "") }
    var dates by remember { mutableStateOf(EventFormState.fromEvent(event)) }
    var selectedAgendaIri by remember { mutableStateOf(event.agendaIri) }
    var reminders by remember { mutableStateOf<EventReminders?>(event.reminders) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modifier l'événement") },
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
                value = summary,
                onValueChange = { summary = it },
                label = { Text("Titre *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            EventDateFields(form = dates, onChange = { dates = it })

            ReminderPicker(value = reminders, onChange = { reminders = it })

            AgendaPickerField(
                agendas = agendas,
                selectedAgendaIri = selectedAgendaIri,
                onAgendaSelected = { selectedAgendaIri = it },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("Lieu") },
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

            Button(
                onClick = {
                    onConfirm(buildJsonObject {
                        put("summary", summary)
                        put("startAt", dates.startAt(zone))
                        put("endAt", dates.endAt(zone))
                        put("allDay", dates.allDay)
                        put("description", description.ifBlank { null })
                        put("location", location.ifBlank { null })
                        put("agenda", selectedAgendaIri)
                        // Null is how the API clears the field, so a form left
                        // without a reminder removes the ones the event had.
                        put(
                            "reminders",
                            reminders?.let { Json.encodeToJsonElement(EventReminders.serializer(), it) } ?: JsonNull,
                        )
                    })
                },
                enabled = summary.isNotBlank() && !dates.endsBeforeStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Enregistrer")
            }
        }
    }
}
