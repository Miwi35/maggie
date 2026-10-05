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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.CategorizationRuleCreateRequest
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.describeRuleScope
import com.maggie.app.data.model.matchTypeLabel
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar

private val MATCH_TYPES = listOf("contains", "starts_with", "equals")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorizationRuleListScreen(
    viewModel: CategorizationRuleViewModel,
    onBack: () -> Unit,
    onOpenSuggestions: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.lastApplyMessage) {
        uiState.lastApplyMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearApplyMessage()
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
                title = { Text("Règles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    // The rules the statement already implies, rather than a blank
                    // page: the shortest way to a first rule.
                    IconButton(onClick = onOpenSuggestions) {
                        Icon(Icons.Default.Lightbulb, contentDescription = "Suggestions")
                    }
                    IconButton(onClick = { viewModel.applyRules() }, enabled = !uiState.isApplying) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Appliquer les règles")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Nouvelle règle")
            }
        },
    ) { paddingValues ->
        when {
            uiState.isLoading && uiState.rules.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.rules.isEmpty() -> {
                EmptyState(
                    modifier = Modifier.padding(paddingValues),
                    title = "Aucune règle pour l'instant",
                    description = "Une règle classe toute seule les opérations dont le libellé correspond, et vaut aussi pour l'historique.",
                    actionLabel = "Écrire une règle",
                    onAction = { showCreateDialog = true },
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.rules, key = { it.id }) { rule ->
                        val categoryName = uiState.categories
                            .firstOrNull { it.id == rule.categoryId }
                            ?.name
                        val scope = describeRuleScope(
                            rule.direction,
                            rule.minAmountCents,
                            rule.maxAmountCents,
                        )

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${matchTypeLabel(rule.matchType)} « ${rule.labelPattern} »",
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        text = listOfNotNull(categoryName, scope.ifEmpty { null })
                                            .joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { viewModel.deleteRule(rule.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Supprimer",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        RuleCreateDialog(
            categories = uiState.categories,
            onConfirm = { request ->
                viewModel.createRule(request)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

@Composable
private fun RuleCreateDialog(
    categories: List<Category>,
    onConfirm: (CategorizationRuleCreateRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var labelPattern by remember { mutableStateOf("") }
    var matchType by remember { mutableStateOf("contains") }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }

    val canSubmit = labelPattern.isNotBlank() && selectedCategoryId != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle règle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = labelPattern,
                    onValueChange = { labelPattern = it },
                    label = { Text("Le libellé…") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MATCH_TYPES.forEach { type ->
                        FilterChip(
                            selected = matchType == type,
                            onClick = { matchType = type },
                            label = { Text(matchTypeLabel(type)) },
                        )
                    }
                }
                Text("Catégorie", style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { category ->
                        FilterChip(
                            selected = selectedCategoryId == category.id,
                            onClick = { selectedCategoryId = category.id },
                            label = { Text(category.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val categoryId = selectedCategoryId ?: return@TextButton
                    onConfirm(
                        CategorizationRuleCreateRequest(
                            labelPattern = labelPattern.trim(),
                            category = "/api/categories/$categoryId",
                            matchType = matchType,
                        ),
                    )
                },
                enabled = canSubmit,
            ) {
                Text("Créer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        },
    )
}
