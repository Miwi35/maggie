package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Store
import com.maggie.app.ui.uiTagRoot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddItemSheet(
    products: List<Product>,
    stores: List<Store>,
    onAddItem: (label: String, quantity: QuantityPayload, storeId: String?, storeName: String?, category: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var label by remember { mutableStateOf("") }
    var selectedProduct by remember { mutableStateOf<Product?>(null) }
    var draft by remember { mutableStateOf(QuantityDraft()) }
    var selectedCategory by remember { mutableStateOf<ProductCategory?>(null) }
    var selectedStoreId by remember { mutableStateOf<String?>(null) }
    var storeText by remember { mutableStateOf("") }
    var categoryExpanded by remember { mutableStateOf(false) }
    var showProductPicker by remember { mutableStateOf(false) }
    var showStorePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        modifier = Modifier.uiTagRoot(),
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

            // Product search field (opens full-screen picker)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showProductPicker = true },
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text("Nom du produit") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "Rechercher un produit")
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }

            // Quantity, or packagings for a packaged product; prefilled with its automatic restock
            QuantityEntry(product = selectedProduct, draft = draft, onChange = { draft = it })

            // Category picker
            ExposedDropdownMenuBox(
                expanded = categoryExpanded,
                onExpandedChange = { categoryExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedCategory?.name?.lowercase() ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Cat\u00e9gorie") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = categoryExpanded,
                    onDismissRequest = { categoryExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Aucune") },
                        onClick = {
                            selectedCategory = null
                            categoryExpanded = false
                        },
                    )
                    ProductCategory.entries.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat.name.lowercase()) },
                            onClick = {
                                selectedCategory = cat
                                categoryExpanded = false
                            },
                        )
                    }
                }
            }

            // Store field (opens full-screen picker)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showStorePicker = true },
            ) {
                OutlinedTextField(
                    value = storeText,
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text("Magasin") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "Choisir un magasin")
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }

            // Add button
            Button(
                onClick = {
                    if (label.isNotBlank() && draft.isValid()) {
                        val sName = if (selectedStoreId == null && storeText.isNotBlank()) storeText.trim() else null
                        val catStr = selectedCategory?.name?.lowercase()
                        onAddItem(label.trim(), draft.payload(), selectedStoreId, sName, catStr)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = label.isNotBlank() && draft.isValid(),
            ) {
                Text("Ajouter")
            }
        }
    }

    // Product picker dialog
    if (showProductPicker) {
        ProductPickerDialog(
            products = products,
            initialQuery = label,
            onSelectProduct = { product ->
                label = product.name
                selectedProduct = product
                draft = initialQuantityDraft(product)
                selectedCategory = product.category
                resolvePreferredStore(product, stores)?.let { store ->
                    selectedStoreId = store.id
                    storeText = store.name
                }
                showProductPicker = false
            },
            onSelectCustomLabel = { customLabel ->
                label = customLabel
                selectedProduct = null
                draft = QuantityDraft()
                showProductPicker = false
            },
            onDismiss = { showProductPicker = false },
        )
    }

    // Store picker dialog
    if (showStorePicker) {
        StorePickerDialog(
            stores = stores,
            initialQuery = storeText,
            onSelectStore = { store ->
                selectedStoreId = store.id
                storeText = store.name
                showStorePicker = false
            },
            onSelectNewStore = { name ->
                selectedStoreId = null
                storeText = name
                showStorePicker = false
            },
            onSelectNone = {
                selectedStoreId = null
                storeText = ""
                showStorePicker = false
            },
            onDismiss = { showStorePicker = false },
        )
    }
}
