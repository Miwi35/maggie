package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.RuleSuggestion
import com.maggie.app.data.model.directionLabel
import com.maggie.app.data.model.suggestionWeight
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar

/**
 * The rules the statement already implies, one merchant per card.
 *
 * Writing rules from a blank page means remembering how your bank spells each
 * shop, which nobody does. Here the history answers instead: the merchants that
 * come back, what they cost, and a heading to confirm or to change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleSuggestionListScreen(
    viewModel: RuleSuggestionViewModel,
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

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Suggestions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = uiState.isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            when {
                uiState.isLoading && uiState.suggestions.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.suggestions.isEmpty() -> {
                    EmptyState(
                        title = "Rien à proposer pour l'instant",
                        description = "Les suggestions viennent des opérations déjà importées : un commerçant qui revient au moins deux fois et qu'aucune règle ne couvre encore.",
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(uiState.suggestions, key = { it.pattern }) { suggestion ->
                            SuggestionCard(
                                suggestion = suggestion,
                                categories = uiState.categories,
                                chosenCategoryId = uiState.chosenCategoryId(suggestion.pattern),
                                canAccept = uiState.canAccept(suggestion.pattern),
                                onChooseCategory = { categoryId ->
                                    viewModel.chooseCategory(suggestion.pattern, categoryId)
                                },
                                onAccept = { viewModel.accept(suggestion.pattern) },
                                onDismiss = { viewModel.dismiss(suggestion.pattern) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: RuleSuggestion,
    categories: List<Category>,
    chosenCategoryId: String?,
    canAccept: Boolean,
    onChooseCategory: (String) -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = suggestion.pattern, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = listOfNotNull(
                    suggestionWeight(suggestion),
                    directionLabel(suggestion.direction).takeIf { suggestion.direction != "any" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (suggestion.samples.isNotEmpty()) {
                Text(
                    text = suggestion.samples.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = if (chosenCategoryId == null) "Catégorie à choisir" else "Catégorie",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                categories.forEach { category ->
                    FilterChip(
                        selected = chosenCategoryId == category.id,
                        onClick = { onChooseCategory(category.id) },
                        label = { Text(category.name) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Ignorer")
                }
                Button(onClick = onAccept, enabled = canAccept) {
                    Text("Créer la règle")
                }
            }
        }
    }
}
