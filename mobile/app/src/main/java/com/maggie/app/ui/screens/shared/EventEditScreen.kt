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
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.EventReminders
import com.maggie.app.data.model.ExpandedEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditScreen(
    event: ExpandedEvent,
    agendas: List<Agenda>,
    onConfirm: (JsonObject) -> Unit,
    onBack: () -> Unit,
) {
    val zone = ZoneId.of(event.timeZone)
    val startZdt = ZonedDateTime.ofInstant(Instant.parse(event.startAt), zone)
    val endZdt = ZonedDateTime.ofInstant(Instant.parse(event.endAt), zone)

    var summary by remember { mutableStateOf(event.summary) }
    var description by remember { mutableStateOf(event.description ?: "") }
    var location by remember { mutableStateOf(event.location ?: "") }
    var allDay by remember { mutableStateOf(event.allDay) }
    var startDate by remember { mutableStateOf(startZdt.toLocalDate().toString()) }
    var startTime by remember { mutableStateOf(startZdt.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))) }
    var endDate by remember { mutableStateOf(endZdt.toLocalDate().toString()) }
    var endTime by remember { mutableStateOf(endZdt.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))) }
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Toute la journée")
                Switch(checked = allDay, onCheckedChange = { allDay = it })
            }

            OutlinedTextField(
                value = startDate,
                onValueChange = { startDate = it },
                label = { Text("Date de début") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!allDay) {
                OutlinedTextField(
                    value = startTime,
                    onValueChange = { startTime = it },
                    label = { Text("Heure de début") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = endDate,
                onValueChange = { endDate = it },
                label = { Text("Date de fin") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!allDay) {
                OutlinedTextField(
                    value = endTime,
                    onValueChange = { endTime = it },
                    label = { Text("Heure de fin") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

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
                    val newStartAt = if (allDay) {
                        LocalDate.parse(startDate).atStartOfDay(zone).toInstant().toString()
                    } else {
                        ZonedDateTime.of(LocalDate.parse(startDate), LocalTime.parse(startTime), zone).toInstant().toString()
                    }
                    val newEndAt = if (allDay) {
                        LocalDate.parse(endDate).plusDays(1).atStartOfDay(zone).toInstant().toString()
                    } else {
                        ZonedDateTime.of(LocalDate.parse(endDate), LocalTime.parse(endTime), zone).toInstant().toString()
                    }
                    onConfirm(buildJsonObject {
                        put("summary", summary)
                        put("startAt", newStartAt)
                        put("endAt", newEndAt)
                        put("allDay", allDay)
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
                enabled = summary.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Enregistrer")
            }
        }
    }
}
