package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryItemSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailSheet(
    item: GroceryItem,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val readOnlyColors = OutlinedTextFieldDefaults.colors(
        disabledTextColor = MaterialTheme.colorScheme.onSurface,
        disabledBorderColor = MaterialTheme.colorScheme.outline,
        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Détails de l'article",
                style = MaterialTheme.typography.titleMedium,
            )

            // Product name
            OutlinedTextField(
                value = item.label,
                onValueChange = {},
                readOnly = true,
                enabled = false,
                label = { Text("Nom du produit") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = readOnlyColors,
            )

            // Quantity + Unit row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = item.quantity?.toString() ?: "",
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text("Quantité") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = readOnlyColors,
                )

                OutlinedTextField(
                    value = item.unit?.name?.lowercase() ?: "",
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text("Unité") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = readOnlyColors,
                )
            }

            // Category
            OutlinedTextField(
                value = item.product?.category?.name?.lowercase() ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = false,
                label = { Text("Catégorie") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = readOnlyColors,
            )

            // Store
            OutlinedTextField(
                value = item.store?.name ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = false,
                label = { Text("Magasin") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = readOnlyColors,
            )

            // Source
            OutlinedTextField(
                value = when (item.source) {
                    GroceryItemSource.RECIPE -> "Recette"
                    GroceryItemSource.RECURRING -> "Récurrent"
                    GroceryItemSource.MANUAL -> "Manuel"
                },
                onValueChange = {},
                readOnly = true,
                enabled = false,
                label = { Text("Source") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = readOnlyColors,
            )
        }
    }
}
