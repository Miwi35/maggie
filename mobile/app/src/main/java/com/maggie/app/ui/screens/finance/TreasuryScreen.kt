package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.maggie.app.data.model.CUSHION_ADVANCE_WARNING
import com.maggie.app.data.model.TREASURY_HORIZONS
import com.maggie.app.data.model.TransactionPrefill
import com.maggie.app.data.model.TreasuryAdvice
import com.maggie.app.data.model.TreasuryOutlook
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.movementRows
import com.maggie.app.data.model.salaryDelayLabel
import com.maggie.app.data.model.toPrefill
import com.maggie.app.data.model.treasuryDate
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreasuryScreen(
    viewModel: TreasuryViewModel,
    onBack: () -> Unit,
    onPrepareAdvance: (accountId: String, accountName: String, prefill: TransactionPrefill) -> Unit,
) {
    Scaffold(
        topBar = {
            FinanceTopBar(Screen.Treasury.route) {
                TopAppBar(
                    title = { Text("Trésorerie") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        TreasuryContent(viewModel, onPrepareAdvance, Modifier.padding(paddingValues))
    }
}

/** The body of the screen, without a bar of its own: the Trésorerie page shows it as its « Prévision » tab. */
@Composable
fun TreasuryContent(
    viewModel: TreasuryViewModel,
    onPrepareAdvance: (accountId: String, accountName: String, prefill: TransactionPrefill) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.savedMessage) {
        uiState.savedMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSavedMessage()
        }
    }

    ErrorSnackbar(
        error = uiState.error.takeIf { !uiState.errorIsMessage },
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )
    // A refused input says why, in the server's own words; retrying would not help.
    ErrorSnackbar(
        error = uiState.error.takeIf { uiState.errorIsMessage },
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        message = uiState.error.orEmpty(),
    )

    Box(modifier = modifier.fillMaxSize()) {
        TreasuryBody(uiState, viewModel, onPrepareAdvance)
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun TreasuryBody(
    uiState: TreasuryUiState,
    viewModel: TreasuryViewModel,
    onPrepareAdvance: (accountId: String, accountName: String, prefill: TransactionPrefill) -> Unit,
) {
    val outlook = uiState.outlook

    if (outlook == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator()
            } else {
                Text("Trésorerie indisponible", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!outlook.salary.isConfigured) {
            SalaryDayCard(isSaving = uiState.isSaving, onSave = viewModel::saveSalaryDay)
        } else {
            SalaryCard(outlook)
            ProjectionCard(outlook, uiState.months, viewModel::selectHorizon)
            AdviceCard(outlook, onPrepareAdvance)
            OverdraftLimitCard(
                limitCents = outlook.overdraft.limitCents,
                isSaving = uiState.isSaving,
                onSave = viewModel::saveOverdraftLimit,
            )
        }
    }
}

@Composable
private fun SalaryDayCard(isSaving: Boolean, onSave: (String) -> Unit) {
    var day by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Quel jour le salaire est-il attendu ?", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = day,
                onValueChange = { day = it },
                label = { Text("Jour du mois (1 à 31)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Button(onClick = { onSave(day) }, enabled = !isSaving) {
                Text("Enregistrer")
            }
        }
    }
}

@Composable
private fun SalaryCard(outlook: TreasuryOutlook) {
    val salary = outlook.salary
    val late = salary.isLate && !salary.isReceived

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (late) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (late) {
                Text(salaryDelayLabel(salary.delayDays), style = MaterialTheme.typography.titleMedium)
            } else if (salary.isReceived) {
                Text("Salaire reçu", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("Salaire", style = MaterialTheme.typography.titleMedium)
            }

            salary.expectedOn?.let {
                Text(
                    text = "${if (late) "Attendu depuis le" else "Attendu le"} ${treasuryDate(it)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (late && salary.shortfallCents > 0) {
                Text(
                    text = "Il manque ${formatCents(salary.shortfallCents)} par rapport au repère.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Solde des comptes courants : ${formatCents(outlook.balanceCents)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun PlannedMovements(outlook: TreasuryOutlook) {
    val rows = outlook.movementRows()
    if (rows.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Mouvements prévus", style = MaterialTheme.typography.titleSmall)
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "${treasuryDate(row.date)} · ${row.label}" + if (row.isRecurring) " (récurrent)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(formatCents(row.amountCents), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HorizonPicker(months: Int?, onSelect: (Int?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TREASURY_HORIZONS.forEach { horizon ->
            FilterChip(
                selected = horizon.months == months,
                onClick = { onSelect(horizon.months) },
                label = { Text(horizon.label) },
            )
        }
    }
}

@Composable
private fun ProjectionCard(outlook: TreasuryOutlook, months: Int?, onSelectHorizon: (Int?) -> Unit) {
    val overdraft = outlook.overdraft
    val onDate = overdraft.onDate

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Solde projeté", style = MaterialTheme.typography.titleSmall)
            HorizonPicker(months, onSelectHorizon)

            if (outlook.projection.isEmpty()) {
                Text(
                    "Aucune projection pour le moment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TreasuryChart(days = outlook.projection, limitCents = overdraft.limitCents)
                Text("Entrées et sorties prévues, cumulées", style = MaterialTheme.typography.titleSmall)
                TreasuryFlowsChart(days = outlook.projection)
            }

            PlannedMovements(outlook)

            if (overdraft.isForecast && onDate != null) {
                Text(
                    text = "Découvert prévu le ${treasuryDate(onDate)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AdviceCard(
    outlook: TreasuryOutlook,
    onPrepareAdvance: (String, String, TransactionPrefill) -> Unit,
) {
    val advice = outlook.advice

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Avance conseillée", style = MaterialTheme.typography.titleSmall)

            if (advice == null) {
                Text(
                    text = if (outlook.salary.isReceived) {
                        "Le salaire est arrivé : aucune avance à prévoir."
                    } else {
                        "Aucune avance à prévoir : le solde reste au-dessus de la limite."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AdviceBody(advice, outlook.today, onPrepareAdvance)
            }

            if (outlook.outstandingAdvancesCents > 0) {
                Text(
                    text = "Déjà avancé et pas encore rendu : ${formatCents(outlook.outstandingAdvancesCents)} reste à rendre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AdviceBody(
    advice: TreasuryAdvice,
    today: String,
    onPrepareAdvance: (String, String, TransactionPrefill) -> Unit,
) {
    val source = advice.sourceAccountName
    val partial = advice.sourceAmountCents < advice.amountCents

    Text(
        text = buildString {
            append("Avancer ${formatCents(advice.amountCents)}")
            if (source != null) {
                append(" depuis $source")
                if (partial) append(" (${formatCents(advice.sourceAmountCents)} sur ce compte)")
            } else {
                append(" : aucune épargne disponible pour le couvrir.")
            }
        },
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = "${formatCents(advice.amountCents)} reste à rendre une fois le salaire arrivé.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (advice.isCushion || advice.touchesCushion) {
        Text(
            text = "Cette avance $CUSHION_ADVANCE_WARNING.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (advice.alternatives.isNotEmpty()) {
        Text("Le reste, depuis", style = MaterialTheme.typography.labelMedium)
        advice.alternatives.forEach { alternative ->
            Text(
                text = "${alternative.accountName} — ${formatCents(alternative.amountCents)}" +
                    if (alternative.isCushion) " (matelas)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (advice.uncoveredCents > 0) {
        Text(
            text = "il manque encore ${formatCents(advice.uncoveredCents)} : l'épargne ne suffit pas.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    val prefill = advice.toPrefill(today)
    val sourceId = advice.sourceAccountId
    if (prefill != null && sourceId != null) {
        Button(
            onClick = { onPrepareAdvance(sourceId, source.orEmpty(), prefill) },
            modifier = Modifier.testTag(UiTags.TREASURY_PREPARE_ADVANCE),
        ) {
            Text("Préparer l'avance")
        }
    }
}

@Composable
private fun OverdraftLimitCard(limitCents: Int, isSaving: Boolean, onSave: (String) -> Unit) {
    var limit by remember(limitCents) { mutableStateOf((limitCents / 100.0).toString()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Découvert autorisé", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = limit,
                onValueChange = { limit = it },
                label = { Text("Limite (€)") },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.TREASURY_OVERDRAFT_LIMIT),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Button(onClick = { onSave(limit) }, enabled = !isSaving) {
                Text("Enregistrer")
            }
        }
    }
}
