package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.CiqualFood
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.RecipeNeedResponse
import com.maggie.app.data.model.Store
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.grocery.label
import com.maggie.app.ui.theme.maggieSurface
import com.maggie.app.ui.uiTagRoot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddItemSheet(
    products: List<Product>,
    stores: List<Store>,
    onAddItem: (label: String, quantity: QuantityPayload, storeId: String?, storeName: String?, category: String?) -> Unit,
    onDismiss: () -> Unit,
    // To word the product's aisle under its name; an aisle whose store is not here is left out.
    aislesByStore: Map<String, List<Aisle>> = emptyMap(),
    // « Pour les recettes de la semaine » (MAG-573): read when a product is chosen; null while loading or when no meal needs it.
    recipeNeed: RecipeNeedResponse? = null,
    // A product (or a free label, null) was chosen: the screen reads the week's recipe need for it.
    onProductChosen: (Product?) -> Unit = {},
    // Why the API refused the line; the sheet stays open on what was typed.
    error: String? = null,
    // Ciqual foods offered when no product matches; choosing one makes a product linked to it (null: not offered).
    onSearchCiqual: (suspend (String) -> List<CiqualFood>)? = null,
    onCreateProductFromCiqual: (suspend (CiqualFood) -> Product?)? = null,
) {
    val scope = rememberCoroutineScope()
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

    // The need of the product chosen — never one read for the product before it.
    val need = recipeNeed?.takeIf { selectedProduct != null && it.productId == selectedProduct?.id }
    val headline = need?.recipeNeed
    // The need arrives after the product was chosen: it replaces the one-packaging default, nothing the owner or the restock set.
    LaunchedEffect(headline, selectedProduct) {
        draft = draft.withRecipeNeed(selectedProduct, headline)
    }

    val surface = maggieSurface()
    val canAdd = label.isNotBlank() && draft.isValid()
    val chosen = selectedProduct
    // A product with its store says it in the header; a free label or a store-less product is asked for them.
    val asksStoreAndCategory = chosen == null || resolvePreferredStore(chosen, stores) == null

    ModalBottomSheet(
        modifier = Modifier.uiTagRoot(),
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AddSheetHeader(
                label = label,
                product = chosen,
                line = chosen?.let { productLine(it, stores, aislesByStore) },
                enabled = canAdd,
                onPick = { showProductPicker = true },
                onClose = onDismiss,
                onAdd = {
                    if (canAdd) {
                        val sName = if (selectedStoreId == null && storeText.isNotBlank()) storeText.trim() else null
                        val catStr = selectedCategory?.name?.lowercase()
                        onAddItem(label.trim(), draft.payload(), selectedStoreId, sName, catStr)
                    }
                },
            )

            if (asksStoreAndCategory) {
                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = it },
                ) {
                    TextField(
                        value = selectedCategory?.label() ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Cat\u00e9gorie") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        shape = FIELD_SHAPE,
                        colors = filledFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false },
                        modifier = Modifier.uiTagRoot(),
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
                                text = { Text(cat.label()) },
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
                    TextField(
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
                        shape = FIELD_SHAPE,
                        colors = filledFieldColors(),
                    )
                }
            }

            // What the week's meals need of the product, with the meals behind the figure
            if (chosen?.packagingUnit != null) RecipeNeedBox(need)

            // Quantity, or packagings for a packaged product; prefilled with its restock, else the week's need
            QuantityEntry(product = chosen, draft = draft, onChange = { draft = it }, need = headline)

            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(UiTags.GROCERY_ADD_ERROR),
                )
            }
        }
    }

    fun chooseProduct(product: Product) {
        label = product.name
        selectedProduct = product
        draft = initialQuantityDraft(product)
        onProductChosen(product)
        selectedCategory = product.category
        resolvePreferredStore(product, stores)?.let { store ->
            selectedStoreId = store.id
            storeText = store.name
        }
        showProductPicker = false
    }

    // Product picker dialog
    if (showProductPicker) {
        ProductPickerDialog(
            products = products,
            initialQuery = label,
            onSelectProduct = { product -> chooseProduct(product) },
            onSelectCustomLabel = { customLabel ->
                label = customLabel
                selectedProduct = null
                draft = QuantityDraft()
                onProductChosen(null)
                showProductPicker = false
            },
            onDismiss = { showProductPicker = false },
            onSearchCiqual = onSearchCiqual,
            onSelectCiqualFood = onCreateProductFromCiqual?.let { create ->
                { food ->
                    scope.launch {
                        val created = create(food)
                        if (created != null) {
                            chooseProduct(created)
                        } else {
                            label = food.alimNameFr
                            selectedProduct = null
                            draft = QuantityDraft()
                            onProductChosen(null)
                            showProductPicker = false
                        }
                    }
                }
            },
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

private val FIELD_SHAPE = RoundedCornerShape(14.dp)

/** The mock-up's full field: raised fill, no outline, whatever its state. */
@Composable
internal fun filledFieldColors(): TextFieldColors {
    val surface = maggieSurface()
    val ink = MaterialTheme.colorScheme.onSurface
    return TextFieldDefaults.colors(
        focusedContainerColor = surface.raised,
        unfocusedContainerColor = surface.raised,
        disabledContainerColor = surface.raised,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        disabledTextColor = ink,
        disabledLabelColor = surface.textMuted,
        disabledTrailingIconColor = surface.textMuted,
    )
}

/** ✕, « Ajouter à la liste » over the product and where it is sold, and the « Ajouter » pill. */
@Composable
private fun AddSheetHeader(
    label: String,
    product: Product?,
    line: String?,
    enabled: Boolean,
    onPick: () -> Unit,
    onClose: () -> Unit,
    onAdd: () -> Unit,
) {
    val surface = maggieSurface()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Fermer", tint = surface.textMuted)
        }
        Column(modifier = Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Ajouter \u00e0 la liste", fontSize = 13.sp, color = surface.textMuted)
            Text(
                text = label.ifBlank { "Choisir un produit" },
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = if (label.isBlank()) surface.textMuted else surface.text,
                modifier = Modifier.clickable(role = Role.Button, onClick = onPick).testTag(UiTags.GROCERY_ADD_PRODUCT),
            )
            if (product != null && line != null) Text(line, fontSize = 13.sp, color = surface.textMuted)
        }
        Box(
            modifier = Modifier
                .height(40.dp)
                .background(surface.text.copy(alpha = if (enabled) 1f else 0.3f), RoundedCornerShape(50))
                .clickable(enabled = enabled, role = Role.Button, onClick = onAdd)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Ajouter", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = surface.background)
        }
    }
}
