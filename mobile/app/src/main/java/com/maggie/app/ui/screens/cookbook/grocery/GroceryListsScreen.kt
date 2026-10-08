package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.ui.theme.MaggieTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroceryScreen(
    viewModel: GroceryViewModel,
    onNavigateToProducts: () -> Unit = {},
    onNavigateToStores: () -> Unit = {},
    openItemId: String? = null,
    onOpenItemHandled: () -> Unit = {},
    // Set when the item opens in the detail pane beside the list instead of a sheet (MAG-263).
    onOpenItemInPane: ((GroceryItem) -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    var itemPendingDelete by remember { mutableStateOf<GroceryItem?>(null) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<GroceryItem?>(null) }
    fun openDetail(item: GroceryItem) {
        if (onOpenItemInPane != null) onOpenItemInPane(item) else detailItem = item
    }

    // A deep link names an item: open its sheet once the list has it, or give up once the list is loaded without it.
    LaunchedEffect(openItemId, uiState.groceryList, uiState.isLoading, uiState.error) {
        if (openItemId == null) return@LaunchedEffect
        val list = uiState.groceryList
        if (list == null) {
            if (uiState.error != null) onOpenItemHandled()
            return@LaunchedEffect
        }
        val item = list.items.firstOrNull { it.id == openItemId }
        if (item != null) {
            openDetail(item)
            onOpenItemHandled()
        } else if (!uiState.isLoading) {
            onOpenItemHandled()
        }
    }

    Scaffold(
        floatingActionButton = {
            if (!uiState.isSelecting) {
                FloatingActionButton(onClick = { viewModel.showAddSheet() }) {
                    Icon(Icons.Default.Add, contentDescription = "Ajouter un article")
                }
            }
        },
        bottomBar = {
            if (uiState.isSelecting) {
                SelectionBottomBar(
                    selectedCount = uiState.selectedIds.size,
                    onCheck = { viewModel.checkSelectedItems() },
                    onDelete = { showDeleteSelectedDialog = true },
                    onClose = { viewModel.clearSelection() },
                )
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
                val checkedCount = uiState.checkedCount
                val totalCount = uiState.totalCount
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
                    uiState.groceryList == null || (uiState.storeGroups.isEmpty() && uiState.laterItems.isEmpty()) -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Aucun article dans la liste",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        val lazyListState = rememberLazyListState()
                        var laterExpanded by remember { mutableStateOf(false) }
                        val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
                            // Find which store group these items belong to
                            val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
                            val toKey = to.key as? String ?: return@rememberReorderableLazyListState

                            // Only allow reorder within same store group
                            val fromStoreKey = fromKey.substringBefore("|")
                            val toStoreKey = toKey.substringBefore("|")
                            if (fromStoreKey != toStoreKey) return@rememberReorderableLazyListState

                            val group = uiState.storeGroups.find {
                                (it.store?.id ?: "__unassigned__") == fromStoreKey
                            } ?: return@rememberReorderableLazyListState
                            val fromId = fromKey.substringAfter("|")
                            val toId = toKey.substringAfter("|")
                            val fromIdx = group.items.indexOfFirst { it.id == fromId }
                            val toIdx = group.items.indexOfFirst { it.id == toId }
                            if (fromIdx != -1 && toIdx != -1) {
                                viewModel.moveItem(fromStoreKey, fromIdx, toIdx)
                            }
                        }

                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            uiState.storeGroups.forEach { group ->
                                val storeId = group.store?.id
                                val storeKey = storeId ?: "__unassigned__"
                                val storeName = group.store?.name ?: "Non assign\u00e9"
                                val groupHasChecked = group.items.any { it.checked }
                                item(key = "header-$storeKey") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp, bottom = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = storeName,
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        if (groupHasChecked && storeId != null && !uiState.isSelecting) {
                                            TextButton(onClick = {
                                                viewModel.finishStore(storeId, storeName)
                                            }) {
                                                Icon(
                                                    Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text("Termin\u00e9")
                                            }
                                        }
                                    }
                                    HorizontalDivider()
                                }
                                items(
                                    group.items,
                                    key = { "$storeKey|${it.id ?: it.hashCode()}" },
                                ) { groceryItem ->
                                    ReorderableItem(
                                        reorderableLazyListState,
                                        key = "$storeKey|${groceryItem.id ?: groceryItem.hashCode()}",
                                    ) { isDragging ->
                                        SwipeableGroceryItem(
                                            item = groceryItem,
                                            isSelecting = uiState.isSelecting,
                                            isSelected = groceryItem.id in uiState.selectedIds,
                                            isDragging = isDragging,
                                            onSwipeRight = {
                                                if (!groceryItem.checked) {
                                                    viewModel.toggleItemChecked(groceryItem)
                                                }
                                            },
                                            onSwipeLeft = {
                                                if (groceryItem.checked) {
                                                    viewModel.toggleItemChecked(groceryItem)
                                                } else {
                                                    itemPendingDelete = groceryItem
                                                }
                                            },
                                            onLongPress = {
                                                groceryItem.id?.let { viewModel.startSelection(it) }
                                            },
                                            onTap = {
                                                if (uiState.isSelecting) {
                                                    groceryItem.id?.let { viewModel.toggleSelection(it) }
                                                } else {
                                                    openDetail(groceryItem)
                                                }
                                            },
                                            dragModifier = if (!uiState.isSelecting) {
                                                Modifier.draggableHandle()
                                            } else {
                                                Modifier
                                            },
                                        )
                                    }
                                }
                            }
                            if (uiState.laterItems.isNotEmpty()) {
                                item(key = "later-header") {
                                    LaterSectionHeader(
                                        count = uiState.laterItems.size,
                                        expanded = laterExpanded,
                                        onClick = { laterExpanded = !laterExpanded },
                                    )
                                }
                                if (laterExpanded) {
                                    items(
                                        uiState.laterItems,
                                        key = { "later|${it.id ?: it.hashCode()}" },
                                    ) { laterItem ->
                                        LaterItemRow(laterItem)
                                    }
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
            onAddItem = { label, quantity, unit, storeId, storeName, category ->
                viewModel.addItem(label, quantity, unit, storeId, storeName, category)
            },
            onDismiss = { viewModel.hideAddSheet() },
        )
    }

    // Item edit bottom sheet
    detailItem?.let { item ->
        ItemDetailSheet(
            item = item,
            products = uiState.products,
            stores = uiState.stores,
            onSave = { label, quantity, unit, storeId, storeName, category ->
                item.id?.let { itemId ->
                    viewModel.updateItem(itemId, label, quantity, unit, storeId, storeName, category)
                }
                detailItem = null
            },
            onDismiss = { detailItem = null },
        )
    }

    // Delete confirmation dialog (single item)
    itemPendingDelete?.let { item ->
        DeleteConfirmDialog(
            title = "Supprimer l'article",
            message = "Supprimer \u00ab ${item.label} \u00bb de la liste ?",
            onConfirm = {
                viewModel.deleteItem(item)
                itemPendingDelete = null
            },
            onDismiss = { itemPendingDelete = null },
        )
    }

    // Delete confirmation dialog (multi-select)
    if (showDeleteSelectedDialog) {
        DeleteConfirmDialog(
            title = "Supprimer ${uiState.selectedIds.size} articles ?",
            message = "Cette action est irr\u00e9versible.",
            onConfirm = {
                viewModel.deleteSelectedItems()
                showDeleteSelectedDialog = false
            },
            onDismiss = { showDeleteSelectedDialog = false },
        )
    }

    // Remaining items sheet (after finishing a store) \u2014 pick transfer target via StorePickerDialog
    var transferTargetItem by remember { mutableStateOf<com.maggie.app.data.api.EndErrandRemainingItem?>(null) }
    val finishingStoreName = uiState.pendingFinishStoreName
    if (finishingStoreName != null && uiState.pendingFinishItems.isNotEmpty()) {
        RemainingItemsSheet(
            storeName = finishingStoreName,
            items = uiState.pendingFinishItems,
            onTransferClick = { transferTargetItem = it },
            onKeepClick = { viewModel.keepPendingItem(it.id) },
            onDismiss = { viewModel.dismissPendingFinish() },
        )
    }

    transferTargetItem?.let { item ->
        val excludedStoreId = item.store?.id
        StorePickerDialog(
            stores = uiState.stores.filter { it.id != excludedStoreId },
            onSelectStore = { store ->
                store.id?.let { viewModel.transferPendingItem(item.id, it) }
                transferTargetItem = null
            },
            onSelectNewStore = { _ -> transferTargetItem = null },
            onSelectNone = { transferTargetItem = null },
            onDismiss = { transferTargetItem = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeableGroceryItem(
    item: GroceryItem,
    isSelecting: Boolean,
    isSelected: Boolean,
    isDragging: Boolean = false,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
    dragModifier: Modifier = Modifier,
) {
    val rowContent: @Composable () -> Unit = {
        GroceryItemRow(item = item, isSelected = isSelected, isDragging = isDragging, dragModifier = dragModifier)
    }

    val clickModifier = Modifier.combinedClickable(
        onLongClick = { if (!isSelecting) onLongPress() },
        onClick = { onTap() },
    )

    if (item.id == null || isSelecting) {
        Box(modifier = clickModifier) {
            rowContent()
        }
        return
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onSwipeRight()
                SwipeToDismissBoxValue.EndToStart -> onSwipeLeft()
                SwipeToDismissBoxValue.Settled -> {}
            }
            false // Always snap back
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { SwipeBackground(dismissState.targetValue, item.checked) },
        modifier = clickModifier,
    ) {
        rowContent()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(targetValue: SwipeToDismissBoxValue, isChecked: Boolean) {
    val (color, icon, alignment) = when (targetValue) {
        SwipeToDismissBoxValue.StartToEnd -> Triple(
            MaggieTokens.Signal.success,
            Icons.Default.Check,
            Alignment.CenterStart,
        )
        SwipeToDismissBoxValue.EndToStart -> if (isChecked) {
            Triple(MaggieTokens.Signal.warning, Icons.AutoMirrored.Filled.Undo, Alignment.CenterEnd)
        } else {
            Triple(MaggieTokens.Signal.danger, Icons.Default.Delete, Alignment.CenterEnd)
        }
        SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, Icons.Default.Check, Alignment.CenterStart)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        if (targetValue != SwipeToDismissBoxValue.Settled) {
            Icon(icon, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Supprimer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        },
    )
}

@Composable
private fun GroceryItemRow(
    item: GroceryItem,
    isSelected: Boolean = false,
    isDragging: Boolean = false,
    dragModifier: Modifier = Modifier,
) {
    val backgroundColor = when {
        isDragging -> MaterialTheme.colorScheme.surfaceContainerHighest
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Default.DragIndicator,
            contentDescription = "Réordonner",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = dragModifier.size(24.dp),
        )
        if (item.checked) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaggieTokens.Signal.success,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Spacer(modifier = Modifier.width(24.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                color = if (item.checked) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
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

@Composable
private fun SelectionBottomBar(
    selectedCount: Int,
    onCheck: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    BottomAppBar {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Annuler la s\u00e9lection")
        }
        Text(
            text = "$selectedCount s\u00e9lectionn\u00e9(s)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        IconButton(onClick = onCheck) {
            Icon(Icons.Default.Check, contentDescription = "Cocher les articles")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Supprimer les articles")
        }
    }
}

@Composable
private fun LaterSectionHeader(count: Int, expanded: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Plus tard ($count)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Replier" else "Déplier",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
    }
}

/** A deferred item: no checkbox, no swipe — it is not on today's list. */
@Composable
private fun LaterItemRow(item: GroceryItem) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp)) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        formatBuyAfter(item.buyAfter)?.let { date ->
            Text(
                text = "À acheter à partir du $date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
