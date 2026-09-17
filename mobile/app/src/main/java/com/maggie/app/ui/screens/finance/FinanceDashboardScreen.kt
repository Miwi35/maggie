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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.consumedFraction
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.monthLabel
import com.maggie.app.data.model.postChangeLabel
import com.maggie.app.data.model.reasonText
import com.maggie.app.data.model.scoreLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceDashboardScreen(
    viewModel: FinanceDashboardViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val dashboard = uiState.dashboard

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

            when {
                uiState.isLoading && dashboard == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                dashboard == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            uiState.error ?: "Vue indisponible",
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
                        item { ScoreCard(dashboard) }
                        item { BalanceCard(dashboard) }
                        item { CapacityCard(dashboard) }
                        item { TopPostsCard(dashboard) }
                        item { EnvelopesCard(dashboard) }
                    }
                }
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
