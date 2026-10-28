package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.EnvelopeCreateRequest
import com.maggie.app.data.api.EnvelopeUpdateRequest
import com.maggie.app.data.model.AlertThresholdInput
import com.maggie.app.data.model.BudgetLine
import com.maggie.app.data.model.CategoryTree
import com.maggie.app.data.model.DEFAULT_ALERT_THRESHOLD_PERCENT
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.model.EnvelopeConsumption
import com.maggie.app.data.model.ONE_OFF_MODE
import com.maggie.app.data.model.Season
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.model.budgetCaption
import com.maggie.app.data.model.budgetPeriodLabel
import com.maggie.app.data.model.consumedFraction
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.monthLabel
import com.maggie.app.data.model.overlappingMonth
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.layout.ListDetailPane
import java.util.Locale
import kotlin.math.roundToInt

private val INDENT = 20.dp

/**
 * The tablet's Budgets screen (MAG-419): not a consultation but the place envelopes are set. On the
 * left the categories as a tree, with or without an envelope in the period shown; on the right the
 * editor of the one touched. No envelope is mandatory at any level.
 */
@Composable
internal fun BudgetConfigurationScreen(
    viewModel: BudgetViewModel,
    uiState: BudgetUiState,
    snackbarHostState: SnackbarHostState,
    showsOutside: Boolean,
    toQualify: ToSortPile? = null,
    onOpenToQualify: () -> Unit = {},
) {
    val editor = uiState.editor
    ListDetailPane(
        showsDetailPane = true,
        list = { BudgetTreePane(viewModel, uiState, snackbarHostState, showsOutside, toQualify, onOpenToQualify) },
        detail = editor?.let { open -> { EnvelopeEditorPane(viewModel, uiState, open) } },
        placeholder = "Touchez une catégorie pour régler son enveloppe.",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetTreePane(
    viewModel: BudgetViewModel,
    uiState: BudgetUiState,
    snackbarHostState: SnackbarHostState,
    showsOutside: Boolean,
    toQualify: ToSortPile?,
    onOpenToQualify: () -> Unit,
) {
    val rows = remember(uiState.categories, uiState.status) { uiState.treeRows }
    val outside = uiState.status?.outsideMonthlyBudget
    val annual = remember(outside) { uiState.nestedAnnualBudgets }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            PeriodSelector(
                year = uiState.year,
                month = uiState.month,
                onPrevious = { viewModel.shiftPeriod(-1) },
                onNext = { viewModel.shiftPeriod(1) },
            )
            ToQualifyRow(toQualify, onOpenToQualify)
            uiState.status?.takeIf { it.budgets.isNotEmpty() && !showsOutside }?.let { status ->
                Text(
                    text = "${formatCents(status.totalConsumedCents)} consommés sur " +
                        "${formatCents(status.totalBudgetedCents)} — " +
                        "${formatCents(status.totalAvailableCents)} encore libres",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(UiTags.BUDGET_TREE),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (uiState.isLoading && rows.isEmpty() && !showsOutside) {
                    item(key = "loading") {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                items(if (showsOutside) emptyList() else rows, key = { it.row.category.id }) { node ->
                    BudgetNodeRow(
                        name = node.row.category.name,
                        indentLevel = node.row.level,
                        line = node.line,
                        selected = uiState.selectedCategoryId == node.row.category.id,
                        onClick = { viewModel.selectCategory(node.row.category.id) },
                    )
                }

                if (showsOutside) {
                    item(key = "outside-monthly-budget") {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(UiTags.BUDGET_OUTSIDE_MONTHLY)) {
                            Text("Hors budget mensuel", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    items(annual, key = { it.line.id }) { row ->
                        val name = row.label.ifEmpty { row.line.categoryName }
                        BudgetNodeRow(
                            name = name,
                            indentLevel = row.depth,
                            line = row.line,
                            caption = budgetPeriodLabel(row.line.mode, row.line.year, row.line.month),
                            selected = uiState.selectedEnvelopeId == row.line.id,
                            onClick = { viewModel.selectEnvelope(row.line.id) },
                        )
                    }
                    items(outside?.oneOff.orEmpty(), key = { it.id }) { line ->
                        BudgetNodeRow(
                            name = line.name,
                            indentLevel = 1,
                            line = line,
                            caption = "Ponctuelle",
                            selected = uiState.selectedEnvelopeId == line.id,
                            onClick = { viewModel.selectEnvelope(line.id) },
                        )
                    }
                    item(key = "new-one-off") {
                        OutlinedButton(onClick = viewModel::startNewOneOff, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Nouvelle ponctuelle")
                        }
                    }
                }
            }
        }
    }
}

/**
 * One node of the tree. With an envelope: what is consumed over what is budgeted, a thin bar
 * (error when overspent, tertiary when overcommitted) and the state in words; without one, « Sans enveloppe ».
 */
@Composable
private fun BudgetNodeRow(
    name: String,
    indentLevel: Int,
    line: EnvelopeConsumption?,
    selected: Boolean,
    onClick: () -> Unit,
    caption: String? = null,
) {
    MaggieCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = INDENT * (indentLevel - 1)),
        selected = selected,
        listRow = true,
    ) {
        // The tag is on the clickable column, which merges the texts of the row: a test reads them from it.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(UiTags.budgetNode(name))
                .clickable(onClick = onClick)
                .padding(12.dp),
        ) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            caption?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (line == null) {
                Text(
                    "Sans enveloppe",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "${formatCents(line.consumedCents, line.currency)} / " +
                        formatCents(line.amountCents, line.currency),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
                LinearProgressIndicator(
                    progress = { consumedFraction(line.consumedCents, line.amountCents) },
                    color = when {
                        line.isOverspent -> MaterialTheme.colorScheme.error
                        line.isOvercommitted -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
                BudgetState(line)
            }
        }
    }
}

@Composable
private fun EnvelopeEditorPane(viewModel: BudgetViewModel, uiState: BudgetUiState, editor: EnvelopeEditor) {
    val tree = remember(uiState.categories) { CategoryTree(uiState.categories) }
    val target = editor.target
    val stored = editor.stored

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(UiTags.BUDGET_EDITOR),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            editor.isLoading && stored == null -> {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            target is EditorTarget.NewOneOff -> OneOffForm(
                stored = null,
                isSaving = editor.isSaving,
                onSave = viewModel::createOneOffEnvelope,
                onDelete = null,
                onClose = viewModel::closeEditor,
            )
            stored?.isOneOff == true -> OneOffForm(
                stored = stored,
                isSaving = editor.isSaving,
                onSave = { name, cents ->
                    viewModel.updateEnvelope(stored.id, EnvelopeUpdateRequest(cents, ONE_OFF_MODE, name = name.trim()))
                },
                onDelete = { viewModel.deleteEnvelope(stored.id) },
                onClose = viewModel::closeEditor,
            )
            else -> {
                val categoryId = (target as? EditorTarget.NewForCategory)?.categoryId
                    ?: stored?.category?.substringAfterLast('/')
                val line = stored?.let { s -> uiState.status?.budgets?.firstOrNull { it.id == s.id } }
                EnvelopeForm(
                    title = categoryId?.let(tree::pathOf) ?: "Enveloppe",
                    categoryId = categoryId,
                    stored = stored,
                    line = line,
                    year = uiState.year,
                    month = uiState.month,
                    isSaving = editor.isSaving,
                    viewModel = viewModel,
                    uiState = uiState,
                )
            }
        }
    }
}

/** An envelope of a category, new or stored: type, budget, threshold, seasons — and the ruler of a mother. */
@Composable
private fun EnvelopeForm(
    title: String,
    categoryId: String?,
    stored: Envelope?,
    line: BudgetLine?,
    year: Int,
    month: Int,
    isSaving: Boolean,
    viewModel: BudgetViewModel,
    uiState: BudgetUiState,
) {
    // The draft is keyed by the envelope, not by its content: a refusal keeps what was typed.
    val key = stored?.id ?: categoryId
    var isMonthly by remember(key) { mutableStateOf(stored?.mode != "annual") }
    var amountText by remember(key) { mutableStateOf(stored?.let { eurosText(it.amountCents) }.orEmpty()) }
    var thresholdText by remember(key) {
        mutableStateOf(
            stored?.effectiveAlertThresholdPercent?.takeIf { it != DEFAULT_ALERT_THRESHOLD_PERCENT }?.toString().orEmpty(),
        )
    }
    var seasons by remember(key) {
        mutableStateOf(
            stored?.seasonalAmounts.orEmpty().map {
                SeasonDraft(it.fromMonth.toString(), it.toMonth.toString(), eurosText(it.amountCents))
            },
        )
    }

    val threshold = AlertThresholdInput.parse(thresholdText)
    val amountCents = parseEuros(amountText)
    val parsedSeasons = seasons.map { it.toSeason() }
    val seasonsOverlap = isMonthly && overlappingMonth(parsedSeasons.filterNotNull()) != null
    val canSubmit = !isSaving && amountCents != null && threshold is AlertThresholdInput.Valid &&
        (!isMonthly || parsedSeasons.all { it != null }) && !seasonsOverlap &&
        (stored != null || categoryId != null)

    Text(
        if (stored == null) "Créer une enveloppe" else "Enveloppe",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(title, style = MaterialTheme.typography.headlineSmall)
    if (line != null) {
        Text(budgetCaption(line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BudgetState(line)
    }

    Text("Type", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = isMonthly,
            onClick = { isMonthly = true },
            label = { Text("Mensuelle · ${monthLabel(stored?.month ?: month)} $year") },
        )
        FilterChip(
            selected = !isMonthly,
            onClick = { isMonthly = false },
            label = { Text("Annuelle · $year") },
        )
    }

    OutlinedTextField(
        value = amountText,
        onValueChange = { amountText = it },
        label = { Text("Budget (€)") },
        modifier = Modifier.fillMaxWidth().testTag("budget_editor_amount"),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
    AlertThresholdField(thresholdText) { thresholdText = it }
    if (isMonthly) {
        SeasonsEditor(seasons = seasons, onChange = { seasons = it })
        if (seasonsOverlap) {
            Text(
                "Deux saisons couvrent le même mois.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (stored != null && line != null) {
        val daughters = uiState.daughtersOf(line)
        if (daughters.isNotEmpty()) {
            Text("Répartition entre les sous-catégories", style = MaterialTheme.typography.titleSmall)
            line.freeCents?.let {
                Text(
                    "${formatCents(it, line.currency)} libres",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EnvelopeSplit(
                mother = line,
                daughters = daughters,
                editor = uiState.splitEditor?.takeIf { it.motherId == line.id },
                canEdit = true,
                autoStart = true,
                onStart = { viewModel.startSplit(line.id) },
                onMoveBoundary = viewModel::moveSplitBoundary,
                onSave = viewModel::saveSplit,
                onCancel = viewModel::cancelSplit,
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = canSubmit,
            onClick = {
                val cents = amountCents ?: return@Button
                val percent = (threshold as? AlertThresholdInput.Valid)?.percent
                val seasonList: List<Season> = if (isMonthly) parsedSeasons.filterNotNull() else emptyList()
                if (stored == null) {
                    viewModel.createEnvelope(
                        EnvelopeCreateRequest(
                            category = "/api/categories/${categoryId ?: return@Button}",
                            amountCents = cents,
                            year = year,
                            mode = if (isMonthly) "monthly" else "annual",
                            month = if (isMonthly) month else null,
                            alertThresholdPercent = percent,
                            seasonalAmounts = seasonList,
                        ),
                    )
                } else {
                    viewModel.updateEnvelope(
                        stored.id,
                        EnvelopeUpdateRequest(
                            amountCents = cents,
                            mode = if (isMonthly) "monthly" else "annual",
                            month = if (isMonthly) stored.month ?: month else null,
                            seasonalAmounts = seasonList,
                            alertThresholdPercent = percent,
                        ),
                    )
                }
            },
        ) {
            Text(if (stored == null) "Créer" else "Enregistrer")
        }
        if (stored != null) {
            TextButton(onClick = { viewModel.deleteEnvelope(stored.id) }, enabled = !isSaving) {
                Text("Supprimer l'enveloppe", color = MaterialTheme.colorScheme.error)
            }
        }
        TextButton(onClick = viewModel::closeEditor) { Text("Fermer") }
    }
}

/** A one-off envelope (Hellfest 2026): a name and an amount, and no threshold — it has no period. */
@Composable
private fun OneOffForm(
    stored: Envelope?,
    isSaving: Boolean,
    onSave: (name: String, amountCents: Int) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var name by remember(stored?.id) { mutableStateOf(stored?.name.orEmpty()) }
    var amountText by remember(stored?.id) { mutableStateOf(stored?.let { eurosText(it.amountCents) }.orEmpty()) }
    val amountCents = parseEuros(amountText)

    Text(
        if (stored == null) "Nouvelle ponctuelle" else "Enveloppe ponctuelle",
        style = MaterialTheme.typography.headlineSmall,
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Nom") },
        modifier = Modifier.fillMaxWidth().testTag("budget_editor_name"),
        singleLine = true,
    )
    OutlinedTextField(
        value = amountText,
        onValueChange = { amountText = it },
        label = { Text("Budget (€)") },
        modifier = Modifier.fillMaxWidth().testTag("budget_editor_amount"),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !isSaving && name.isNotBlank() && amountCents != null,
            onClick = { amountCents?.let { onSave(name, it) } },
        ) {
            Text(if (stored == null) "Créer" else "Enregistrer")
        }
        onDelete?.let {
            TextButton(onClick = it, enabled = !isSaving) {
                Text("Supprimer l'enveloppe", color = MaterialTheme.colorScheme.error)
            }
        }
        TextButton(onClick = onClose) { Text("Fermer") }
    }
}

/** Euros as typed in a field: « 400 », « 45,5 » or « 12.30 », in cents; null when it is not an amount. */
internal fun parseEuros(text: String): Int? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 100).roundToInt() }

/** Cents as a field shows them: « 400 » for a round amount, « 45.50 » otherwise. */
internal fun eurosText(cents: Int): String =
    if (cents % 100 == 0) (cents / 100).toString() else String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100)
