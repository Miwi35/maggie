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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.TileAccount
import com.maggie.app.data.model.monthLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.components.RhythmCurvePanel
import com.maggie.app.ui.components.ScoreRings
import com.maggie.app.ui.components.TreasuryBanner
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceDashboardScreen(
    viewModel: FinanceDashboardViewModel,
    monthBalanceViewModel: MonthBalanceViewModel,
    upcomingViewModel: UpcomingViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    // The account whose rejections the « N rejets » pill opens, on its Incidents tab.
    onOpenIncidents: (TileAccount) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val twoColumns = rememberAppLayout().detailPaneFits
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_OVERVIEW) }

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
            FinanceTopBar(Screen.FinanceDashboard.route) {
                TopAppBar(
                    title = { Text("Finance") },
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
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == TAB_OVERVIEW,
                    onClick = { selectedTab = TAB_OVERVIEW },
                    text = { Text("Vue d'ensemble") },
                    modifier = Modifier.testTag(UiTags.FINANCE_TAB_OVERVIEW),
                )
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
            when (selectedTab) {
                TAB_OVERVIEW -> OverviewTab(viewModel, uiState, twoColumns, onOpen, onOpenIncidents)
                TAB_CURRENT -> MonthBalanceTab(monthBalanceViewModel, onOpen)
                else -> UpcomingTab(upcomingViewModel, twoColumns)
            }
        }
    }
}

private const val TAB_OVERVIEW = 0
private const val TAB_CURRENT = 1
private const val TAB_UPCOMING = 2

/** « Vue d'ensemble »: everything the dashboard showed before the tabs, unchanged. */
@Composable
private fun OverviewTab(
    viewModel: FinanceDashboardViewModel,
    uiState: FinanceDashboardUiState,
    twoColumns: Boolean,
    onOpen: (String) -> Unit,
    onOpenIncidents: (TileAccount) -> Unit,
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

        val head: LazyListScope.() -> Unit = {
            if (dashboard?.todo?.reviewReady == true) {
                item {
                    Button(
                        onClick = { onOpen(Screen.MonthlyReview.route) },
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.FINANCE_REVIEW_SHORTCUT),
                    ) {
                        Text("Revue ce soir")
                    }
                }
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
                item { AccountsTileCard(dashboard.tiles.accounts, dashboard.todo, onOpen, onOpenIncidents) }
                item { BudgetTileCard(dashboard.tiles.budget, dashboard.todo, onOpen) }
                item { PlanningTileCard(dashboard.tiles.planning, onOpen) }
            }
        }

        if (twoColumns) {
            // Room for two panes: the scores and the next project on the left, the tiles on the right.
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
