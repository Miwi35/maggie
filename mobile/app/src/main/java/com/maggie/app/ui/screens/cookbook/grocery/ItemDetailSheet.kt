package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryItemSource
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.RecurringFrequency
import com.maggie.app.data.model.Store
import com.maggie.app.ui.uiTagRoot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailSheet(
    item: GroceryItem,
    products: List<Product>,
    stores: List<Store>,
    onSave: (label: String, quantity: QuantityPayload?, storeId: String?, storeName: String?, category: String?) -> Unit,
    onDismiss: () -> Unit,
    recurring: RecurringFrequency? = null,
    onRecurringChange: ((RecurringFrequency?) -> Unit)? = null,
    aislesByStore: Map<String, List<Aisle>> = emptyMap(),
    onLoadAisles: (storeId: String) -> Unit = {},
    onSaveAisle: ((storeId: String, aisle: String?) -> Unit)? = null,
) {
    ItemDetailContent(
        item = item,
        products = products,
        stores = stores,
        onSave = onSave,
        // Taller since the « Récurrent » setting: a short window scrolls to « Enregistrer ».
        modifier = Modifier.verticalScroll(rememberScrollState()),
        recurring = recurring,
        onRecurringChange = onRecurringChange,
        aislesByStore = aislesByStore,
        onLoadAisles = onLoadAisles,
        onSaveAisle = onSaveAisle,
    ) { body ->
        ModalBottomSheet(
            modifier = Modifier.uiTagRoot(),
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) { body() }
    }
}

/**
 * The form of a grocery item. [frame] wraps it: the sheet puts it in a modal, the default
 * draws it in place, which is what the detail pane beside the list wants (MAG-263).
 * The fields remember what was typed, so a pane showing another item needs `key(item.id)`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailContent(
    item: GroceryItem,
    products: List<Product>,
    stores: List<Store>,
    onSave: (label: String, quantity: QuantityPayload?, storeId: String?, storeName: String?, category: String?) -> Unit,
    modifier: Modifier = Modifier,
    // The « Récurrent » setting needs a product, since a recurring item is a product's (MAG-369).
    recurring: RecurringFrequency? = null,
    onRecurringChange: ((RecurringFrequency?) -> Unit)? = null,
    // The « Rayon » choice needs a product and a known store: the aisle is the product's in that store (MAG-493).
    aislesByStore: Map<String, List<Aisle>> = emptyMap(),
    onLoadAisles: (storeId: String) -> Unit = {},
    // Called on « Enregistrer » when the aisle was changed: an aisle id, a new aisle's name, or null for « Sans rayon ».
    onSaveAisle: ((storeId: String, aisle: String?) -> Unit)? = null,
    frame: @Composable (body: @Composable () -> Unit) -> Unit = { body -> body() },
) {
    var label by remember { mutableStateOf(item.label) }
    val initialDraft = remember { quantityDraftForLine(item.quantity, item.product) }
    var draft by remember { mutableStateOf(initialDraft) }
    var selectedCategory by remember { mutableStateOf(item.product?.category) }
    var selectedStoreId by remember { mutableStateOf(item.store?.id) }
    var storeText by remember { mutableStateOf(item.store?.name ?: "") }
    var categoryExpanded by remember { mutableStateOf(false) }
    var showProductPicker by remember { mutableStateOf(false) }
    var showStorePicker by remember { mutableStateOf(false) }
    // What « Enregistrer » sends for the aisle: an id or a new name, null for « Sans rayon ».
    var aisleChoice by remember { mutableStateOf(item.aisle?.id) }
    var aisleText by remember { mutableStateOf(item.aisle?.name ?: "") }
    var aisleChanged by remember { mutableStateOf(false) }
    var showAislePicker by remember { mutableStateOf(false) }
    val aisleStoreId = selectedStoreId?.takeIf { item.product != null && onSaveAisle != null && label == item.label }
    LaunchedEffect(aisleStoreId) { aisleStoreId?.let(onLoadAisles) }
    // Another store is another aisle: the one shown was the item's in its former store.
    fun resetAisle() {
        aisleChoice = null
        aisleText = ""
        aisleChanged = false
    }

    val readOnlyColors = OutlinedTextFieldDefaults.colors(
        disabledTextColor = MaterialTheme.colorScheme.onSurface,
        disabledBorderColor = MaterialTheme.colorScheme.outline,
        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    frame {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Modifier l'article",
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
                    colors = readOnlyColors,
                )
            }

            // Only a packaged product has a quantity to set: packagings, or a quantity turned into packagings.
            if (item.isPackaged) {
                QuantityEntry(product = item.product, draft = draft, onChange = { draft = it })
            }

            // Category picker
            ExposedDropdownMenuBox(
                expanded = categoryExpanded,
                onExpandedChange = { categoryExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedCategory?.name?.lowercase() ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Catégorie") },
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
                    colors = readOnlyColors,
                )
            }

            if (aisleStoreId != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAislePicker = true },
                ) {
                    OutlinedTextField(
                        value = aisleText,
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        label = { Text("Rayon") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Choisir un rayon")
                        },
                        colors = readOnlyColors,
                    )
                }
            }

            if (item.product != null && onRecurringChange != null) {
                RecurringSetting(recurring = recurring, onChange = onRecurringChange)
            }

            // Source (read-only)
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
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )

            // Save button
            Button(
                onClick = {
                    if (label.isNotBlank() && (!item.isPackaged || draft.isValid())) {
                        // Only a change is sent: the same number of packagings must not rewrite the line's need.
                        val qty = if (item.isPackaged && draft != initialDraft) draft.payload() else null
                        val sName = if (selectedStoreId == null && storeText.isNotBlank()) storeText.trim() else null
                        val catStr = selectedCategory?.name?.lowercase()
                        onSave(label.trim(), qty, selectedStoreId, sName, catStr)
                        if (aisleChanged && aisleStoreId != null) onSaveAisle?.invoke(aisleStoreId, aisleChoice)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = label.isNotBlank() && (!item.isPackaged || draft.isValid()),
            ) {
                Text("Enregistrer")
            }
        }
    }

    if (showAislePicker && aisleStoreId != null) {
        AislePickerDialog(
            aisles = aislesByStore[aisleStoreId].orEmpty(),
            onSelectAisle = { aisle ->
                aisleChoice = aisle.id
                aisleText = aisle.name
                aisleChanged = true
                showAislePicker = false
            },
            onCreateAisle = { name ->
                aisleChoice = name
                aisleText = name
                aisleChanged = true
                showAislePicker = false
            },
            onSelectNone = {
                aisleChoice = null
                aisleText = "Sans rayon"
                aisleChanged = true
                showAislePicker = false
            },
            onDismiss = { showAislePicker = false },
        )
    }

    // Product picker dialog
    if (showProductPicker) {
        ProductPickerDialog(
            products = products,
            initialQuery = label,
            onSelectProduct = { product ->
                label = product.name
                selectedCategory = product.category
                resolvePreferredStore(product, stores)?.let { store ->
                    selectedStoreId = store.id
                    storeText = store.name
                }
                showProductPicker = false
            },
            onSelectCustomLabel = { customLabel ->
                label = customLabel
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
                if (store.id != selectedStoreId) resetAisle()
                selectedStoreId = store.id
                storeText = store.name
                showStorePicker = false
            },
            onSelectNewStore = { name ->
                resetAisle()
                selectedStoreId = null
                storeText = name
                showStorePicker = false
            },
            onSelectNone = {
                resetAisle()
                selectedStoreId = null
                storeText = ""
                showStorePicker = false
            },
            onDismiss = { showStorePicker = false },
        )
    }
}
