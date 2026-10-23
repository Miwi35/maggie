package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.formatCents
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot

/**
 * The editor of a scenario (MAG-551): its name, the projects it keeps and their order, a monthly saving. The
 * tablet adds the simulated early repayments and the placements. The projects themselves are never edited here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioEditorSheet(
    editor: ScenarioEditor,
    options: ScenarioOptions?,
    full: Boolean,
    viewModel: ScenarioViewModel,
) {
    LaunchedEffect(editor.done) { if (editor.done) viewModel.closeEditor() }

    ModalBottomSheet(
        modifier = Modifier.uiTagRoot(),
        onDismissRequest = viewModel::closeEditor,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val errors = editor.errors
        Column(
            modifier = Modifier
                .testTag(UiTags.SCENARIO_EDITOR)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (editor.isNew) "Nouveau scénario" else "Modifier le scénario", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = editor.draft.name,
                onValueChange = { name -> viewModel.editDraft { it.copy(name = name) } },
                label = { Text("Nom") },
                isError = ScenarioField.NAME in errors,
                supportingText = errors[ScenarioField.NAME]?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().testTag(UiTags.SCENARIO_NAME),
            )

            Text("Projets retenus, dans l'ordre", style = MaterialTheme.typography.titleSmall)
            ProjectPicks(editor.draft, options, viewModel)

            OutlinedTextField(
                value = editor.draft.saving,
                onValueChange = { saving -> viewModel.editDraft { it.copy(saving = saving) } },
                label = { Text("Épargne mensuelle (€)") },
                supportingText = { Text(errors[ScenarioField.SAVING] ?: "Vide : l'épargne que le planning compte déjà") },
                isError = ScenarioField.SAVING in errors,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag(UiTags.SCENARIO_SAVING),
            )

            if (full) {
                HorizontalDivider()
                RepaymentsEditor(editor, options, viewModel)
                PlacementsPicker(editor.draft, options, viewModel)
            }

            editor.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(UiTags.SCENARIO_EDITOR_ERROR))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = viewModel::saveEditor, enabled = !editor.isSaving, modifier = Modifier.testTag(UiTags.SCENARIO_SAVE)) {
                    Text("Enregistrer")
                }
                OutlinedButton(onClick = viewModel::closeEditor) { Text("Annuler") }
                if (editor.isSaving) CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The kept projects first, in the scenario's order with up and down; then those the scenario leaves out. */
@Composable
private fun ProjectPicks(draft: ScenarioDraft, options: ScenarioOptions?, viewModel: ScenarioViewModel) {
    val projects = options?.projects.orEmpty()
    if (options == null) {
        Text("Chargement des projets…", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val byId = projects.associateBy { it.id }
    val kept = draft.projectIds.mapNotNull { byId[it] }
    val left = projects.filter { it.id !in draft.projectIds }

    Column {
        kept.forEachIndexed { index, project ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = true,
                    onCheckedChange = { viewModel.editDraft { it.toggleProject(project.id) } },
                    modifier = Modifier.testTag(UiTags.scenarioProject(project.id)),
                )
                Text("${index + 1}. ${project.name}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { viewModel.editDraft { it.moveProject(project.id, -1) } },
                    enabled = index > 0,
                    modifier = Modifier.testTag(UiTags.scenarioProjectUp(project.id)),
                ) { Icon(Icons.Default.ArrowUpward, contentDescription = "Monter ${project.name}") }
                IconButton(
                    onClick = { viewModel.editDraft { it.moveProject(project.id, 1) } },
                    enabled = index < kept.lastIndex,
                    modifier = Modifier.testTag(UiTags.scenarioProjectDown(project.id)),
                ) { Icon(Icons.Default.ArrowDownward, contentDescription = "Descendre ${project.name}") }
            }
        }
        left.forEach { project ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = false,
                    onCheckedChange = { viewModel.editDraft { it.toggleProject(project.id) } },
                    modifier = Modifier.testTag(UiTags.scenarioProject(project.id)),
                )
                Text(project.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepaymentsEditor(editor: ScenarioEditor, options: ScenarioOptions?, viewModel: ScenarioViewModel) {
    val loans = options?.loans.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Remboursements anticipés simulés", style = MaterialTheme.typography.titleSmall)
        editor.draft.repayments.forEach { repayment ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loans.isEmpty()) {
                        Text("Aucun prêt à rembourser.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            loans.forEach { loan ->
                                FilterChip(
                                    selected = repayment.loanId == loan.id,
                                    onClick = { viewModel.editRepayment(repayment.key) { it.copy(loanId = loan.id) } },
                                    label = { Text(loanLabel(loan)) },
                                    modifier = Modifier.testTag(UiTags.scenarioRepayment(repayment.key, "loan_${loan.id}")),
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = repayment.month,
                        onValueChange = { month -> viewModel.editRepayment(repayment.key) { it.copy(month = month) } },
                        label = { Text("Mois (AAAA-MM)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.scenarioRepayment(repayment.key, "month")),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = repayment.total,
                            onCheckedChange = { total -> viewModel.editRepayment(repayment.key) { it.copy(total = total) } },
                            modifier = Modifier.testTag(UiTags.scenarioRepayment(repayment.key, "total")),
                        )
                        Text("Capital restant", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!repayment.total) {
                        OutlinedTextField(
                            value = repayment.euros,
                            onValueChange = { euros -> viewModel.editRepayment(repayment.key) { it.copy(euros = euros) } },
                            label = { Text("Montant (€)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth().testTag(UiTags.scenarioRepayment(repayment.key, "euros")),
                        )
                    }
                    IconButton(
                        onClick = { viewModel.removeRepayment(repayment.key) },
                        modifier = Modifier.testTag(UiTags.scenarioRepayment(repayment.key, "remove")),
                    ) { Icon(Icons.Default.Delete, contentDescription = "Retirer ce remboursement") }
                }
            }
        }
        editor.errors[ScenarioField.REPAYMENTS]?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = viewModel::addRepayment, modifier = Modifier.testTag(UiTags.SCENARIO_REPAYMENT_ADD)) {
            Text("+ Remboursement")
        }
    }
}

private fun loanLabel(loan: Loan): String = "${loan.name} · ${formatCents(loan.principalRemainingCents, loan.currency)}"

@Composable
private fun PlacementsPicker(draft: ScenarioDraft, options: ScenarioOptions?, viewModel: ScenarioViewModel) {
    val placements = options?.placements.orEmpty()
    if (placements.isEmpty()) return
    Column {
        Text("Placements", style = MaterialTheme.typography.titleSmall)
        placements.forEach { placement ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = placement.id in draft.placementIds,
                    onCheckedChange = { viewModel.editDraft { it.togglePlacement(placement.id) } },
                    modifier = Modifier.testTag(UiTags.scenarioPlacement(placement.id)),
                )
                Text(placement.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
