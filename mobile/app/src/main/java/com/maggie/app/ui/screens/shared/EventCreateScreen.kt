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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.EventCreateRequest
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.EventReminders
import com.maggie.app.ui.UiTags
import java.time.LocalDate
import java.time.ZoneId

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
    var dates by remember { mutableStateOf(EventFormState.forNewEvent(initialDate ?: LocalDate.now())) }
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
                modifier = Modifier.fillMaxWidth().testTag(UiTags.EVENT_TITLE),
            )

            EventDateFields(form = dates, onChange = { dates = it })

            RecurrencePicker(
                value = rrule,
                onChange = { rrule = it },
                eventStartDate = dates.startDate,
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
                    onConfirm(
                        EventCreateRequest(
                            summary = summary,
                            startAt = dates.startAt(zone),
                            endAt = dates.endAt(zone),
                            allDay = dates.allDay,
                            description = description.ifBlank { null },
                            location = location.ifBlank { null },
                            agenda = selectedAgendaIri,
                            rrule = rrule,
                            reminders = reminders,
                        ),
                    )
                },
                enabled = summary.isNotBlank() && !dates.endsBeforeStart,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.EVENT_SAVE),
            ) {
                Text("Créer")
            }
        }
    }
}
