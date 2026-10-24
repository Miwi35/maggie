package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.EnvelopeCreateRequest
import com.maggie.app.data.model.AlertThresholdInput
import com.maggie.app.data.model.BudgetLine
import com.maggie.app.data.model.EnvelopeConsumption
import com.maggie.app.data.model.NestedBudgetLine
import com.maggie.app.data.model.OutsideMonthlyBudget
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.DEFAULT_ALERT_THRESHOLD_PERCENT
import com.maggie.app.data.model.CategoryTree
import com.maggie.app.data.model.Season
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.budgetBreakdown
import com.maggie.app.data.model.budgetCaption
import com.maggie.app.data.model.budgetPeriodLabel
import com.maggie.app.data.model.consumedFraction
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.monthLabel
import com.maggie.app.data.model.overlappingMonth
import com.maggie.app.data.model.splitSharesOf
import com.maggie.app.data.model.toSortPillLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.navigation.FinanceSection
import com.maggie.app.ui.navigation.ProvideFinanceNav
import com.maggie.app.ui.navigation.financeSubviewState
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.uiTagRoot
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.layout.WindowWidth
import com.maggie.app.ui.layout.rememberAppLayout
import kotlin.math.roundToInt

/** The key of Budget's « Hors budget » sub-view; the other one, « Mois », is the section's first. */
private const val BUDGET_OUTSIDE = "outside"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(
    viewModel: BudgetViewModel,
    onBack: () -> Unit,
    // The ruler of a mother is read on the phone and edited where the window has room for it.
    canEditSplit: Boolean = false,
    // The tablet configures the envelopes of the period: the tree beside the editor (MAG-419).
    showsDetailPane: Boolean = false,
    // The pile of the lines to qualify: the pill shows under the period when it is not empty (MAG-458).
    toQualify: ToSortPile? = null,
    onOpenToQualify: () -> Unit = {},
    // « Ajuster » (MAG-461): the envelopes against what was really spent, on phone and tablet. Null: no entry.
    adjustments: EnvelopeAdjustmentsViewModel? = null,
) {
    ProvideFinanceNav {
        BudgetContent(viewModel, onBack, canEditSplit, showsDetailPane, toQualify, onOpenToQualify, adjustments)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetContent(
    viewModel: BudgetViewModel,
    onBack: () -> Unit,
    canEditSplit: Boolean,
    showsDetailPane: Boolean,
    toQualify: ToSortPile?,
    onOpenToQualify: () -> Unit,
    adjustments: EnvelopeAdjustmentsViewModel?,
) {
    val uiState by viewModel.uiState.collectAsState()
    // « Mois » (the envelopes of the month) or « Hors budget » (annual and one-off ones): the sub-view row's state.
    val (subview, _) = financeSubviewState(FinanceSection.BUDGET)
    val showsOutside = subview.key == BUDGET_OUTSIDE
    var adjusting by rememberSaveable { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingThreshold by remember { mutableStateOf<BudgetLine?>(null) }
    // The tablet sets the alert threshold; the phone shows it (MAG-49).
    val canSetThreshold = rememberAppLayout().width != WindowWidth.COMPACT
    val budgets = uiState.status?.budgets ?: emptyList()
    val visibleBudgets = uiState.visibleBudgets
    val tree = remember(uiState.categories) { CategoryTree(uiState.categories) }
    val snackbarHostState = remember { SnackbarHostState() }
    // The tree is built (and seasons set) on the tablet and the web; the phone folds, unfolds and reads it (spec, rule 8).
    val editsSeasons = rememberAppLayout().width != WindowWidth.COMPACT

    LaunchedEffect(uiState.rollOverMessage) {
        uiState.rollOverMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearRollOverMessage()
        }
    }

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )

    if (adjusting && adjustments != null) {
        EnvelopeAdjustmentsScreen(adjustments, onBack = { adjusting = false })
        return
    }

    val onAdjust = adjustments?.let { { adjusting = true } }

    val topBar: @Composable () -> Unit = {
        FinanceTopBar(Screen.BudgetList.route, actions = {
            AdjustAction(onAdjust)
            IconButton(
                onClick = { viewModel.rollOverPreviousPeriod() },
                enabled = !uiState.isRollingOver,
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Reconduire le mois précédent")
            }
        })
    }

    if (showsDetailPane) {
        Column(modifier = Modifier.fillMaxSize()) {
            topBar()
            Box(modifier = Modifier.weight(1f)) {
                BudgetConfigurationScreen(viewModel, uiState, snackbarHostState, showsOutside, toQualify, onOpenToQualify)
            }
        }
        return
    }

    Scaffold(
        topBar = topBar,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (editsSeasons) {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Nouvelle enveloppe")
                }
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            PeriodSelector(
                year = uiState.year,
                month = uiState.month,
                onPrevious = { viewModel.shiftPeriod(-1) },
                onNext = { viewModel.shiftPeriod(1) },
            )
            ToQualifyRow(toQualify, onOpenToQualify)

            uiState.status?.let { status ->
                if (status.budgets.isNotEmpty() && !showsOutside) {
                    Text(
                        text = "${formatCents(status.totalConsumedCents)} consommés sur " +
                            "${formatCents(status.totalBudgetedCents)} — " +
                            "${formatCents(status.totalAvailableCents)} encore libres",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            val outside = uiState.status?.outsideMonthlyBudget
            when {
                uiState.isLoading && budgets.isEmpty() && outside?.isEmpty != false -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                budgets.isEmpty() && outside?.isEmpty != false -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Aucune enveloppe pour cette période.\nReconduisez le mois précédent ou créez-en une.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (budgets.isEmpty() && !showsOutside) {
                            item {
                                Text(
                                    "Aucune enveloppe mensuelle pour cette période.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (showsOutside && (outside == null || outside.isEmpty)) {
                            item {
                                Text(
                                    "Rien hors du budget mensuel pour cette période.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(if (showsOutside) emptyList() else visibleBudgets, key = { it.line.id }) { row ->
                            val label = row.label.ifEmpty { tree.pathOf(row.line.categoryId) ?: row.line.categoryName }
                            BudgetCard(
                                line = row.line,
                                label = label,
                                caption = budgetCaption(row.line),
                                depth = row.depth,
                                hasChildren = row.hasChildren,
                                expanded = row.expansionKey in uiState.expanded,
                                onToggle = { viewModel.toggleExpanded(row.expansionKey) },
                                onOpen = { viewModel.openDetail(row.line, tree.pathOf(row.line.categoryId) ?: label) },
                                daughters = uiState.daughtersOf(row.line),
                                splitEditor = uiState.splitEditor?.takeIf { it.motherId == row.line.id },
                                canEditSplit = canEditSplit,
                                onStartSplit = { viewModel.startSplit(row.line.id) },
                                onMoveSplitBoundary = viewModel::moveSplitBoundary,
                                onSaveSplit = viewModel::saveSplit,
                                onCancelSplit = viewModel::cancelSplit,
                                onDelete = if (editsSeasons) {
                                    { viewModel.deleteEnvelope(row.line.id) }
                                } else {
                                    null
                                },
                                onEditThreshold = if (canSetThreshold) {
                                    { editingThreshold = row.line }
                                } else {
                                    null
                                },
                            )
                        }
                        if (showsOutside && outside != null && !outside.isEmpty) {
                            outsideMonthlyBudget(
                                outside = outside,
                                annual = uiState.visibleAnnualBudgets,
                                expanded = uiState.expanded,
                                onToggle = viewModel::toggleExpanded,
                                onOpen = { line -> viewModel.openDetail(line, line.categoryPath.ifEmpty { line.categoryName }) },
                                onDelete = if (editsSeasons) viewModel::deleteEnvelope else null,
                            )
                        }
                        if (!editsSeasons && !showsOutside) {
                            item(key = "edit-elsewhere") {
                                Text(
                                    "Les enveloppes se règlent sur la tablette ou le web.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        EnvelopeCreateDialog(
            categories = uiState.categories,
            year = uiState.year,
            month = uiState.month,
            withThreshold = canSetThreshold,
            editsSeasons = editsSeasons,
            onConfirm = { request ->
                viewModel.createEnvelope(request)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    uiState.detail?.let { detail ->
        EnvelopeDetailSheet(detail = detail, onDismiss = viewModel::closeDetail)
    }

    editingThreshold?.let { line ->
        AlertThresholdDialog(
            line = line,
            onConfirm = { percent ->
                viewModel.setAlertThreshold(line.id, percent)
                editingThreshold = null
            },
            onDismiss = { editingThreshold = null },
        )
    }
}

@Composable
internal fun PeriodSelector(
    year: Int,
    month: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Mois précédent")
        }
        Text(
            text = "${monthLabel(month)} $year",
            style = MaterialTheme.typography.titleMedium,
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Mois suivant")
        }
    }
}

/**
 * « Hors budget mensuel »: the annual envelopes of the year and the one-off ones (Hellfest 2026),
 * which count in none of the month's totals (MAG-414).
 */
private fun LazyListScope.outsideMonthlyBudget(
    outside: OutsideMonthlyBudget,
    annual: List<NestedBudgetLine>,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (BudgetLine) -> Unit,
    onDelete: ((String) -> Unit)?,
) {
    item(key = "outside-monthly-budget") {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(UiTags.BUDGET_OUTSIDE_MONTHLY)) {
            Text("Hors budget mensuel", style = MaterialTheme.typography.titleMedium)
            Text(
                "Les enveloppes annuelles et les événements ponctuels ne comptent pas dans le total du mois.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(annual, key = { it.line.id }) { row ->
        BudgetCard(
            line = row.line,
            label = row.label.ifEmpty { row.line.categoryName },
            caption = budgetPeriodLabel(row.line.mode, row.line.year, row.line.month),
            depth = row.depth,
            hasChildren = row.hasChildren,
            expanded = row.expansionKey in expanded,
            onToggle = { onToggle(row.expansionKey) },
            onOpen = { onOpen(row.line) },
            onDelete = onDelete?.let { delete -> { delete(row.line.id) } },
        )
    }
    items(outside.oneOff, key = { it.id }) { line ->
        BudgetCard(
            line = line,
            label = line.name,
            caption = "Ponctuelle",
            depth = 1,
            onDelete = onDelete?.let { delete -> { delete(line.id) } },
        )
    }
}

@Composable
private fun BudgetCard(
    line: EnvelopeConsumption,
    label: String,
    caption: String,
    depth: Int,
    onDelete: (() -> Unit)?,
    hasChildren: Boolean = false,
    expanded: Boolean = false,
    onToggle: () -> Unit = {},
    // Null where there is nothing to open: a one-off envelope's lines are not read by category.
    onOpen: (() -> Unit)? = null,
    onEditThreshold: (() -> Unit)? = null,
    daughters: List<BudgetLine> = emptyList(),
    splitEditor: SplitEditor? = null,
    canEditSplit: Boolean = false,
    onStartSplit: () -> Unit = {},
    onMoveSplitBoundary: (index: Int, deltaCents: Int) -> Unit = { _, _ -> },
    onSaveSplit: () -> Unit = {},
    onCancelSplit: () -> Unit = {},
) {
    MaggieCard(modifier = Modifier.fillMaxWidth().padding(start = ((depth - 1) * 16).dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
                ) {
                    Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hasChildren) {
                    IconButton(onClick = onToggle) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Replier $label" else "Déplier $label",
                        )
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Supprimer",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            LinearProgressIndicator(
                progress = { consumedFraction(line.consumedCents, line.amountCents) },
                color = when {
                    line.isOverspent -> MaterialTheme.colorScheme.error
                    line.isOvercommitted -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.fillMaxWidth().height(8.dp).padding(top = 8.dp),
            )

            Text(
                text = "${formatCents(line.consumedCents, line.currency)} / " +
                    formatCents(line.amountCents, line.currency),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            val breakdown = budgetBreakdown(line)
            if (breakdown.isNotEmpty()) {
                Text(
                    text = breakdown,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            BudgetState(line)

            // A one-off envelope has no period: no sync weighs it against a threshold.
            if (line is BudgetLine) {
                AlertThreshold(line.alertThresholdPercent, onEditThreshold)
            }
            if (daughters.isNotEmpty()) {
                EnvelopeSplit(
                    mother = line,
                    daughters = daughters,
                    editor = splitEditor,
                    canEdit = canEditSplit,
                    onStart = onStartSplit,
                    onMoveBoundary = onMoveSplitBoundary,
                    onSave = onSaveSplit,
                    onCancel = onCancelSplit,
                )
            }
        }
    }
}

/** The lines an envelope weighs on, sub-categories included: opened by touching the envelope. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnvelopeDetailSheet(detail: EnvelopeDetail, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.uiTagRoot()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag(UiTags.BUDGET_DETAIL),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(detail.title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail.month?.let { "${monthLabel(it)} ${detail.year}" } ?: "Année ${detail.year}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                detail.isLoading -> CircularProgressIndicator(modifier = Modifier.padding(vertical = 16.dp))
                detail.error != null -> Text(detail.error, color = MaterialTheme.colorScheme.error)
                detail.transactions.isEmpty() -> Text(
                    "Aucune dépense sur cette période.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(detail.transactions, key = { it.id }) { line -> EnvelopeSpendRow(line) }
                }
            }
        }
    }
}

@Composable
private fun EnvelopeSpendRow(line: Transaction) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(line.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                line.bookedAt.orEmpty().take(10),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(formatCents(line.amountCents, line.currency), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The mother's ruler: always readable, editable once the screen allows it and "Répartir" is tapped.
 * With [autoStart] (inside the envelope form) the handles are live at once, no "Répartir" to tap.
 */
@Composable
internal fun EnvelopeSplit(
    mother: EnvelopeConsumption,
    daughters: List<BudgetLine>,
    editor: SplitEditor?,
    canEdit: Boolean,
    onStart: () -> Unit,
    onMoveBoundary: (index: Int, deltaCents: Int) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    autoStart: Boolean = false,
) {
    val shares = editor?.shares ?: splitSharesOf(daughters.map { it.amountCents }, mother.amountCents)

    if (autoStart && canEdit) {
        // Restart from what is stored when it changes (a save, a refresh), and after a save or a cancel.
        val stored = daughters.map { it.amountCents } to mother.amountCents
        LaunchedEffect(stored) { onStart() }
        LaunchedEffect(editor == null) { if (editor == null) onStart() }
    }

    EnvelopeSplitBar(
        names = daughters.map { it.categoryName },
        shares = shares,
        totalCents = mother.amountCents,
        currency = mother.currency,
        modifier = Modifier.padding(top = 12.dp),
        onMoveBoundary = if (editor != null && !editor.isSaving) onMoveBoundary else null,
    )

    if (canEdit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editor == null) {
                if (!autoStart) TextButton(onClick = onStart) { Text("Répartir") }
            } else {
                TextButton(onClick = onSave, enabled = !editor.isSaving) { Text("Enregistrer") }
                TextButton(onClick = onCancel, enabled = !editor.isSaving) { Text("Annuler") }
            }
        }
    }
}

/** Where Maggie interrupts after a sync: shown on every window, set where there is room for a form. */
@Composable
private fun AlertThreshold(percent: Int, onEdit: (() -> Unit)?) {
    val text = "Alerte à $percent % puis à 100 %"
    if (onEdit == null) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp).testTag("budget_alert_threshold"),
        )
    } else {
        TextButton(onClick = onEdit, modifier = Modifier.testTag("budget_alert_threshold")) {
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The threshold field, blank for the default: the same rule in the dialog of a new envelope and of an existing one. */
@Composable
internal fun AlertThresholdField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Seuil d'alerte (%)") },
        supportingText = { Text("Entre 1 et 99. Vide : 80 %.") },
        isError = AlertThresholdInput.parse(value) is AlertThresholdInput.Invalid,
        modifier = Modifier.fillMaxWidth().testTag("alert_threshold_field"),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun AlertThresholdDialog(
    line: BudgetLine,
    onConfirm: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Blank for the default: saving untouched keeps « the default », not a frozen 80.
    var text by remember {
        mutableStateOf(line.alertThresholdPercent.takeIf { it != DEFAULT_ALERT_THRESHOLD_PERCENT }?.toString().orEmpty())
    }
    val parsed = AlertThresholdInput.parse(text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alerte de ${line.categoryName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Après une synchro bancaire qui fait passer l'enveloppe au-delà de ce seuil, " +
                        "Maggie vous prévient — une fois par période, puis une seconde fois à 100 %.",
                    style = MaterialTheme.typography.bodySmall,
                )
                AlertThresholdField(text) { text = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { (parsed as? AlertThresholdInput.Valid)?.let { onConfirm(it.percent) } },
                enabled = parsed is AlertThresholdInput.Valid,
            ) {
                Text("Enregistrer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        },
        modifier = Modifier.uiTagRoot(),
    )
}

/** Overspent or overcommitted: the colour never speaks alone, an icon and a word go with it. */
@Composable
internal fun BudgetState(line: EnvelopeConsumption) {
    val (icon, color) = when {
        line.isOverspent -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
        line.isOvercommitted -> Icons.Default.WarningAmber to MaterialTheme.colorScheme.tertiary
        else -> null to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when {
        line.isOverspent -> "Dépassement de ${formatCents(-line.remainingCents, line.currency)}"
        line.isOvercommitted -> "Surengagement de ${formatCents(-line.availableCents, line.currency)}"
        line.plannedCents > 0 -> "${formatCents(line.availableCents, line.currency)} encore libres"
        else -> "Reste ${formatCents(line.remainingCents, line.currency)}"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.padding(end = 4.dp).size(16.dp),
            )
        }
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun EnvelopeCreateDialog(
    categories: List<Category>,
    year: Int,
    month: Int,
    withThreshold: Boolean,
    editsSeasons: Boolean,
    onConfirm: (EnvelopeCreateRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var thresholdText by remember { mutableStateOf("") }
    val threshold = AlertThresholdInput.parse(thresholdText)
    val tree = remember(categories) { CategoryTree(categories) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var amountText by remember { mutableStateOf("") }
    var isMonthly by remember { mutableStateOf(true) }
    var seasons by remember { mutableStateOf(listOf<SeasonDraft>()) }

    val parsedSeasons = seasons.map { it.toSeason() }
    // Two seasons sharing a month keep the dialog open rather than losing what was typed.
    val seasonsOverlap = isMonthly && overlappingMonth(parsedSeasons.filterNotNull()) != null
    val canSubmit = selectedCategoryId != null && amountText.toDoubleOrNull() != null &&
        threshold is AlertThresholdInput.Valid &&
        (!isMonthly || parsedSeasons.all { it != null }) && !seasonsOverlap

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle enveloppe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Catégorie", style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { category ->
                        FilterChip(
                            selected = selectedCategoryId == category.id,
                            onClick = { selectedCategoryId = category.id },
                            label = { Text(tree.path(category)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Budget (€)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = isMonthly,
                        onClick = { isMonthly = true },
                        label = { Text("${monthLabel(month)} $year") },
                    )
                    FilterChip(
                        selected = !isMonthly,
                        onClick = { isMonthly = false },
                        label = { Text("Année $year") },
                    )
                }
                if (withThreshold) {
                    AlertThresholdField(thresholdText) { thresholdText = it }
                }
                if (editsSeasons && isMonthly) {
                    SeasonsEditor(seasons = seasons, onChange = { seasons = it })
                    if (seasonsOverlap) {
                        Text(
                            "Deux saisons couvrent le même mois.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val categoryId = selectedCategoryId ?: return@TextButton
                    val euros = amountText.toDoubleOrNull() ?: return@TextButton
                    onConfirm(
                        EnvelopeCreateRequest(
                            category = "/api/categories/$categoryId",
                            amountCents = (euros * 100).roundToInt(),
                            year = year,
                            mode = if (isMonthly) "monthly" else "annual",
                            month = if (isMonthly) month else null,
                            alertThresholdPercent = (threshold as? AlertThresholdInput.Valid)?.percent,
                            seasonalAmounts = if (isMonthly) parsedSeasons.filterNotNull() else emptyList(),
                        ),
                    )
                },
                enabled = canSubmit,
            ) {
                Text("Créer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        },
        modifier = Modifier.uiTagRoot(),
    )
}

/** A season being typed: months 1-12 and an amount in euros, checked on [toSeason]. */
internal data class SeasonDraft(val from: String = "", val to: String = "", val euros: String = "") {
    fun toSeason(): Season? {
        val fromMonth = from.toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val toMonth = to.toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val amount = euros.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 } ?: return null
        return Season(fromMonth, toMonth, (amount * 100).roundToInt())
    }
}

/**
 * A monthly envelope's seasons, on the tablet (MAG-415): Cinéma 45 € from November (11)
 * to February (2); the months no season covers keep the envelope's amount.
 */
@Composable
internal fun SeasonsEditor(seasons: List<SeasonDraft>, onChange: (List<SeasonDraft>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag(UiTags.BUDGET_SEASONS)) {
        Text("Saisons", style = MaterialTheme.typography.bodySmall)
        Text(
            "Un autre montant sur une partie de l'année ; les autres mois gardent le budget.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        seasons.forEachIndexed { index, draft ->
            fun update(changed: SeasonDraft) = onChange(seasons.toMutableList().also { it[index] = changed })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft.from,
                    onValueChange = { update(draft.copy(from = it)) },
                    label = { Text("Du mois") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = draft.to,
                    onValueChange = { update(draft.copy(to = it)) },
                    label = { Text("Au mois") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = draft.euros,
                    onValueChange = { update(draft.copy(euros = it)) },
                    label = { Text("€") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onChange(seasons.filterIndexed { i, _ -> i != index }) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Retirer la saison")
                }
            }
        }
        TextButton(onClick = { onChange(seasons + SeasonDraft()) }) {
            Text("Ajouter une saison")
        }
    }
}

/** The « À qualifier · n · montant » pill, drawn only while the pile is not empty. */
@Composable
internal fun ToQualifyRow(pile: ToSortPile?, onOpen: () -> Unit) {
    val label = pile?.let(::toSortPillLabel) ?: return
    ToQualifyPill(label, onClick = onOpen, modifier = Modifier.padding(horizontal = 16.dp))
}

/** The top bar's entry to « Ajuster » (MAG-461); nothing where the mode is not mounted. */
@Composable
internal fun AdjustAction(onAdjust: (() -> Unit)?) {
    if (onAdjust == null) return
    TextButton(onClick = onAdjust, modifier = Modifier.testTag(UiTags.BUDGET_ADJUST_OPEN)) { Text("Ajuster") }
}
