package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CategoryRow
import com.maggie.app.data.model.categoryKindLabel
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.layout.ListDetailPane
import com.maggie.app.ui.navigation.Screen

/**
 * The categories as a tree of up to three levels, opened on the editor of the one touched.
 * On a phone the editor replaces the tree; with [showsDetailPane] (a tablet) it sits beside it.
 */
@Composable
fun CategoryListScreen(
    viewModel: CategoryViewModel,
    onBack: () -> Unit,
    onOpenRules: () -> Unit = {},
    showsDetailPane: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsState()
    val editing = uiState.editing

    // A category has a screen of its own, new or existing (MAG-353): the list is only
    // what is behind it.
    val editor: (@Composable () -> Unit)? = editing?.let { open ->
        {
            CategoryEditScreen(
                state = uiState,
                editing = open,
                onSave = { form ->
                    val category = open.category
                    if (category == null) {
                        viewModel.createCategory(form.toRequest())
                    } else {
                        viewModel.updateCategory(category.id, form.changes())
                    }
                },
                onBack = viewModel::closeEditor,
                onAskDelete = viewModel::askDelete,
                onConfirmDelete = viewModel::confirmDelete,
                onCancelDelete = viewModel::cancelDelete,
                onClearError = viewModel::clearError,
            )
        }
    }

    if (!showsDetailPane) {
        if (editor != null) editor() else CategoryTreeScreen(viewModel, uiState, onBack, onOpenRules)
        return
    }

    ListDetailPane(
        showsDetailPane = true,
        list = { CategoryTreeScreen(viewModel, uiState, onBack, onOpenRules) },
        detail = editor,
        placeholder = "Touchez une catégorie pour la modifier ici.",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryTreeScreen(
    viewModel: CategoryViewModel,
    uiState: CategoryUiState,
    onBack: () -> Unit,
    onOpenRules: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val rows = remember(uiState.categories, uiState.expanded) { uiState.rows }

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )

    Scaffold(
        topBar = {
            FinanceTopBar(Screen.CategoryList.route, actions = {
                IconButton(onClick = onOpenRules) {
                    Icon(
                        Icons.AutoMirrored.Filled.Rule,
                        contentDescription = "Règles de catégorisation",
                    )
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.startCreating() }) {
                Icon(Icons.Default.Add, contentDescription = "Nouvelle catégorie")
            }
        },
    ) { paddingValues ->
        when {
            uiState.isLoading && uiState.categories.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.categories.isEmpty() -> {
                EmptyState(
                    modifier = Modifier.padding(paddingValues),
                    title = "Aucune catégorie pour l'instant",
                    description = "Les catégories portent les budgets, les règles et la revue mensuelle. Commencez par celles où va l'essentiel de votre argent.",
                    actionLabel = "Créer une catégorie",
                    onAction = { viewModel.startCreating() },
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { it.category.id }) { row ->
                        CategoryTreeRow(
                            row = row,
                            selected = uiState.editing?.category?.id == row.category.id,
                            onOpen = { viewModel.startEditing(row.category.id) },
                            onToggle = { viewModel.toggleExpanded(row.category.id) },
                            onAddChild = { viewModel.startCreating(row.category.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTreeRow(
    row: CategoryRow,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onAddChild: () -> Unit,
) {
    val category = row.category
    MaggieCard(
        modifier = Modifier.fillMaxWidth().padding(start = INDENT * (row.level - 1)),
        selected = selected,
        listRow = true,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.hasChildren) {
                IconButton(onClick = onToggle) {
                    Icon(
                        if (row.expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = (if (row.expanded) "Replier " else "Déplier ") + category.name,
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            CategoryGlyph(color = category.color, icon = category.icon)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = category.name, style = MaterialTheme.typography.bodyLarge)
                categoryKindLabel(category)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (row.canAddChild) {
                IconButton(onClick = onAddChild) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Ajouter une sous-catégorie à ${category.name}")
                }
            }
        }
    }
}

private val INDENT = 20.dp
