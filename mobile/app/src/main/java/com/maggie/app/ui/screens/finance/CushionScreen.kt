package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.CushionConfigRequest
import com.maggie.app.data.model.CushionStatus
import com.maggie.app.data.model.cushionStateLabel
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.rechargePlanLabel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CushionScreen(
    viewModel: CushionViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.savedMessage) {
        uiState.savedMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSavedMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Matelas") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        val status = uiState.status

        if (status == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        uiState.error ?: "Matelas indisponible",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CushionSummaryCard(status)
            CushionSettingsCard(
                status = status,
                isSaving = uiState.isSaving,
                onSave = viewModel::configure,
            )
        }
    }
}

@Composable
private fun CushionSummaryCard(status: CushionStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Matelas de sécurité", style = MaterialTheme.typography.titleMedium)
                AssistChip(onClick = {}, label = { Text(cushionStateLabel(status.state)) })
            }

            Text(
                text = "${formatCents(status.currentCents)} sur ${formatCents(status.targetCents)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "${status.monthsCovered} mois de revenu couverts sur ${status.targetMonths}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LinearProgressIndicator(
                progress = { status.coveragePercent / 100f },
                color = if (status.state == "complete") {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
                modifier = Modifier.fillMaxWidth().height(8.dp).padding(top = 8.dp),
            )

            if (status.deficitCents > 0) {
                Text(
                    text = "Il manque ${formatCents(status.deficitCents)} — " +
                        rechargePlanLabel(status),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (status.isCappedByRechargeCap) {
                    Text(
                        text = "Le plafond mensuel allonge la durée plutôt que de forcer l'effort.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (status.accounts.isEmpty()) {
                Text(
                    text = "Aucun compte n'est marqué « matelas ».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(
                    text = "Comptes comptés",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                status.accounts.forEach { account ->
                    Text(
                        text = "${account.name} — ${formatCents(account.balanceCents, account.currency)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CushionSettingsCard(
    status: CushionStatus,
    isSaving: Boolean,
    onSave: (CushionConfigRequest) -> Unit,
) {
    var targetMonths by remember(status.targetMonths) {
        mutableStateOf(status.targetMonths.toString())
    }
    var income by remember(status.monthlyNetIncomeCents) {
        mutableStateOf((status.monthlyNetIncomeCents / 100.0).toString())
    }
    var cap by remember(status.rechargeCapCents) {
        mutableStateOf((status.rechargeCapCents / 100.0).toString())
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Réglages", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "La cible est un nombre de mois de revenu net : elle se recalcule si le " +
                    "revenu change.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = targetMonths,
                onValueChange = { targetMonths = it },
                label = { Text("Cible (mois de revenu)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = income,
                onValueChange = { income = it },
                label = { Text("Revenu net mensuel (€)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                value = cap,
                onValueChange = { cap = it },
                label = { Text("Plafond de recharge (€/mois)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )

            Button(
                onClick = {
                    onSave(
                        CushionConfigRequest(
                            targetMonths = targetMonths.toIntOrNull(),
                            monthlyNetIncomeCents = income.toDoubleOrNull()
                                ?.let { (it * 100).roundToInt() },
                            rechargeCapCents = cap.toDoubleOrNull()
                                ?.let { (it * 100).roundToInt() },
                        ),
                    )
                },
                enabled = !isSaving,
            ) {
                Text("Enregistrer")
            }
        }
    }
}
