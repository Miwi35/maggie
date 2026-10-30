package com.maggie.app.ui.screens.grocery

import com.maggie.app.ui.components.FabModule
import com.maggie.app.ui.components.ModuleFab
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.maggie.app.data.model.Aisle
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** A store's aisles, in the order of the way through it, moved by the handle (MAG-493). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreAislesScreen(
    storeName: String,
    viewModel: StoreAislesViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var aisleToRename by remember { mutableStateOf<Aisle?>(null) }
    var aisleToDelete by remember { mutableStateOf<Aisle?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        val message = uiState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(storeName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ModuleFab(FabModule.GROCERY, "Nouveau rayon", onClick = { showAddDialog = true })
        },
    ) { paddingValues ->
        when {
            uiState.isLoading && uiState.aisles.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.aisles.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    Text("Aucun rayon", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                val lazyListState = rememberLazyListState()
                val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
                    viewModel.previewMove(from.index, to.index)
                }
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    itemsIndexed(uiState.aisles, key = { _, aisle -> aisle.id }) { index, aisle ->
                        ReorderableItem(reorderState, key = aisle.id) { isDragging ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isDragging) {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                    )
                                    .clickable { aisleToRename = aisle }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // A finger-sized handle: the row itself is the rename tap.
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .draggableHandle(onDragStopped = { viewModel.endMove(aisle.id) }),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.DragIndicator,
                                        contentDescription = "Déplacer ${aisle.name}",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                                Text(
                                    text = "${index + 1}. ${aisle.name}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { aisleToDelete = aisle }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Supprimer ${aisle.name}",
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

    if (showAddDialog) {
        AisleNameDialog(
            title = "Nouveau rayon",
            initialName = "",
            confirmLabel = "Ajouter",
            onConfirm = { name ->
                viewModel.addAisle(name)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    aisleToRename?.let { aisle ->
        AisleNameDialog(
            title = "Renommer le rayon",
            initialName = aisle.name,
            confirmLabel = "Enregistrer",
            onConfirm = { name ->
                if (name.trim() != aisle.name) viewModel.renameAisle(aisle.id, name)
                aisleToRename = null
            },
            onDismiss = { aisleToRename = null },
        )
    }

    aisleToDelete?.let { aisle ->
        AlertDialog(
            onDismissRequest = { aisleToDelete = null },
            title = { Text("Supprimer « ${aisle.name} » ?") },
            text = { Text("Ses produits passent en « Sans rayon ».") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAisle(aisle.id)
                    aisleToDelete = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { aisleToDelete = null }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun AisleNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
