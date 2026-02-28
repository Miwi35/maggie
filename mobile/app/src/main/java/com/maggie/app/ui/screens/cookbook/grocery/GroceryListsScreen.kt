package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.GroceryItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroceryScreen(
    viewModel: GroceryViewModel,
    onNavigateToProducts: () -> Unit = {},
    onNavigateToStores: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.showAddSheet() }) {
                Icon(Icons.Default.Add, contentDescription = "Ajouter un article")
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // Header with overflow menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val checkedCount = uiState.groceryList?.items?.count { it.checked } ?: 0
                val totalCount = uiState.storeGroups.sumOf { it.items.size }
                Text(
                    text = "Liste de courses",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$checkedCount/$totalCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Produits") },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToProducts()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Magasins") },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToStores()
                                },
                            )
                        }
                    }
                }
            }

            // Main content with pull-to-refresh
            PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    uiState.isLoading && uiState.groceryList == null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    uiState.groceryList == null || uiState.storeGroups.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Aucun article dans la liste",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            uiState.storeGroups.forEach { group ->
                                item {
                                    Text(
                                        text = group.store?.name ?: "Non assigné",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    )
                                    HorizontalDivider()
                                }
                                items(group.items, key = { it.id ?: it.hashCode() }) { item ->
                                    GroceryItemRow(
                                        item = item,
                                        onToggle = { viewModel.toggleItemChecked(item) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add item bottom sheet
    if (uiState.showAddSheet) {
        AddItemSheet(
            products = uiState.products,
            stores = uiState.stores,
            onAddItem = { label, quantity, unit, storeId ->
                viewModel.addItem(label, quantity, unit, storeId)
            },
            onDismiss = { viewModel.hideAddSheet() },
        )
    }
}

@Composable
private fun GroceryItemRow(
    item: GroceryItem,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = item.checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (item.checked) TextDecoration.LineThrough else null,
            )
        }
        item.quantity?.let { qty ->
            val unitStr = item.unit?.name?.lowercase() ?: ""
            Text(
                text = "$qty $unitStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
