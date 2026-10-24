package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.navigation.Screen

/** Planification · Indépendance (MAG-537): the counter and the net saving capacity, off the dashboard's tile. */
@Composable
fun IndependenceScreen(viewModel: FinanceDashboardViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = {},
        onRetry = viewModel::refresh,
    )
    Scaffold(
        topBar = { FinanceTopBar(Screen.Independence.route) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        val planning = uiState.dashboard?.tiles?.planning
        if (planning == null) {
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(32.dp)) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
            ) {
                item {
                    MaggieCard(modifier = Modifier.testTag(UiTags.FINANCE_TILE_PLANNING)) {
                        Column(modifier = Modifier.padding(16.dp)) { IndependenceBody(planning) }
                    }
                }
            }
        }
    }
}
