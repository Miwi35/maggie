package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.markdown.MarkdownEditor
import com.maggie.app.util.RruleUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId

/** What the fields of the event form hold, whether the event is new or being edited (MAG-649). */
data class EventFormFields(
    val summary: String,
    val dates: EventFormState,
    val rrule: String? = null,
    val reminders: EventReminders? = null,
    val agendaIri: String? = null,
    val location: String = "",
    val description: String = "",
) {
    /** A title, and an end that does not precede the start. */
    val canSave: Boolean get() = summary.isNotBlank() && !dates.endsBeforeStart

    fun toCreateRequest(zone: ZoneId) = EventCreateRequest(
        summary = summary,
        startAt = dates.startAt(zone),
        endAt = dates.endAt(zone),
        startDate = dates.allDayStartDate,
        endDate = dates.allDayEndDate,
        allDay = dates.allDay,
        description = description.ifBlank { null },
        location = location.ifBlank { null },
        agenda = agendaIri,
        rrule = rrule,
        reminders = reminders,
    )

    /**
     * The PATCH of an edit. Both pairs of bounds go, the unused one null (MAG-382); reminders
     * go null when none is left, which is how the API clears them; `rrule` goes only when a
     * one-off event was made to repeat.
     */
    fun toPatch(zone: ZoneId): JsonObject = buildJsonObject {
        put("summary", summary)
        dates.patch(zone).forEach { (key, value) -> put(key, value) }
        put("description", description.ifBlank { null })
        put("location", location.ifBlank { null })
        put("agenda", agendaIri)
        put(
            "reminders",
            reminders?.let { Json.encodeToJsonElement(EventReminders.serializer(), it) } ?: JsonNull,
        )
        rrule?.let { put("rrule", it) }
    }

    companion object {
        fun forNewEvent(date: LocalDate, agendas: List<Agenda>) = EventFormFields(
            summary = "",
            dates = EventFormState.forNewEvent(date),
            agendaIri = agendas.find { it.isDefault }?.let { "/api/agendas/${it.id}" },
        )

        fun fromEvent(event: ExpandedEvent) = EventFormFields(
            summary = event.summary,
            dates = EventFormState.fromEvent(event),
            reminders = event.reminders,
            agendaIri = event.agendaIri,
            location = event.location ?: "",
            description = event.description ?: "",
        )
    }
}

/**
 * The one screen an event is created and edited on: × and the title on the left, the pill
 * (« Créer » / « Enregistrer ») on the right, then Titre, Toute la journée, Début, Fin,
 * Répéter, Rappels, Agenda, Lieu, Description.
 *
 * @param seriesRrule set on an occurrence of a series: the rule is the series', shown and not editable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventFormScreen(
    title: String,
    submitLabel: String,
    initial: EventFormFields,
    agendas: List<Agenda>,
    seriesRrule: String? = null,
    onSubmit: (EventFormFields) -> Unit,
    onClose: () -> Unit,
) {
    var fields by remember { mutableStateOf(initial) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Fermer")
                    }
                },
                actions = {
                    Button(
                        onClick = { onSubmit(fields) },
                        enabled = fields.canSave,
                        shape = CircleShape,
                        modifier = Modifier.padding(end = 8.dp).testTag(UiTags.EVENT_SAVE),
                    ) {
                        Text(submitLabel)
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
                value = fields.summary,
                onValueChange = { fields = fields.copy(summary = it) },
                label = { Text("Titre") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.EVENT_TITLE),
            )

            EventDateFields(form = fields.dates, onChange = { fields = fields.copy(dates = it) })

            if (seriesRrule != null) {
                OutlinedTextField(
                    value = RruleUtils.rruleToFrenchText(seriesRrule),
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text("Répéter") },
                    supportingText = { Text("La règle de la série ne se change pas depuis une occurrence.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                RecurrencePicker(
                    value = fields.rrule,
                    onChange = { fields = fields.copy(rrule = it) },
                    eventStartDate = fields.dates.startDate,
                )
            }

            ReminderPicker(value = fields.reminders, onChange = { fields = fields.copy(reminders = it) })

            AgendaPickerField(
                agendas = agendas,
                selectedAgendaIri = fields.agendaIri,
                onAgendaSelected = { fields = fields.copy(agendaIri = it) },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = fields.location,
                onValueChange = { fields = fields.copy(location = it) },
                label = { Text("Lieu") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            MarkdownEditor(
                value = fields.description,
                onValueChange = { fields = fields.copy(description = it) },
                label = "Description",
                modifier = Modifier.testTag(UiTags.EVENT_DESCRIPTION),
            )
        }
    }
}
