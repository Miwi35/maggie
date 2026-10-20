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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CategoryRow
import com.maggie.app.data.model.categoryKindLabel
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.FinanceTopBar
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

    CategoryPanes(viewModel, uiState, showsDetailPane) {
        CategoryTreeScreen(viewModel, uiState, onBack, onOpenRules)
    }
}

/**
 * The tree, the editor and the tablet's detail pane, without a bar of their own: the Configuration
 * page shows them as its « Catégories » tab. [onOpenRules], when given, adds the way to the rules.
 */
@Composable
fun CategoryListContent(
    viewModel: CategoryViewModel,
    modifier: Modifier = Modifier,
    onOpenRules: (() -> Unit)? = null,
    showsDetailPane: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsState()

    CategoryPanes(viewModel, uiState, showsDetailPane) {
        CategoryTreeContent(viewModel, uiState, onOpenRules, modifier)
    }
}

@Composable
private fun CategoryPanes(
    viewModel: CategoryViewModel,
    uiState: CategoryUiState,
    showsDetailPane: Boolean,
    tree: @Composable () -> Unit,
) {
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
        if (editor != null) editor() else tree()
        return
    }

    ListDetailPane(
        showsDetailPane = true,
        list = tree,
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
    Scaffold(
        topBar = {
            FinanceTopBar(Screen.CategoryList.route) {
                TopAppBar(
                    title = { Text("Catégories") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenRules) {
                            Icon(
                                Icons.AutoMirrored.Filled.Rule,
                                contentDescription = "Règles de catégorisation",
                            )
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        CategoryTreeContent(viewModel, uiState, onOpenRules = null, Modifier.padding(paddingValues))
    }
}

@Composable
private fun CategoryTreeContent(
    viewModel: CategoryViewModel,
    uiState: CategoryUiState,
    onOpenRules: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val rows = remember(uiState.categories, uiState.expanded) { uiState.rows }

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )

    Box(modifier = modifier.fillMaxSize()) {
        when {
            uiState.isLoading && uiState.categories.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.categories.isEmpty() -> {
                EmptyState(
                    title = "Aucune catégorie pour l'instant",
                    description = "Les catégories portent les budgets, les règles et la revue mensuelle. Commencez par celles où va l'essentiel de votre argent.",
                    actionLabel = "Créer une catégorie",
                    onAction = { viewModel.startCreating() },
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onOpenRules != null) {
                        item {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = onOpenRules) { Text("Règles de catégorisation") }
                            }
                        }
                    }
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
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        FloatingActionButton(
            onClick = { viewModel.startCreating() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Nouvelle catégorie")
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
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = INDENT * (row.level - 1)),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
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
