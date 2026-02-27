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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.GroceryItem

@Composable
fun GroceryScreen(
    viewModel: GroceryViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()

    when {
        uiState.isLoading && uiState.groceryList == null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        uiState.groceryList == null || uiState.storeGroups.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucun article dans la liste", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        else -> {
            val checkedCount = uiState.groceryList?.items?.count { it.checked } ?: 0
            val totalCount = uiState.storeGroups.sumOf { it.items.size }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Liste de courses",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "$checkedCount/$totalCount",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

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
