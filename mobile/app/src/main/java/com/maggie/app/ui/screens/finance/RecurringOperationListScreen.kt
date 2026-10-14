package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ProposalClaim
import com.maggie.app.data.model.RecurringOperationRecord
import com.maggie.app.data.model.RecurringProposal
import com.maggie.app.data.model.RecurringSeries
import com.maggie.app.data.model.RecurringSuggestion
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.categoryMatchesNature
import com.maggie.app.data.model.claimsOf
import com.maggie.app.data.model.transactionNature
import com.maggie.app.data.model.describePriceChange
import com.maggie.app.data.model.describeReason
import com.maggie.app.data.model.describeSchedule
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.formatDay
import com.maggie.app.data.model.referenceSourceLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar

/**
 * The recurring operations: what they cost each month and each year, which one did not arrive,
 * which one went up, and the lines the history proposes to attach or to turn into a series.
 *
 * [seed] is the transaction the owner asked to turn into a series from its detail screen: the
 * editor opens on it once, and leaving it goes back to that transaction ([onBackToTransaction]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringOperationListScreen(
    viewModel: RecurringOperationViewModel,
    onBack: () -> Unit,
    seed: Transaction? = null,
    onSeedConsumed: () -> Unit = {},
    onBackToTransaction: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // The suggestion whose heading is being chosen: a screen of its own, never a dropdown.
    var choosingFor by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(seed) {
        if (seed != null) {
            viewModel.startFromTransaction(seed)
            onSeedConsumed()
        }
    }

    LaunchedEffect(uiState.returnToTransaction) {
        if (uiState.returnToTransaction) {
            viewModel.consumeReturnToTransaction()
            onBackToTransaction()
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    uiState.preview?.let { preview ->
        RecurringHistoryPreviewScreen(
            preview = preview,
            isSaving = uiState.isSaving,
            error = uiState.error,
            onConfirm = viewModel::confirmPreview,
            onLater = viewModel::dismissPreview,
            onClearError = viewModel::clearError,
        )
        return
    }

    uiState.editing?.let { editing ->
        RecurringOperationEditScreen(
            state = uiState,
            editing = editing,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
            onBack = viewModel::closeEditor,
            onClearError = viewModel::clearError,
            onDetachLine = viewModel::detachSeriesLine,
            onRetryLines = viewModel::reloadSeriesLines,
        )
        return
    }

    choosingFor?.let { key ->
        BackHandler { choosingFor = null }
        RecurringCategorySearchScreen(
            // A heading of the other nature than the payment would be refused or misleading.
            categories = uiState.categories.filter { category ->
                val amount = uiState.visibleSuggestions.firstOrNull { suggestionKey(it) == key }?.referenceAmountCents
                amount == null || categoryMatchesNature(category, transactionNature(amount))
            },
            selected = uiState.suggestionCategories[key],
            onPick = {
                viewModel.chooseSuggestionCategory(key, it.id)
                choosingFor = null
            },
            onBack = { choosingFor = null },
        )
        return
    }

    BackHandler(onBack = onBack)

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Opérations récurrentes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::startCreate,
                modifier = Modifier.testTag(UiTags.RECURRING_NEW),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Nouvelle opération récurrente")
            }
        },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = uiState.isLoading,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            when {
                uiState.overview == null && uiState.error == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.isEmpty -> {
                    EmptyState(
                        title = "Aucune opération récurrente",
                        description = "Loyer, abonnements, assurances : une opération récurrente suit ce qui revient chaque mois, son coût et la date attendue. Créez-en une avec « + », ou à partir d'une transaction.",
                    )
                }
                else -> RecurringContent(uiState, viewModel, onChooseCategory = { choosingFor = it })
            }
        }
    }
}

@Composable
private fun RecurringContent(
    uiState: RecurringOperationUiState,
    viewModel: RecurringOperationViewModel,
    onChooseCategory: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        uiState.overview?.totals?.takeIf { uiState.operations.isNotEmpty() }?.let { totals ->
            item(key = "totals") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Coût mensuel", style = MaterialTheme.typography.bodySmall)
                        Text(
                            formatCents(-totals.monthlyCostCents),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            "${formatCents(-totals.yearlyCostCents)} par an · ${totals.activeCount} en cours",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (totals.monthlyIncomeCents != 0) {
                            Text(
                                "Revenus récurrents : ${formatCents(totals.monthlyIncomeCents)} par mois",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (uiState.proposals.isNotEmpty()) {
            item(key = "proposals-title") { SectionHeader("À confirmer", "Rien n'est rattaché tant que vous n'avez pas dit oui.") }
            items(uiState.proposals, key = { "proposal-${it.transactionId}" }) { proposal ->
                ProposalCard(
                    proposal = proposal,
                    busy = uiState.actingOn != null,
                    onAccept = { claim -> viewModel.acceptProposal(proposal, claim) },
                    onDismiss = { viewModel.dismissProposal(proposal.transactionId) },
                )
            }
        }

        if (uiState.operations.isNotEmpty()) {
            item(key = "operations-title") { SectionHeader("En cours") }
            items(uiState.operations, key = { "series-${it.id}" }) { series ->
                SeriesCard(
                    series = series,
                    record = uiState.records[series.id],
                    onClick = { viewModel.startEdit(series.id) },
                )
            }
        }

        if (uiState.visibleSuggestions.isNotEmpty()) {
            item(key = "suggestions-title") {
                SectionHeader("Détectées dans vos transactions", "Des paiements qui reviennent au même rythme, sans opération récurrente.")
            }
            items(uiState.visibleSuggestions, key = { "suggestion-${suggestionKey(it)}" }) { suggestion ->
                SuggestionCard(
                    suggestion = suggestion,
                    state = uiState,
                    onChooseCategory = { onChooseCategory(suggestionKey(suggestion)) },
                    onAccept = { viewModel.acceptSuggestion(suggestion) },
                    onDismiss = { viewModel.dismissSuggestion(suggestionKey(suggestion)) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, description: String? = null) {
    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The two warnings of a series: a due date that did not come, a price that moved. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SeriesBadges(series: RecurringSeries, onClick: () -> Unit = {}) {
    if (series.missedOccurrence == null && series.priceChange == null) return

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        series.missedOccurrence?.let { missed ->
            AssistChip(
                onClick = onClick,
                label = { Text("Échéance manquée · ${missed.daysOverdue} j de retard") },
                colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag(UiTags.RECURRING_MISSED_BADGE),
            )
        }
        series.priceChange?.let { change ->
            AssistChip(
                onClick = onClick,
                label = {
                    val kind = if (change.percent > 0) "Hausse" else "Baisse"
                    Text("$kind de prix · ${describePriceChange(change, series.currency)}")
                },
                modifier = Modifier.testTag(UiTags.RECURRING_PRICE_BADGE),
            )
        }
    }
}

@Composable
fun SeriesCard(series: RecurringSeries, record: RecurringOperationRecord?, onClick: () -> Unit = {}) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(series.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(formatCents(series.referenceAmountCents, series.currency), style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                text = "${referenceSourceLabel(series.referenceSource).replaceFirstChar { it.uppercase() }} · " +
                    describeSchedule(series.period, series.dayRule, record?.anchorOn?.takeIf { it.isNotBlank() } ?: series.nextOccurrenceOn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // Signed like a transaction: an expense is negative, so its cost is read with the sign flipped.
                text = if (series.monthlyCostCents > 0) {
                    "Revenu : ${formatCents(series.monthlyCostCents, series.currency)} par mois · ${formatCents(series.yearlyCostCents, series.currency)} par an"
                } else {
                    "${formatCents(-series.monthlyCostCents, series.currency)} par mois · ${formatCents(-series.yearlyCostCents, series.currency)} par an"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            series.nextOccurrenceOn?.let {
                Text("Prochaine échéance : ${formatDay(it)}", style = MaterialTheme.typography.bodySmall)
            }
            record?.counterpartyName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "Contrepartie attendue : $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            listOf(series.accountName, series.categoryName).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let {
                Text(
                    it.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            series.endsOn?.let {
                Text("Jusqu'au ${formatDay(it)}", style = MaterialTheme.typography.bodySmall)
            }
            SeriesBadges(series, onClick)
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: RecurringProposal,
    busy: Boolean,
    onAccept: (ProposalClaim) -> Unit,
    onDismiss: () -> Unit,
) {
    val claims = claimsOf(proposal)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(proposal.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(formatCents(proposal.amountCents), style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "Transaction du ${formatDay(proposal.bookedAt)} : ${describeReason(proposal)}",
                style = MaterialTheme.typography.bodySmall,
            )
            claims.forEach { claim ->
                Button(
                    onClick = { onAccept(claim) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.RECURRING_PROPOSAL_ACCEPT),
                ) {
                    Text("Rattacher à « ${claim.recurringOperationLabel ?: "?"} »")
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Ignorer") }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: RecurringSuggestion,
    state: RecurringOperationUiState,
    onChooseCategory: () -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val chosen = state.categoryOf(suggestion)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(suggestion.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(formatCents(suggestion.referenceAmountCents), style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "${describeSchedule(suggestion.period, suggestion.dayRule, suggestion.anchorOn)} · ${suggestion.occurrences} transactions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (suggestion.accountName.isNotBlank()) {
                Text(suggestion.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val categoryName = suggestion.categoryName ?: state.categories.firstOrNull { it.id == chosen }?.name
            FilterChip(
                selected = chosen != null,
                onClick = onChooseCategory,
                label = { Text(categoryName ?: "Catégorie à choisir") },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Ignorer") }
                Button(
                    onClick = onAccept,
                    enabled = state.canAccept(suggestion),
                    modifier = Modifier.testTag(UiTags.RECURRING_SUGGESTION_ACCEPT),
                ) {
                    Text("Créer l'opération récurrente")
                }
            }
        }
    }
}
