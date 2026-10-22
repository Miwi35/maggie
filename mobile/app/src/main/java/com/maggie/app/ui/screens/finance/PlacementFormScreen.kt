package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.PlacementFrequency
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot

/**
 * A placement on a screen of its own (MAG-555), new when [placementId] is null. It opens the form on
 * the shared [PlacementViewModel] and closes itself once the save or the deletion went through.
 */
@Composable
fun PlacementFormScreen(
    viewModel: PlacementViewModel,
    placementId: String?,
    onClose: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    // Opened once per visit; a rotation finds the form, and what was typed, still in the ViewModel.
    // An edit waits for the list: opened from a link, the placement is not there before it.
    LaunchedEffect(placementId, uiState.hasLoaded) {
        val form = viewModel.uiState.value.form
        if (form == null || form.placementId != placementId) {
            when {
                placementId == null -> viewModel.openNew()
                viewModel.uiState.value.hasLoaded -> viewModel.openEdit(placementId)
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
    // A placement that no longer exists: the form does not open, the list says why.
    LaunchedEffect(uiState.error, uiState.hasLoaded) {
        if (placementId != null && uiState.hasLoaded && uiState.form == null && uiState.error == PlacementViewModel.MESSAGE_NOT_FOUND) onClose()
    }
    if (form == null) return

    val close = {
        viewModel.closeForm()
        onClose()
    }
    BackHandler(onBack = close)

    PlacementFormContent(
        form = form,
        onEdit = viewModel::editDraft,
        onSave = viewModel::save,
        onDelete = { form.placementId?.let(viewModel::delete) },
        onBack = close,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlacementFormContent(
    form: PlacementForm,
    onEdit: ((PlacementDraft) -> PlacementDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val draft = form.draft
    val errors = form.errors
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Supprimer « ${draft.name} » ?") },
            text = { Text("La simulation disparaît avec lui. Cette action est définitive.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                    modifier = Modifier.testTag(UiTags.PLACEMENT_DELETE_CONFIRM),
                ) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Annuler") } },
            modifier = Modifier.uiTagRoot(),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (form.isNew) "Nouveau placement" else "Modifier le placement") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                // Under a tablet's taskbar otherwise: the Scaffold does not reserve the navigation bar for its bottom bar.
                Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = onSave,
                        enabled = !form.isSaving,
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag(UiTags.PLACEMENT_SAVE),
                    ) {
                        Text(if (form.isNew) "Créer" else "Enregistrer")
                    }
                }
            }
        },
        modifier = Modifier.testTag(UiTags.PLACEMENT_FORM),
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                form.error?.let { refusal ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.PLACEMENT_FORM_ERROR),
                    ) {
                        Text(refusal, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(16.dp))
                    }
                }

                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { value -> onEdit { it.copy(name = value) } },
                    label = { Text("Nom") },
                    isError = errors[PlacementField.NAME] != null,
                    supportingText = errors[PlacementField.NAME]?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.PLACEMENT_NAME),
                )
                AmountField("Versement initial (€)", draft.initial, errors[PlacementField.INITIAL], UiTags.PLACEMENT_INITIAL) { value ->
                    onEdit { it.copy(initial = value) }
                }
                AmountField(
                    if (draft.frequency == PlacementFrequency.ONCE) "Versement, le premier jour (€)" else "Versement (€)",
                    draft.deposit,
                    errors[PlacementField.DEPOSIT],
                    UiTags.PLACEMENT_DEPOSIT,
                ) { value -> onEdit { it.copy(deposit = value) } }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fréquence", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlacementFrequency.entries.forEach { frequency ->
                            FilterChip(
                                selected = draft.frequency == frequency,
                                onClick = { onEdit { it.copy(frequency = frequency) } },
                                label = { Text(frequency.label) },
                                modifier = Modifier.testTag(UiTags.placementFrequency(frequency.key)),
                            )
                        }
                    }
                }

                AmountField("Rendement annuel (%)", draft.rate, errors[PlacementField.RATE], UiTags.PLACEMENT_RATE) { value ->
                    onEdit { it.copy(rate = value) }
                }
                OutlinedTextField(
                    value = draft.years,
                    onValueChange = { value -> onEdit { it.copy(years = value) } },
                    label = { Text("Durée (ans)") },
                    isError = errors[PlacementField.YEARS] != null,
                    supportingText = errors[PlacementField.YEARS]?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.PLACEMENT_YEARS),
                )
                Text(
                    "Intérêts composés chaque mois et réinvestis. Une simulation : rien n'est placé.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!form.isNew) {
                    OutlinedButton(
                        onClick = { confirmingDelete = true },
                        enabled = !form.isSaving,
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.PLACEMENT_DELETE),
                    ) {
                        Text("Supprimer le placement", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun AmountField(label: String, value: String, error: String?, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
