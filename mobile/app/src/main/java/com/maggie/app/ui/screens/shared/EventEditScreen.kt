package com.maggie.app.ui.screens.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.ExpandedEvent
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId

@Composable
fun EventEditScreen(
    event: ExpandedEvent,
    agendas: List<Agenda>,
    onConfirm: (JsonObject) -> Unit,
    onBack: () -> Unit,
) {
    val initial = remember(event) { EventFormFields.fromEvent(event) }

    EventFormScreen(
        title = "Modifier l'événement",
        submitLabel = "Enregistrer",
        initial = initial,
        agendas = agendas,
        seriesRrule = event.masterRrule,
        onSubmit = { onConfirm(it.toPatch(ZoneId.of(event.timeZone))) },
        onClose = onBack,
    )
}
