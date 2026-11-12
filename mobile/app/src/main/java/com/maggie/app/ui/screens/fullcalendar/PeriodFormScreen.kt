package com.maggie.app.ui.screens.fullcalendar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Agenda
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ConfirmDeleteDialog
import com.maggie.app.ui.components.FIELD_RADIUS
import com.maggie.app.ui.components.LabeledField
import com.maggie.app.ui.components.LabeledTextField
import com.maggie.app.ui.components.filledFieldColors
import com.maggie.app.ui.screens.shared.AgendaPickerField
import com.maggie.app.ui.screens.shared.EventDateFields
import com.maggie.app.ui.screens.shared.RecurrencePicker
import com.maggie.app.util.RruleUtils

/**
 * The full-screen « Période » of a phone, on the list's ViewModel. A period that no longer exists does not open: the
 * list says why.
 */
@Composable
fun PeriodFormScreen(
    viewModel: PeriodViewModel,
    periodId: String?,
    onClose: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    // Opened once per visit; a rotation finds the form, and what was typed, still in the ViewModel.
    LaunchedEffect(periodId, uiState.hasLoaded) {
        val form = viewModel.uiState.value.form
        if (form == null || form.periodId != periodId) {
            when {
                periodId == null -> viewModel.openNew()
                viewModel.uiState.value.hasLoaded -> viewModel.openEdit(periodId)
            }
        }
    }

    val form = uiState.form
    LaunchedEffect(form?.done) {
        if (form?.done == true) {
            viewModel.closeForm()
            onClose()
        }
    }
    LaunchedEffect(uiState.error, uiState.hasLoaded) {
        if (periodId != null && uiState.hasLoaded && uiState.form == null && uiState.error == PeriodViewModel.MESSAGE_NOT_FOUND) onClose()
    }
    if (form == null) return

    val close = {
        viewModel.closeForm()
        onClose()
    }
    BackHandler(onBack = close)

    PeriodFormContent(
        form = form,
        agendas = uiState.agendas,
        onEdit = viewModel::editForm,
        onSave = viewModel::save,
        onDelete = { form.periodId?.let(viewModel::delete) },
        onClose = close,
    )
}

/**
 * The form: × and « Période » on the left, « Enregistrer » on the right, then Nom, Agenda, the dates, Répéter and
 * « Supprimer la période ». [onClose] is null in a tablet's pane, which has no × to leave by.
 */
@Composable
internal fun PeriodFormContent(
    form: PeriodForm,
    agendas: List<Agenda>,
    onEdit: ((PeriodForm) -> PeriodForm) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val errors = form.shownErrors

    if (confirmingDelete) {
        ConfirmDeleteDialog(
            title = "Supprimer « ${form.name} » ?",
            confirmLabel = "Supprimer",
            confirmTag = UiTags.PERIOD_DELETE_CONFIRM,
            onConfirm = {
                confirmingDelete = false
                onDelete()
            },
            onDismiss = { confirmingDelete = false },
        )
    }

    Column(modifier = modifier.fillMaxSize().testTag(UiTags.PERIOD_FORM)) {
        Row(
            modifier = Modifier
                .then(if (onClose != null) Modifier.statusBarsPadding() else Modifier)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onClose != null) {
                IconButton(onClick = onClose, modifier = Modifier.testTag(UiTags.PERIOD_CLOSE)) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer")
                }
            }
            Text(
                "Période",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = if (onClose != null) 0.dp else 8.dp),
            )
            Button(
                onClick = onSave,
                enabled = !form.isSaving,
                shape = CircleShape,
                modifier = Modifier.padding(end = 8.dp).testTag(UiTags.PERIOD_SAVE),
            ) { Text("Enregistrer") }
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            form.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(UiTags.PERIOD_FORM_ERROR))
            }

            LabeledTextField(
                label = "Nom",
                value = form.name,
                onValueChange = { value -> onEdit { it.copy(name = value) } },
                modifier = Modifier.testTag(UiTags.PERIOD_NAME),
            )
            errors[PeriodField.NAME]?.let { ErrorLine(it) }

            AgendaPickerField(
                agendas = agendas,
                selectedAgendaIri = form.agendaIri,
                onAgendaSelected = { iri -> onEdit { it.copy(agendaIri = iri) } },
                modifier = Modifier.fillMaxWidth(),
            )
            errors[PeriodField.AGENDA]?.let { ErrorLine(it) }

            EventDateFields(form = form.dates, onChange = { dates -> onEdit { it.copy(dates = dates) } })
            errors[PeriodField.DATES]?.let { ErrorLine(it) }

            if (form.isNew) {
                RecurrencePicker(
                    value = form.rrule,
                    onChange = { rule -> onEdit { it.copy(rrule = rule) } },
                    eventStartDate = form.dates.startDate,
                )
            } else {
                LabeledField(label = "Répéter", modifier = Modifier.fillMaxWidth()) {
                    TextField(
                        value = form.rrule?.let(RruleUtils::rruleToFrenchText) ?: "Jamais",
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        shape = RoundedCornerShape(FIELD_RADIUS),
                        colors = filledFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (!form.isNew) {
                OutlinedButton(
                    onClick = { confirmingDelete = true },
                    enabled = !form.isSaving,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.PERIOD_DELETE),
                ) { Text("Supprimer la période", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
