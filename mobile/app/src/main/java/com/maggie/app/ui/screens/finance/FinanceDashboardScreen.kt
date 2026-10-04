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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.consumedFraction
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.monthLabel
import com.maggie.app.data.model.postChangeLabel
import com.maggie.app.data.model.reasonText
import com.maggie.app.data.model.scoreLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.navigation.FinanceAccess
import com.maggie.app.ui.navigation.FinanceFrequency
import com.maggie.app.ui.navigation.financeAccessesBy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceDashboardScreen(
    viewModel: FinanceDashboardViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val dashboard = uiState.dashboard

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Finance") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.shiftPeriod(-1) }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Mois précédent")
                }
                Text(
                    text = "${monthLabel(uiState.month)} ${uiState.year}",
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = { viewModel.shiftPeriod(1) }) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Mois suivant")
                }
            }

            // The accesses do not depend on the dashboard figures: a failed load must not lock the module.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (dashboard != null) item { ScoreCard(dashboard) }
                item { AccessRow(financeAccessesBy(FinanceFrequency.DAILY), onOpen) }
                if (dashboard != null) {
                    item { BalanceCard(dashboard) }
                    item { CapacityCard(dashboard) }
                    item { TopPostsCard(dashboard) }
                    item { EnvelopesCard(dashboard) }
                } else {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            if (uiState.isLoading) {
                                CircularProgressIndicator()
                            } else {
                                Text(
                                    uiState.error ?: "Vue indisponible",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item { AccessSection(FinanceFrequency.MONTHLY, onOpen) }
                item { AccessSection(FinanceFrequency.RARE, onOpen) }
            }
        }
    }
}

@Composable
private fun AccessRow(accesses: List<FinanceAccess>, onOpen: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accesses.forEach { access ->
            FilledTonalButton(
                onClick = { onOpen(access.route) },
                modifier = Modifier.weight(1f).testTag(UiTags.financeAccess(access.route)),
            ) {
                Text(access.label, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun AccessSection(frequency: FinanceFrequency, onOpen: (String) -> Unit) {
    val title = requireNotNull(frequency.title) { "The daily accesses have no section" }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        financeAccessesBy(frequency).forEach { access ->
            TextButton(
                onClick = { onOpen(access.route) },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.financeAccess(access.route)),
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
            ) {
                Text(access.label, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }
    }
}

@Composable
private fun ScoreCard(dashboard: FinanceDashboard) {
    val container = when (dashboard.score.score) {
        "green" -> MaterialTheme.colorScheme.primaryContainer
        "orange" -> MaterialTheme.colorScheme.tertiaryContainer
        "red" -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(scoreLabel(dashboard.score.score), style = MaterialTheme.typography.titleMedium)
            dashboard.score.reasons.forEach { reason ->
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
private fun BalanceCard(dashboard: FinanceDashboard) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Solde de tous les comptes", style = MaterialTheme.typography.titleSmall)
            Text(
                text = formatCents(dashboard.balance.totalCents),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                text = "dont ${formatCents(dashboard.balance.cushionCents)} de matelas — " +
                    "${formatCents(dashboard.balance.availableCents)} disponibles",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CapacityCard(dashboard: FinanceDashboard) {
    val capacity = dashboard.savingCapacity

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Capacité d'épargne nette", style = MaterialTheme.typography.titleSmall)
            if (capacity.isIncomeKnown) {
                Text(
                    text = "${formatCents(capacity.netCapacityCents)} / mois",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Text(
                    text = "${formatCents(capacity.monthlyNetIncomeCents)} de revenu − " +
                        "${formatCents(capacity.loanPaymentsCents)} de mensualités − " +
                        "${formatCents(capacity.estimatedLifestyleCents)} de train de vie",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Renseignez votre revenu dans le matelas pour l'obtenir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TopPostsCard(dashboard: FinanceDashboard) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Principaux postes du mois", style = MaterialTheme.typography.titleSmall)

            if (dashboard.topPosts.isEmpty()) {
                Text(
                    text = "Aucune dépense sur ce mois.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            dashboard.topPosts.forEach { post ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = post.categoryName ?: "Non catégorisé",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = postChangeLabel(post),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = formatCents(post.spentCents),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun EnvelopesCard(dashboard: FinanceDashboard) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Enveloppes", style = MaterialTheme.typography.titleSmall)

            if (dashboard.budgets.isEmpty()) {
                Text(
                    text = "Aucune enveloppe sur ce mois.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            dashboard.budgets.forEach { line ->
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(line.categoryName, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "${formatCents(line.consumedCents, line.currency)} / " +
                                formatCents(line.amountCents, line.currency),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (line.isOverspent) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    LinearProgressIndicator(
                        progress = { consumedFraction(line.consumedCents, line.amountCents) },
                        color = when {
                            line.isOverspent -> MaterialTheme.colorScheme.error
                            line.isOvercommitted -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
