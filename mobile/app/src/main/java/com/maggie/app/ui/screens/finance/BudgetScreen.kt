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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.EnvelopeCreateRequest
import com.maggie.app.data.model.BudgetLine
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.budgetBreakdown
import com.maggie.app.data.model.budgetPeriodLabel
import com.maggie.app.data.model.consumedFraction
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.monthLabel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(
    viewModel: BudgetViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    val budgets = uiState.status?.budgets ?: emptyList()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.rollOverMessage) {
        uiState.rollOverMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearRollOverMessage()
        }
    }

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

            when {
                uiState.isLoading && budgets.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                budgets.isEmpty() -> {
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
                        items(budgets, key = { it.id }) { line ->
                            BudgetCard(
                                line = line,
                                onDelete = { viewModel.deleteEnvelope(line.id) },
                            )
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
            onConfirm = { request ->
                viewModel.createEnvelope(request)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
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

@Composable
private fun BudgetCard(
    line: BudgetLine,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = line.categoryName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = budgetPeriodLabel(line.mode, line.year, line.month),
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

            Text(
                text = when {
                    line.isOverspent ->
                        "Dépassement de ${formatCents(-line.remainingCents, line.currency)}"
                    line.plannedCents > 0 ->
                        "${formatCents(line.availableCents, line.currency)} encore libres"
                    else -> "Reste ${formatCents(line.remainingCents, line.currency)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    line.isOverspent -> MaterialTheme.colorScheme.error
                    line.isOvercommitted -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun EnvelopeCreateDialog(
    categories: List<Category>,
    year: Int,
    month: Int,
    onConfirm: (EnvelopeCreateRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var amountText by remember { mutableStateOf("") }
    var isMonthly by remember { mutableStateOf(true) }

    val canSubmit = selectedCategoryId != null && amountText.toDoubleOrNull() != null

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
                            label = { Text(category.name) },
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
    )
}
