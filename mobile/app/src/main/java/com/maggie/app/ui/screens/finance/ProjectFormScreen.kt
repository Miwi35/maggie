package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ProjectDateMode
import com.maggie.app.data.model.ProjectWellbeing
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.DateField
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.components.markdown.MarkdownEditor
import com.maggie.app.ui.layout.WindowWidth
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.uiTagRoot

/**
 * A project on a screen of its own (MAG-519), new when [projectId] is null. It opens the form on
 * the shared [ProjectViewModel] and closes itself once the save or the deletion went through.
 */
@Composable
fun ProjectFormScreen(
    viewModel: ProjectViewModel,
    projectId: String?,
    onClose: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    // Opened once per visit; a rotation finds the form, and what was typed, still in the ViewModel.
    LaunchedEffect(projectId) {
        val form = viewModel.uiState.value.form
        if (form == null || form.projectId != projectId) {
            if (projectId == null) viewModel.openNew() else viewModel.openEdit(projectId)
        }
    }

    val form = uiState.form
    LaunchedEffect(form?.done) {
        if (form?.done == true) {
            viewModel.closeForm()
            onClose()
        }
    }
    if (form == null) return

    val close = {
        viewModel.closeForm()
        onClose()
    }
    BackHandler(onBack = close)

    // From the medium width (tablet, foldable) a new project is previewed before it is created (MAG-553).
    val previewFirst = form.isNew && rememberAppLayout().width != WindowWidth.COMPACT

    form.preview?.let { preview ->
        ProjectPreviewDialog(preview = preview, onAdd = viewModel::confirmPreview, onCancel = viewModel::dismissPreview)
    }

    ProjectFormContent(
        form = form,
        others = uiState.projects.filter { it.id != form.projectId },
        onEdit = viewModel::editDraft,
        onSave = if (previewFirst) viewModel::previewNew else viewModel::save,
        previewFirst = previewFirst,
        onDelete = { form.projectId?.let(viewModel::delete) },
        onBack = close,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ProjectFormContent(
    form: ProjectForm,
    others: List<Project>,
    onEdit: ((ProjectDraft) -> ProjectDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    /** The tablet's new project: the button asks for the preview instead of creating. */
    previewFirst: Boolean = false,
) {
    val draft = form.draft
    val errors = form.errors
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Supprimer « ${draft.name} » ?") },
            text = { Text("Les projets qui l'attendaient n'en dépendront plus. Cette action est définitive.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                    modifier = Modifier.testTag(UiTags.PROJECT_DELETE_CONFIRM),
                ) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Annuler") } },
            modifier = Modifier.uiTagRoot(),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (form.isNew) "Nouveau projet" else "Modifier le projet") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = onSave,
                        enabled = !form.isSaving && !form.isPreviewing,
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag(UiTags.PROJECT_SAVE),
                    ) {
                        Text(
                            when {
                                !form.isNew -> "Enregistrer"
                                previewFirst -> "Voir l'aperçu"
                                else -> "Créer"
                            },
                        )
                    }
                }
            }
        },
        modifier = Modifier.testTag(UiTags.PROJECT_FORM),
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
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.PROJECT_FORM_ERROR),
                    ) {
                        Text(
                            refusal,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { value -> onEdit { it.copy(name = value) } },
                    label = { Text("Nom") },
                    isError = errors[ProjectField.NAME] != null,
                    supportingText = errors[ProjectField.NAME]?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.PROJECT_NAME),
                )
                OutlinedTextField(
                    value = draft.amount,
                    onValueChange = { value -> onEdit { it.copy(amount = value) } },
                    label = { Text("Montant (€)") },
                    isError = errors[ProjectField.AMOUNT] != null,
                    supportingText = errors[ProjectField.AMOUNT]?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.PROJECT_AMOUNT),
                )

                Section("Bien-être") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProjectWellbeing.entries.forEach { level ->
                            FilterChip(
                                selected = draft.wellbeing == level,
                                onClick = { onEdit { it.copy(wellbeing = level) } },
                                label = { Text("${level.label} (${level.score})") },
                                modifier = Modifier.testTag(UiTags.projectWellbeing(level.key)),
                            )
                        }
                    }
                }

                Section("Date") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProjectDateMode.entries.forEach { mode ->
                            FilterChip(
                                selected = draft.dateMode == mode,
                                onClick = { onEdit { it.copy(dateMode = mode) } },
                                label = { Text(mode.label) },
                                modifier = Modifier.testTag(UiTags.projectDateMode(mode.key)),
                            )
                        }
                    }
                    if (draft.dateMode.needsDate) {
                        DateField(
                            label = if (draft.dateMode == ProjectDateMode.FIXED) "Date du projet" else "Date limite",
                            value = draft.targetOn,
                            onValueChange = { date -> onEdit { it.copy(targetOn = date) } },
                            isError = errors[ProjectField.TARGET_ON] != null,
                            supportingText = errors[ProjectField.TARGET_ON],
                            tag = UiTags.PROJECT_TARGET_ON,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Section("Après") {
                    if (others.isEmpty()) {
                        Text(
                            "Aucun autre projet à attendre.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            others.forEach { other ->
                                val selected = other.id in draft.dependsOn
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        onEdit {
                                            it.copy(dependsOn = if (selected) it.dependsOn - other.id else it.dependsOn + other.id)
                                        }
                                    },
                                    label = { Text(other.name) },
                                    modifier = Modifier.testTag(UiTags.projectDependency(other.id)),
                                )
                            }
                        }
                    }
                }

                MarkdownEditor(
                    value = draft.note,
                    onValueChange = { value -> onEdit { it.copy(note = value) } },
                    label = "Note",
                    modifier = Modifier.testTag(UiTags.PROJECT_NOTE),
                )

                if (!form.isNew) {
                    OutlinedButton(
                        onClick = { confirmingDelete = true },
                        enabled = !form.isSaving,
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.PROJECT_DELETE),
                    ) {
                        Text("Supprimer le projet", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}
