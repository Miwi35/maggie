package com.maggie.app.ui.screens.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.ui.UiTags
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private val TABS = listOf("Aujourd'hui", "Semaine", "Mois")

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = koinViewModel(),
    onEventClick: (ExpandedEvent) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.DASHBOARD),
    ) {
        TabRow(selectedTabIndex = pagerState.currentPage) {
            TABS.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title) },
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = uiState.isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (uiState.error != null && !uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Erreur : ${uiState.error}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (page) {
                        0 -> TodayPage(uiState, viewModel, onEventClick)
                        1 -> WeekPage(uiState, viewModel, onEventClick)
                        2 -> MonthPage(uiState, viewModel, onEventClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayPage(
    uiState: DashboardUiState,
    viewModel: DashboardViewModel,
    onEventClick: (ExpandedEvent) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            DashboardSection(
                title = "Aujourd'hui",
                events = uiState.todayEvents,
                tasks = uiState.todayTasks,
                onToggleTaskDone = { id, done -> viewModel.toggleTaskDone(id, done) },
                onEventClick = onEventClick,
            )
        }
        item {
            DashboardSection(
                title = "Demain",
                events = uiState.tomorrowEvents,
                tasks = uiState.tomorrowTasks,
                onToggleTaskDone = { id, done -> viewModel.toggleTaskDone(id, done) },
                onEventClick = onEventClick,
            )
        }
    }
}

@Composable
private fun WeekPage(
    uiState: DashboardUiState,
    viewModel: DashboardViewModel,
    onEventClick: (ExpandedEvent) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            DashboardSection(
                title = "Cette semaine",
                events = uiState.weekEvents,
                tasks = uiState.weekTasks,
                showDate = true,
                onToggleTaskDone = { id, done -> viewModel.toggleTaskDone(id, done) },
                onEventClick = onEventClick,
            )
        }
    }
}

@Composable
private fun MonthPage(
    uiState: DashboardUiState,
    viewModel: DashboardViewModel,
    onEventClick: (ExpandedEvent) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            DashboardSection(
                title = "Ce mois",
                events = uiState.monthEvents,
                tasks = uiState.monthTasks,
                showDate = true,
                onToggleTaskDone = { id, done -> viewModel.toggleTaskDone(id, done) },
                onEventClick = onEventClick,
            )
        }
    }
}
