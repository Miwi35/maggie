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
import androidx.compose.material3.Button
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.navigation.DeepLinks
import com.maggie.app.ui.navigation.ProvideFinanceNav
import com.maggie.app.ui.navigation.financeSubviewState
import com.maggie.app.ui.navigation.FinanceSection
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    upcomingViewModel: UpcomingViewModel,
    onOpen: (String) -> Unit,
    // The account whose rejections the « N rejets » pill opens, on its Incidents tab.
    onOpenIncidents: (TileAccount) -> Unit = {},
) {
    ProvideFinanceNav {
        FinanceDashboardContent(viewModel, upcomingViewModel, onOpen, onOpenIncidents)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinanceDashboardContent(
    viewModel: FinanceDashboardViewModel,
    upcomingViewModel: UpcomingViewModel,
    onOpen: (String) -> Unit,
    onOpenIncidents: (TileAccount) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val twoColumns = rememberAppLayout().detailPaneFits
    val (subview, _) = financeSubviewState(FinanceSection.DASHBOARD)

    // Back from the treasury screen, the banner follows what was prepared there.
    LaunchedEffect(Unit) { viewModel.refreshTreasury() }

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )

    Scaffold(
        topBar = { FinanceTopBar(Screen.FinanceDashboard.route) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (subview.key) {
                SUBVIEW_UPCOMING -> UpcomingTab(upcomingViewModel, twoColumns)
                else -> CurrentTab(uiState, twoColumns, onOpen, onOpenIncidents)
            }
        }
    }
}

private const val SUBVIEW_UPCOMING = "upcoming"

/** « État courant » (MAG-537): the current month only — the review card when one is due, the rings, the next project, the tiles. */
@Composable
private fun CurrentTab(
    uiState: FinanceDashboardUiState,
    twoColumns: Boolean,
    onOpen: (String) -> Unit,
    onOpenIncidents: (TileAccount) -> Unit,
) {
    val dashboard = uiState.dashboard
    Column(modifier = Modifier.fillMaxSize()) {
        val head: LazyListScope.() -> Unit = {
            if (dashboard?.todo?.reviewReady == true) {
                item {
                    ReviewDueCard(
                        title = reviewDueTitle(uiState.month),
                        onLaunch = { onOpen(Screen.FinanceReviewActivity.route) },
                    )
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
                    item {
                        ScoreRings(
                            week = scores.week,
                            month = scores.month,
                            onClassify = { onOpen(DeepLinks.REVIEW_TO_SORT_ROUTE) },
                        )
                    }
                    item { RhythmCurvePanel(week = scores.week.rhythm, month = scores.month.rhythm) }
                }
                val planned = dashboard.nextProject
                val beyond = dashboard.projectBeyondHorizon
                if (planned != null) {
                    item { NextProjectCard(planned) }
                } else if (beyond != null) {
                    item { NextProjectBeyondHorizonCard(beyond) }
                }
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
                item { PlanningTileCard(dashboard.tiles.planning, uiState.planning, onOpen) }
            }
        }

        if (twoColumns) {
            // Room for two panes: the scores and the next project on the left, the tiles on the right.
            Row(modifier = Modifier.fillMaxSize().testTag(UiTags.FINANCE_TWO_COLUMNS), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** The month a review covers, in French: « Revue d'octobre », « Revue de mars ». */
internal fun reviewDueTitle(month: Int): String {
    val name = monthLabel(month).lowercase()
    val elision = name.firstOrNull()?.let { it in "aeiouhéèêôû" } == true
    return if (elision) "Revue d'$name" else "Revue de $name"
}

/** The review waiting for tonight (owner, 21 Oct.): a 64 dp card with its time and one « Lancer » pill, above the rings. */
@Composable
private fun ReviewDueCard(title: String, onLaunch: () -> Unit) {
    MaggieCard(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(UiTags.FINANCE_REVIEW_CARD)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text("ce soir · 20 h", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = onLaunch,
                modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.FINANCE_REVIEW_SHORTCUT),
                shape = RoundedCornerShape(MaggieTokens.Radius.pill),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("Lancer")
            }
        }
    }
}
