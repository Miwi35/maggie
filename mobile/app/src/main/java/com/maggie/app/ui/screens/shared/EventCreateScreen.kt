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
import com.maggie.app.data.api.EventCreateRequest
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.EventReminders
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventCreateScreen(
    agendas: List<Agenda>,
    initialDate: LocalDate? = null,
    onConfirm: (EventCreateRequest) -> Unit,
    onBack: () -> Unit,
) {
    var summary by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var allDay by remember { mutableStateOf(false) }
    var startDate by remember { mutableStateOf(initialDate?.toString() ?: LocalDate.now().toString()) }
    var startTime by remember { mutableStateOf("09:00") }
    var endDate by remember { mutableStateOf(initialDate?.toString() ?: LocalDate.now().toString()) }
    var endTime by remember { mutableStateOf("10:00") }
    var selectedAgendaIri by remember { mutableStateOf<String?>(agendas.find { it.isDefault }?.let { "/api/agendas/${it.id}" }) }
    var rrule by remember { mutableStateOf<String?>(null) }
    var reminders by remember { mutableStateOf<EventReminders?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nouvel événement") },
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

            RecurrencePicker(
                value = rrule,
                onChange = { rrule = it },
                eventStartDate = try { LocalDate.parse(startDate) } catch (_: Exception) { null },
            )

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
                    val zone = ZoneId.of("Europe/Paris")
                    val startAt = if (allDay) {
                        LocalDate.parse(startDate).atStartOfDay(zone).toInstant().toString()
                    } else {
                        ZonedDateTime.of(LocalDate.parse(startDate), LocalTime.parse(startTime), zone).toInstant().toString()
                    }
                    val endAt = if (allDay) {
                        LocalDate.parse(endDate).plusDays(1).atStartOfDay(zone).toInstant().toString()
                    } else {
                        ZonedDateTime.of(LocalDate.parse(endDate), LocalTime.parse(endTime), zone).toInstant().toString()
                    }
                    onConfirm(
                        EventCreateRequest(
                            summary = summary,
                            startAt = startAt,
                            endAt = endAt,
                            allDay = allDay,
                            description = description.ifBlank { null },
                            location = location.ifBlank { null },
                            agenda = selectedAgendaIri,
                            rrule = rrule,
                            reminders = reminders,
                        ),
                    )
                },
                enabled = summary.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Créer")
            }
        }
    }
}
