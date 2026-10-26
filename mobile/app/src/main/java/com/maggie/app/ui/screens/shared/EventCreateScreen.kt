package com.maggie.app.ui.screens.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.maggie.app.data.api.EventCreateRequest
import com.maggie.app.data.model.Agenda
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun EventCreateScreen(
    agendas: List<Agenda>,
    initialDate: LocalDate? = null,
    onConfirm: (EventCreateRequest) -> Unit,
    onBack: () -> Unit,
) {
    val initial = remember { EventFormFields.forNewEvent(initialDate ?: LocalDate.now(), agendas) }

    EventFormScreen(
        title = "Nouvel événement",
        submitLabel = "Créer",
        initial = initial,
        agendas = agendas,
        onSubmit = { onConfirm(it.toCreateRequest(ZoneId.of("Europe/Paris"))) },
        onClose = onBack,
    )
}
