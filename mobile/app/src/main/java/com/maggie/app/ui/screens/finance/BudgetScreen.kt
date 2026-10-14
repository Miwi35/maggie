package com.maggie.app.ui.screens.finance

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.maggie.app.data.model.AlertThresholdInput
import com.maggie.app.data.model.BudgetLine
import com.maggie.app.data.model.EnvelopeConsumption
import com.maggie.app.data.model.NestedBudgetLine
import com.maggie.app.data.model.OutsideMonthlyBudget
import com.maggie.app.data.model.DailyScore
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.DEFAULT_ALERT_THRESHOLD_PERCENT
import com.maggie.app.data.model.CategoryTree
import com.maggie.app.data.model.budgetBreakdown
import com.maggie.app.data.model.budgetPeriodLabel
import com.maggie.app.data.model.consumedFraction
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.monthLabel
import com.maggie.app.data.model.reasonText
import com.maggie.app.data.model.scoreLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.layout.WindowWidth
import com.maggie.app.ui.layout.rememberAppLayout
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(
    viewModel: BudgetViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingThreshold by remember { mutableStateOf<BudgetLine?>(null) }
    // The tablet sets the alert threshold; the phone shows it (MAG-49).
    val canSetThreshold = rememberAppLayout().width != WindowWidth.COMPACT
    val budgets = uiState.status?.budgets ?: emptyList()
    val nested = remember(budgets) { uiState.nestedBudgets }
    val tree = remember(uiState.categories) { CategoryTree(uiState.categories) }
    val snackbarHostState = remember { SnackbarHostState() }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Budgets") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.rollOverPreviousPeriod() },
                        enabled = !uiState.isRollingOver,
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Reconduire le mois précédent")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Nouvelle enveloppe")
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

            uiState.score?.let { DailyScoreCard(it) }

            uiState.status?.let { status ->
                if (status.budgets.isNotEmpty()) {
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
                        if (budgets.isEmpty()) {
                            item {
                                Text(
                                    "Aucune enveloppe mensuelle pour cette période.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(nested, key = { it.line.id }) { row ->
                            BudgetCard(
                                line = row.line,
                                label = row.label.ifEmpty { tree.pathOf(row.line.categoryId) ?: row.line.categoryName },
                                caption = budgetPeriodLabel(row.line.mode, row.line.year, row.line.month),
                                depth = row.depth,
                                onDelete = { viewModel.deleteEnvelope(row.line.id) },
                                onEditThreshold = if (canSetThreshold) {
                                    { editingThreshold = row.line }
                                } else {
                                    null
                                },
                            )
                        }
                        if (outside != null && !outside.isEmpty) {
                            outsideMonthlyBudget(outside, uiState.nestedAnnualBudgets, onDelete = viewModel::deleteEnvelope)
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
            onConfirm = { request ->
                viewModel.createEnvelope(request)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
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

/**
 * The daily signal, always with the reasons behind it: a score on its own
 * reads as a verdict.
 */
@Composable
private fun DailyScoreCard(score: DailyScore) {
    val container = when (score.score) {
        "green" -> MaterialTheme.colorScheme.primaryContainer
        "orange" -> MaterialTheme.colorScheme.tertiaryContainer
        "red" -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(scoreLabel(score.score), style = MaterialTheme.typography.titleMedium)
            score.reasons.forEach { reason ->
                Text(
                    text = "• ${reasonText(reason)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PeriodSelector(
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
    onDelete: (String) -> Unit,
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
            onDelete = { onDelete(row.line.id) },
        )
    }
    items(outside.oneOff, key = { it.id }) { line ->
        BudgetCard(
            line = line,
            label = line.name,
            caption = "Ponctuelle",
            depth = 1,
            onDelete = { onDelete(line.id) },
        )
    }
}

@Composable
private fun BudgetCard(
    line: EnvelopeConsumption,
    label: String,
    caption: String,
    depth: Int,
    onDelete: () -> Unit,
    onEditThreshold: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(start = ((depth - 1) * 16).dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Supprimer",
                        tint = MaterialTheme.colorScheme.error,
                    )
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
private fun AlertThresholdField(value: String, onValueChange: (String) -> Unit) {
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
    )
}

/** Overspent or overcommitted: the colour never speaks alone, an icon and a word go with it. */
@Composable
private fun BudgetState(line: EnvelopeConsumption) {
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
    onConfirm: (EnvelopeCreateRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var thresholdText by remember { mutableStateOf("") }
    val threshold = AlertThresholdInput.parse(thresholdText)
    val tree = remember(categories) { CategoryTree(categories) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var amountText by remember { mutableStateOf("") }
    var isMonthly by remember { mutableStateOf(true) }

    val canSubmit = selectedCategoryId != null && amountText.toDoubleOrNull() != null &&
        threshold is AlertThresholdInput.Valid

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
