package com.maggie.app.ui.screens.grocery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.ProductCreateRequest
import com.maggie.app.data.model.CiqualFood
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.ProductStockState
import com.maggie.app.data.model.frenchName
import com.maggie.app.ui.components.GroceryTopBar
import com.maggie.app.ui.navigation.GrocerySection
import com.maggie.app.ui.screens.cookbook.grocery.RecurringGroceryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductListScreen(
    viewModel: ProductViewModel,
    recurringViewModel: RecurringGroceryViewModel,
    onSearchCiqual: suspend (String) -> List<CiqualFood> = { emptyList() },
) {
    val uiState by viewModel.uiState.collectAsState()
    val recurringState by recurringViewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            GroceryTopBar(GrocerySection.PRODUCTS)
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Nouveau produit")
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            ProductFilterBar(
                state = uiState,
                onStore = viewModel::selectFilterStore,
                onAisle = viewModel::selectFilterAisle,
                onSort = viewModel::selectSort,
            )
            uiState.error?.let { error ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::refresh) { Text("Réessayer") }
                }
            }
            val sections = uiState.sections
            when {
                uiState.isLoading && uiState.products.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.products.isEmpty() && uiState.error != null -> Unit
                uiState.products.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (uiState.query.isFiltered) "Aucun produit ne correspond." else "Aucun produit",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    val row: @Composable (Product) -> Unit = { product ->
                        ProductRow(product = product, onOpen = { viewModel.openProduct(product.id) })
                    }
                    // A chosen store groups by its aisles; otherwise by category, as the Produits mockup does.
                    val groups = sections ?: groupByCategory(uiState.products)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        groups.forEachIndexed { index, section ->
                            item(key = "section-$index") { SectionHeader(section) }
                            items(section.products, key = { it.id }) { row(it) }
                        }
                    }
                }
            }
        }
    }

    uiState.editor?.let { editor ->
        ProductDetailScreen(
            editor = editor,
            stores = uiState.stores,
            aislesByStore = uiState.aislesByStore,
            onDraftChange = viewModel::editDraft,
            onSelectStore = viewModel::selectStore,
            onSelectAisle = viewModel::selectAisle,
            onPickCiqual = viewModel::pickCiqual,
            onSearchCiqual = onSearchCiqual,
            recurring = recurringState.forProduct(editor.original.id)?.frequency,
            onRecurringChange = { recurringViewModel.setFrequency(editor.original.id, it) },
            onDelete = { viewModel.deleteProduct(editor.original.id) },
            onSave = viewModel::saveProduct,
            onDismiss = viewModel::closeProduct,
        )
    }

    if (showCreateDialog) {
        ProductCreateDialog(
            stores = uiState.stores,
            onConfirm = { request ->
                viewModel.createProduct(request)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

/** The title of a group — a category, or an aisle of the chosen store — and how many products it holds. */
@Composable
private fun SectionHeader(section: ProductSection) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = section.title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = section.products.size.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The state of a product's stock, as a coloured chip. */
@Composable
internal fun StockStateChip(state: ProductStockState) {
    val (label, container, content) = when (state) {
        ProductStockState.IN_STOCK -> Triple(
            "En stock",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        ProductStockState.LOW -> Triple(
            "Stock faible",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        ProductStockState.OUT -> Triple(
            "Rupture",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** One line of the list: the product, its category and its unit in French; everything else is in the detail. */
@Composable
internal fun ProductRow(product: Product, onOpen: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(text = product.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = listOfNotNull(product.category.label(), product.defaultUnit?.frenchName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductCreateDialog(
    stores: List<com.maggie.app.data.model.Store>,
    onConfirm: (ProductCreateRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(ProductCategory.OTHER) }
    var selectedUnit by remember { mutableStateOf<CookbookUnit?>(null) }
    var selectedStoreIri by remember { mutableStateOf<String?>(null) }
    var shelfLifeText by remember { mutableStateOf("") }
    var categoryExpanded by remember { mutableStateOf(false) }
    var unitExpanded by remember { mutableStateOf(false) }
    var storeExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouveau produit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Category dropdown
                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedCategory.label(),
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

                // Unit dropdown
                ExposedDropdownMenuBox(
                    expanded = unitExpanded,
                    onExpandedChange = { unitExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedUnit?.frenchName ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Unité par défaut") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = unitExpanded,
                        onDismissRequest = { unitExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Aucune") },
                            onClick = {
                                selectedUnit = null
                                unitExpanded = false
                            },
                        )
                        CookbookUnit.entries.forEach { unit ->
                            DropdownMenuItem(
                                text = { Text(unit.frenchName) },
                                onClick = {
                                    selectedUnit = unit
                                    unitExpanded = false
                                },
                            )
                        }
                    }
                }

                // Store dropdown
                ExposedDropdownMenuBox(
                    expanded = storeExpanded,
                    onExpandedChange = { storeExpanded = it },
                ) {
                    OutlinedTextField(
                        value = stores.find { "/api/stores/${it.id}" == selectedStoreIri }?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Magasin préféré") },
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
                                selectedStoreIri = null
                                storeExpanded = false
                            },
                        )
                        stores.forEach { store ->
                            DropdownMenuItem(
                                text = { Text(store.name) },
                                onClick = {
                                    selectedStoreIri = "/api/stores/${store.id}"
                                    storeExpanded = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = shelfLifeText,
                    onValueChange = { shelfLifeText = it },
                    label = { Text("Durée de conservation (jours)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(
                            ProductCreateRequest(
                                name = name.trim(),
                                category = selectedCategory.name.lowercase(),
                                defaultUnit = selectedUnit?.name?.lowercase(),
                                preferredStore = selectedStoreIri,
                                shelfLifeDays = shelfLifeText.toIntOrNull(),
                            ),
                        )
                    }
                },
                enabled = name.isNotBlank(),
            ) {
                Text("Créer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        },
    )
}
