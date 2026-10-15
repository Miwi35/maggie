package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maggie.app.data.model.Aisle

/**
 * The aisle of an item's product in its store (MAG-493), full screen like the store
 * picker: the store's aisles in the order of the way through it, « Sans rayon », and a
 * new aisle by its name (the API creates it last in the store).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AislePickerDialog(
    aisles: List<Aisle>,
    onSelectAisle: (Aisle) -> Unit,
    onCreateAisle: (String) -> Unit,
    onSelectNone: () -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(searchQuery, aisles) { filterAisles(searchQuery, aisles) }
    val showCreate = remember(searchQuery, aisles) { shouldShowCreateAisle(searchQuery, aisles) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Choisir un rayon") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Nom du rayon") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                )

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        PickerRow(
                            text = "Sans rayon",
                            icon = { Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onSelectNone,
                        )
                        HorizontalDivider()
                    }
                    if (showCreate) {
                        item {
                            PickerRow(
                                text = "Créer « ${searchQuery.trim()} »",
                                icon = { Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                color = MaterialTheme.colorScheme.primary,
                                onClick = { onCreateAisle(searchQuery.trim()) },
                            )
                            HorizontalDivider()
                        }
                    }
                    items(filtered, key = { it.id }) { aisle ->
                        Text(
                            text = aisle.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectAisle(aisle) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    text: String,
    icon: @Composable () -> Unit,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(end = 12.dp)) { icon() }
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}
