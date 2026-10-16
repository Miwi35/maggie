package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.model.toSortPillLabel
import com.maggie.app.data.model.monthLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.RhythmCurvePanel
import com.maggie.app.ui.components.ScoreRings
import com.maggie.app.ui.components.TreasuryBanner
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.navigation.FinanceAccess
import com.maggie.app.ui.navigation.FinanceFrequency
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.navigation.financeAccessesBy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceDashboardScreen(
    viewModel: FinanceDashboardViewModel,
    upcomingViewModel: UpcomingViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    // The pile of the lines to qualify: the pill shows when it is not empty.
    toQualify: ToSortPile? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val twoColumns = rememberAppLayout().detailPaneFits
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_CURRENT) }

    // Back from the treasury screen, the banner follows what was prepared there.
    LaunchedEffect(Unit) { viewModel.refreshTreasury() }

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
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == TAB_CURRENT,
                    onClick = { selectedTab = TAB_CURRENT },
                    text = { Text("État courant") },
                    modifier = Modifier.testTag(UiTags.FINANCE_TAB_CURRENT),
                )
                Tab(
                    selected = selectedTab == TAB_UPCOMING,
                    onClick = { selectedTab = TAB_UPCOMING },
                    text = { Text("À venir") },
                    modifier = Modifier.testTag(UiTags.FINANCE_TAB_UPCOMING),
                )
            }
            if (selectedTab == TAB_CURRENT) {
                CurrentStateTab(viewModel, uiState, twoColumns, toQualify, onOpen)
            } else {
                UpcomingTab(upcomingViewModel, twoColumns)
            }
        }
    }
}

private const val TAB_CURRENT = 0
private const val TAB_UPCOMING = 1

/** « État courant »: everything the dashboard showed before the tabs, unchanged. */
@Composable
private fun CurrentStateTab(
    viewModel: FinanceDashboardViewModel,
    uiState: FinanceDashboardUiState,
    twoColumns: Boolean,
    toQualify: ToSortPile?,
    onOpen: (String) -> Unit,
) {
    val dashboard = uiState.dashboard
    Column(modifier = Modifier.fillMaxSize()) {
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
        val head: LazyListScope.() -> Unit = {
            toQualify?.let(::toSortPillLabel)?.let { label ->
                item { ToQualifyPill(label, onClick = { onOpen(Screen.TransactionsToSort.route) }) }
            }
            uiState.treasuryBanner?.let { banner ->
                item {
                    TreasuryBanner(
                        overdraftOn = banner.overdraftOn,
                        balanceBeforeSalaryCents = banner.balanceBeforeSalaryCents,
                        balanceBeforeSalaryOn = banner.balanceBeforeSalaryOn,
                        onClick = { onOpen(Screen.Treasury.route) },
                    )
                }
            }
            if (dashboard != null) {
                dashboard.scores?.let { scores ->
                    item { ScoreRings(week = scores.week, month = scores.month) }
                    item { RhythmCurvePanel(week = scores.week.rhythm, month = scores.month.rhythm) }
                }
                item { NextProjectCard() }
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
        }
        val body: LazyListScope.() -> Unit = {
            if (dashboard != null) {
                item { AccountsTileCard(dashboard.tiles.accounts, onOpen) }
                item { BudgetTileCard(dashboard.tiles.budget, onOpen) }
                item { PlanningTileCard(dashboard.tiles.planning, onOpen) }
            }
            item { AccessRow(financeAccessesBy(FinanceFrequency.DAILY), onOpen) }
            item { AccessSection(FinanceFrequency.MONTHLY, onOpen) }
            item { AccessSection(FinanceFrequency.RARE, onOpen) }
        }

        if (twoColumns) {
            // Room for two panes: the scores and the next project on the left, the tiles and the accesses on the right.
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = head,
                )
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = body,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                head()
                body()
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
