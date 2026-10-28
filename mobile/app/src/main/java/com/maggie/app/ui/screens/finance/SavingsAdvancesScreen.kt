package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.maggie.app.data.model.AdvanceRepaymentProposal
import com.maggie.app.data.model.SalaryDelay
import com.maggie.app.data.model.SavingsAdvance
import com.maggie.app.data.model.SavingsAdvances
import com.maggie.app.data.model.formatAdvanceDate
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.unproposedReasonLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavingsAdvancesScreen(
    viewModel: SavingsAdvancesViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(uiState.actionError) {
        uiState.actionError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearActionError()
        }
    }

    Scaffold(
        modifier = Modifier.testTag(UiTags.SAVINGS_ADVANCES),
        topBar = {
            FinanceTopBar(Screen.SavingsAdvances.route) {
                TopAppBar(
                    title = { Text("Avances d'épargne") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        val advances = uiState.advances

        if (advances == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator()
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Chargement impossible. Vérifiez votre connexion.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = viewModel::refresh,
                            modifier = Modifier.testTag(UiTags.SAVINGS_ADVANCES_RETRY),
                        ) {
                            Text("Réessayer")
                        }
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SalaryDayCard(advances.salaryDelay, uiState.isSavingDay, viewModel::saveSalaryDay) }
            item { TotalCard(advances) }

            if (advances.openAdvances.isEmpty()) {
                item {
                    Text(
                        "Aucune avance en cours",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (advances.proposals.isNotEmpty()) {
                item { SectionTitle("Contre-virements proposés") }
                items(advances.proposals, key = { "proposal-${it.advanceId}" }) { proposal ->
                    ProposalCard(
                        proposal = proposal,
                        destination = advances.destinationName(proposal),
                        isRecording = uiState.recordingAdvanceId == proposal.advanceId,
                        enabled = uiState.recordingAdvanceId == null,
                        onDone = { viewModel.recordCounterTransfer(proposal) },
                    )
                }
            }

            if (advances.unproposed.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Non proposé",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        advances.unproposed.forEach {
                            Text(
                                "${it.sourceAccountName} : ${formatCents(it.amountCents)} — " +
                                    unproposedReasonLabel(it.reason),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (advances.openAdvances.isNotEmpty()) {
                item { SectionTitle("Avances ouvertes") }
                items(advances.openAdvances, key = { "open-${it.id}" }) { AdvanceCard(it) }
            }

            if (advances.closedAdvances.isNotEmpty()) {
                item { SectionTitle("Avances soldées") }
                items(advances.closedAdvances, key = { "closed-${it.id}" }) { AdvanceCard(it) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SalaryDayCard(
    delay: SalaryDelay,
    isSaving: Boolean,
    onSave: (String) -> Unit,
) {
    var day by remember(delay.salaryExpectedDay) { mutableStateOf(delay.salaryExpectedDay?.toString().orEmpty()) }
    val asked = delay.salaryExpectedDay == null
    val isLate = !asked && delay.isLate

    MaggieCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (isLate) MaterialTheme.colorScheme.errorContainer else null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isLate) {
                Text(
                    "Salaire en retard de ${delay.delayDays} ${if (delay.delayDays > 1) "jours" else "jour"}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                if (asked) "Quel jour attendez-vous votre salaire ?" else "Jour de salaire attendu",
                style = MaterialTheme.typography.titleSmall,
            )
            if (delay.salaryExpectedDay != null && !delay.isConfigured) {
                Text(
                    "Pas de salaire de référence : le retard ne peut pas être calculé",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = day,
                    onValueChange = { day = it.filter(Char::isDigit).take(2) },
                    label = { Text("Jour du mois (1 à 31)") },
                    modifier = Modifier.weight(1f).testTag(UiTags.SAVINGS_ADVANCES_SALARY_DAY),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Button(
                    onClick = { onSave(day) },
                    enabled = !isSaving,
                    modifier = Modifier.testTag(UiTags.SAVINGS_ADVANCES_SAVE_DAY),
                ) {
                    Text("Enregistrer")
                }
            }
        }
    }
}

@Composable
private fun TotalCard(advances: SavingsAdvances) {
    MaggieCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Reste à rendre", style = MaterialTheme.typography.titleSmall)
            Text(
                formatCents(advances.totalOutstandingCents),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag(UiTags.SAVINGS_ADVANCES_TOTAL),
            )
            advances.bySource.forEach { source ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(source.sourceAccountName, style = MaterialTheme.typography.bodyMedium)
                    Text(formatCents(source.outstandingCents), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: AdvanceRepaymentProposal,
    destination: String,
    isRecording: Boolean,
    enabled: Boolean,
    onDone: () -> Unit,
) {
    MaggieCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Remettre ${formatCents(proposal.amountCents, proposal.currency)} sur $destination",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                proposal.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (proposal.isPartial) {
                Text(
                    "Montant réduit au solde disponible",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            OutlinedButton(
                onClick = onDone,
                enabled = enabled,
                modifier = Modifier.testTag(UiTags.savingsAdvanceDone(proposal.advanceId)),
            ) {
                Text(if (isRecording) "Enregistrement…" else "Je l'ai fait")
            }
        }
    }
}

@Composable
private fun AdvanceCard(advance: SavingsAdvance) {
    MaggieCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(advance.sourceAccountName, style = MaterialTheme.typography.titleSmall)
            formatAdvanceDate(advance.openedOn)?.let {
                Text(
                    "Ouverte le $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AmountLine("Avancé", advance.amountCents)
            AmountLine("Rendu", advance.repaidCents)
            AmountLine("Reste à rendre", advance.outstandingCents)
        }
    }
}

@Composable
private fun AmountLine(label: String, cents: Int) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(formatCents(cents), style = MaterialTheme.typography.bodyMedium)
    }
}
