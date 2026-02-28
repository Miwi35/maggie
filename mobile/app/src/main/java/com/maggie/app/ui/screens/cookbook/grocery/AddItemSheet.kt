package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.Store

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddItemSheet(
    products: List<Product>,
    stores: List<Store>,
    onAddItem: (label: String, quantity: Float?, unit: String?, storeId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var label by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("") }
    var selectedUnit by remember { mutableStateOf<CookbookUnit?>(null) }
    var selectedStoreId by remember { mutableStateOf<String?>(null) }
    var unitExpanded by remember { mutableStateOf(false) }
    var storeExpanded by remember { mutableStateOf(false) }

    val filteredProducts = remember(label, products) {
        if (label.length < 2) emptyList()
        else products.filter { it.name.contains(label, ignoreCase = true) }.take(5)
    }

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
                text = "Ajouter un article",
                style = MaterialTheme.typography.titleMedium,
            )

            // Product search field
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Nom du produit") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // Product suggestions
            if (filteredProducts.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 160.dp),
                ) {
                    items(filteredProducts) { product ->
                        Text(
                            text = product.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    label = product.name
                                    product.defaultUnit?.let { selectedUnit = it }
                                    // Auto-select preferred store
                                    product.preferredStore?.let { iri ->
                                        val id = iri.substringAfterLast("/")
                                        if (stores.any { it.id == id }) {
                                            selectedStoreId = id
                                        }
                                    }
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        HorizontalDivider()
                    }
                }
            }

            // Quantity + Unit row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text("Quantité") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )

                ExposedDropdownMenuBox(
                    expanded = unitExpanded,
                    onExpandedChange = { unitExpanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = selectedUnit?.name?.lowercase() ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Unité") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = unitExpanded,
                        onDismissRequest = { unitExpanded = false },
                    ) {
                        CookbookUnit.entries.forEach { unit ->
                            DropdownMenuItem(
                                text = { Text(unit.name.lowercase()) },
                                onClick = {
                                    selectedUnit = unit
                                    unitExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // Store picker
            ExposedDropdownMenuBox(
                expanded = storeExpanded,
                onExpandedChange = { storeExpanded = it },
            ) {
                OutlinedTextField(
                    value = stores.find { it.id == selectedStoreId }?.name ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Magasin") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = storeExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = storeExpanded,
                    onDismissRequest = { storeExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Aucun") },
                        onClick = {
                            selectedStoreId = null
                            storeExpanded = false
                        },
                    )
                    stores.forEach { store ->
                        DropdownMenuItem(
                            text = { Text(store.name) },
                            onClick = {
                                selectedStoreId = store.id
                                storeExpanded = false
                            },
                        )
                    }
                }
            }

            // Add button
            Button(
                onClick = {
                    if (label.isNotBlank()) {
                        val qty = quantityText.toFloatOrNull()
                        val unitStr = selectedUnit?.name?.lowercase()
                        onAddItem(label.trim(), qty, unitStr, selectedStoreId)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = label.isNotBlank(),
            ) {
                Text("Ajouter")
            }
        }
    }
}
