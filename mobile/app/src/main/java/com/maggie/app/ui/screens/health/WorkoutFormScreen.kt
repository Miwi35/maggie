package com.maggie.app.ui.screens.health

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.DateField
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.components.TimeField

/**
 * A session on a screen of its own (MAG-705), new when [workoutId] is null. It opens the form on the shared
 * [SportViewModel] and closes itself once the save went through.
 */
@Composable
fun WorkoutFormScreen(
    viewModel: SportViewModel,
    workoutId: String?,
    onClose: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    // Opened once per visit; a rotation finds the form, and what was typed, still in the ViewModel.
    // An edit waits for the list: opened from a link, the session is not there before it.
    LaunchedEffect(workoutId, uiState.hasLoaded) {
        val form = viewModel.uiState.value.form
        if (form == null || form.workoutId != workoutId) {
            when {
                workoutId == null -> viewModel.openNew()
                viewModel.uiState.value.hasLoaded -> viewModel.openEdit(workoutId)
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
    // A session that no longer exists: the form does not open, the list says why.
    LaunchedEffect(uiState.error, uiState.hasLoaded) {
        if (workoutId != null && uiState.hasLoaded && uiState.form == null && uiState.error == SportViewModel.MESSAGE_NOT_FOUND) onClose()
    }
    if (form == null) return

    val close = {
        viewModel.closeForm()
        onClose()
    }
    BackHandler(onBack = close)

    WorkoutFormContent(form = form, onEdit = viewModel::editDraft, onSave = viewModel::save, onBack = close)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun WorkoutFormContent(
    form: WorkoutForm,
    onEdit: ((WorkoutDraft) -> WorkoutDraft) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val draft = form.draft
    val errors = form.errors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (form.isNew) "Nouvelle séance" else "Modifier la séance") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = onSave,
                        enabled = !form.isSaving,
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag(UiTags.WORKOUT_SAVE),
                    ) { Text(if (form.isNew) "Ajouter" else "Enregistrer") }
                }
            }
        },
        modifier = Modifier.testTag(UiTags.WORKOUT_FORM),
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
                    MaggieCard(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.WORKOUT_FORM_ERROR),
                    ) {
                        Text(refusal, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(16.dp))
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Type", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WorkoutType.entries.forEach { type ->
                            FilterChip(
                                selected = draft.type == type,
                                onClick = { onEdit { it.copy(type = type) } },
                                label = { Text(type.label) },
                                modifier = Modifier.testTag(UiTags.workoutType(type.key)),
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { value -> onEdit { it.copy(title = value) } },
                    label = { Text("Titre") },
                    isError = errors[WorkoutField.TITLE] != null,
                    supportingText = errors[WorkoutField.TITLE]?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.WORKOUT_TITLE),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    DateField(
                        label = "Date",
                        value = draft.date,
                        onValueChange = { value -> onEdit { it.copy(date = value) } },
                        modifier = Modifier.weight(1f),
                        tag = UiTags.WORKOUT_DATE,
                    )
                    TimeField(
                        label = "Heure",
                        value = draft.time,
                        onValueChange = { value -> onEdit { it.copy(time = value) } },
                        modifier = Modifier.weight(1f),
                        tag = UiTags.WORKOUT_TIME,
                    )
                }

                NumberField("Durée (min)", draft.duration, errors[WorkoutField.DURATION], UiTags.WORKOUT_DURATION, KeyboardType.Number) { value ->
                    onEdit { it.copy(duration = value) }
                }
                NumberField("Distance (km)", draft.distance, errors[WorkoutField.DISTANCE], UiTags.WORKOUT_DISTANCE, KeyboardType.Decimal) { value ->
                    onEdit { it.copy(distance = value) }
                }
                NumberField("Calories (kcal)", draft.kcal, errors[WorkoutField.KCAL], UiTags.WORKOUT_KCAL, KeyboardType.Number) { value ->
                    onEdit { it.copy(kcal = value) }
                }
                NumberField("FC moyenne (bpm)", draft.heartRate, errors[WorkoutField.HEART_RATE], UiTags.WORKOUT_HEART_RATE, KeyboardType.Number) { value ->
                    onEdit { it.copy(heartRate = value) }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ressenti", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WorkoutFeeling.entries.forEach { feeling ->
                            FilterChip(
                                selected = draft.feeling == feeling,
                                // A second tap on the chosen feeling clears it: the field is optional.
                                onClick = { onEdit { it.copy(feeling = if (it.feeling == feeling) null else feeling) } },
                                label = { Text(feeling.label) },
                                modifier = Modifier.testTag(UiTags.workoutFeeling(feeling.key)),
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = draft.notes,
                    onValueChange = { value -> onEdit { it.copy(notes = value) } },
                    label = { Text("Notes") },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.WORKOUT_NOTES),
                )
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, error: String?, tag: String, keyboard: KeyboardType, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
