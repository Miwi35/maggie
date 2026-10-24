package com.maggie.app.ui.screens.grocery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.CiqualFood
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.ProductStockState
import com.maggie.app.data.model.Store
import com.maggie.app.data.model.frenchName
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.cookbook.grocery.AislePickerDialog
import com.maggie.app.ui.uiTagRoot
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * The product sheet (MAG-605): every field of a catalogue product in one full-screen sheet, saved
 * together. « Magasin habituel » and « À défaut » each open a full-screen store picker, then their
 * rayon — the aisles of that store only, hidden until a store is chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(
    editor: ProductEditorState,
    stores: List<Store>,
    aislesByStore: Map<String, List<Aisle>>,
    onDraftChange: ((ProductDraft) -> ProductDraft) -> Unit,
    onSelectStore: (StoreSlot, String?) -> Unit,
    onSelectAisle: (StoreSlot, AisleChoice?) -> Unit,
    onPickCiqual: (CiqualFood?) -> Unit,
    onSearchCiqual: suspend (String) -> List<CiqualFood>,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = editor.draft
    var pickingStore by remember { mutableStateOf<StoreSlot?>(null) }
    var pickingAisle by remember { mutableStateOf<StoreSlot?>(null) }
    var searchingCiqual by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (!editor.isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.uiTagRoot().testTag(UiTags.PRODUCT_SHEET),
            topBar = {
                TopAppBar(
                    title = { Text("Produit") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                )
            },
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    editor.error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag(UiTags.PRODUCT_SHEET_ERROR),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        OutlinedButton(onClick = onDismiss, enabled = !editor.isSaving) { Text("Annuler") }
                        Button(
                            onClick = onSave,
                            enabled = !editor.isSaving,
                            modifier = Modifier.testTag(UiTags.PRODUCT_SHEET_SAVE),
                        ) { Text("Enregistrer") }
                    }
                }
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { v -> onDraftChange { it.copy(name = v) } },
                    label = { Text("Nom") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                ChoiceField(
                    label = "Catégorie",
                    value = draft.category.label(),
                    options = ProductCategory.entries.map { it to it.label() },
                    onSelect = { c -> onDraftChange { it.copy(category = c ?: it.category) } },
                )
                ChoiceField(
                    label = "Unité par défaut",
                    value = draft.defaultUnit?.frenchName.orEmpty(),
                    options = unitOptions(),
                    none = "Aucune",
                    onSelect = { u -> onDraftChange { it.copy(defaultUnit = u) } },
                )

                CiqualField(
                    label = editor.ciqualFoodName ?: draft.ciqualAlimCode?.let { "Aliment n° $it" },
                    onOpen = { searchingCiqual = true },
                    onClear = { onPickCiqual(null) },
                )

                SectionTitle("Conditionnement")
                ChoiceField(
                    label = "S'achète en",
                    value = draft.packagingUnit?.frenchName.orEmpty(),
                    options = unitOptions(),
                    none = "Aucun",
                    onSelect = { u -> onDraftChange { it.withPackagingUnit(u) } },
                )
                if (draft.packagingUnit != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.packagingSize,
                            onValueChange = { v -> onDraftChange { it.copy(packagingSize = v) } },
                            label = { Text("Contenu") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        ChoiceField(
                            label = "Unité du contenu",
                            value = draft.packagingSizeUnit?.frenchName.orEmpty(),
                            options = unitOptions(),
                            none = "Aucune",
                            onSelect = { u -> onDraftChange { it.copy(packagingSizeUnit = u) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                SectionTitle("Stock")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProductStockState.entries.forEach { state ->
                        FilterChip(
                            selected = draft.stockState == state,
                            onClick = { onDraftChange { it.copy(stockState = state) } },
                            label = { Text(state.label()) },
                        )
                    }
                }
                OutlinedTextField(
                    value = draft.restockQuantity,
                    onValueChange = { v -> onDraftChange { it.copy(restockQuantity = v) } },
                    label = { Text("Réassort (en conditionnements)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Réassort automatique", modifier = Modifier.weight(1f))
                    Switch(
                        checked = draft.autoRestock,
                        onCheckedChange = { v -> onDraftChange { it.copy(autoRestock = v) } },
                    )
                }

                SectionTitle("Où l'acheter")
                StoreCascade(
                    storeLabel = "Magasin habituel",
                    aisleLabel = "Rayon",
                    slot = StoreSlot.PREFERRED,
                    draft = draft,
                    stores = stores,
                    aislesByStore = aislesByStore,
                    storeTag = UiTags.PRODUCT_SHEET_STORE,
                    aisleTag = UiTags.PRODUCT_SHEET_AISLE,
                    onPickStore = { pickingStore = StoreSlot.PREFERRED },
                    onPickAisle = { pickingAisle = StoreSlot.PREFERRED },
                )
                StoreCascade(
                    storeLabel = "À défaut",
                    aisleLabel = "Rayon à défaut",
                    slot = StoreSlot.FALLBACK,
                    draft = draft,
                    stores = stores,
                    aislesByStore = aislesByStore,
                    storeTag = UiTags.PRODUCT_SHEET_FALLBACK_STORE,
                    aisleTag = UiTags.PRODUCT_SHEET_FALLBACK_AISLE,
                    onPickStore = { pickingStore = StoreSlot.FALLBACK },
                    onPickAisle = { pickingAisle = StoreSlot.FALLBACK },
                )

                SectionTitle("Saison")
                SeasonChips(
                    months = draft.seasonMonths,
                    onChange = { months -> onDraftChange { it.copy(seasonMonths = months) } },
                )
            }
        }
    }

    pickingStore?.let { slot ->
        val taken = draft.storeId(if (slot == StoreSlot.PREFERRED) StoreSlot.FALLBACK else StoreSlot.PREFERRED)
        ProductStorePicker(
            stores = stores.filter { it.id != taken },
            onSelect = {
                onSelectStore(slot, it)
                pickingStore = null
            },
            onDismiss = { pickingStore = null },
        )
    }

    pickingAisle?.let { slot ->
        val storeId = draft.storeId(slot)
        AislePickerDialog(
            aisles = aislesByStore[storeId].orEmpty(),
            onSelectAisle = {
                onSelectAisle(slot, AisleChoice(it.id))
                pickingAisle = null
            },
            onCreateAisle = {
                onSelectAisle(slot, AisleChoice(it, isNew = true))
                pickingAisle = null
            },
            onSelectNone = {
                onSelectAisle(slot, null)
                pickingAisle = null
            },
            onDismiss = { pickingAisle = null },
        )
    }

    if (searchingCiqual) {
        CiqualSearchScreen(
            onSearch = onSearchCiqual,
            onSelect = {
                onPickCiqual(it)
                searchingCiqual = false
            },
            onDismiss = { searchingCiqual = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** A store, then — once one is chosen — its rayon; no store, no rayon field. */
@Composable
private fun StoreCascade(
    storeLabel: String,
    aisleLabel: String,
    slot: StoreSlot,
    draft: ProductDraft,
    stores: List<Store>,
    aislesByStore: Map<String, List<Aisle>>,
    storeTag: String,
    aisleTag: String,
    onPickStore: () -> Unit,
    onPickAisle: () -> Unit,
) {
    val storeId = draft.storeId(slot)
    val choice = draft.aisle(slot)
    TapField(
        label = storeLabel,
        value = stores.firstOrNull { it.id == storeId }?.name.orEmpty(),
        onClick = onPickStore,
        modifier = Modifier.testTag(storeTag),
    )
    if (storeId != null) {
        val aisleName = choice?.let { c ->
            if (c.isNew) c.value else aislesByStore[storeId].orEmpty().firstOrNull { it.id == c.value }?.name
        }
        TapField(
            label = aisleLabel,
            value = aisleName.orEmpty(),
            onClick = onPickAisle,
            modifier = Modifier.testTag(aisleTag),
        )
    }
}

/** A read-only field that opens a full-screen picker when touched. */
@Composable
private fun TapField(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
        )
        // A read-only field swallows the touch: a clickable layer over it gets it back.
        Box(modifier = Modifier.matchParentSize().clickable(onClick = onClick))
    }
}

@Composable
private fun CiqualField(label: String?, onOpen: () -> Unit, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TapField(
            label = "Aliment Ciqual",
            value = label.orEmpty(),
            onClick = onOpen,
            modifier = Modifier.weight(1f),
        )
        if (label != null) {
            IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "Délier l'aliment") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceField(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
    none: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.uiTagRoot()) {
            if (none != null) {
                DropdownMenuItem(text = { Text(none) }, onClick = { onSelect(null); expanded = false })
            }
            options.forEach { (option, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonChips(months: List<Int>, onChange: (List<Int>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(UiTags.SEASON_MONTHS)) {
        SEASON_MONTH_LABELS.forEachIndexed { index, label ->
            val month = index + 1
            FilterChip(
                selected = month in months,
                onClick = { onChange(if (month in months) months - month else (months + month).sorted()) },
                label = { Text(label) },
            )
        }
    }
}

/** The shops to choose from, full screen like every picker of the app, with « Aucun » to take it off. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductStorePicker(stores: List<Store>, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, stores) { stores.filter { it.name.contains(query.trim(), ignoreCase = true) } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.uiTagRoot(),
            topBar = {
                TopAppBar(
                    title = { Text("Choisir un magasin") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Nom du magasin") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text(
                            text = "Aucun",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(null) }.padding(16.dp, 12.dp),
                        )
                        HorizontalDivider()
                    }
                    items(shown, key = { it.id }) { store ->
                        Text(
                            text = store.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(store.id) }.padding(16.dp, 12.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** The Ciqual foods, searched as the owner types, full screen (the mobile rule: no inline dropdown). */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
internal fun CiqualSearchScreen(
    onSearch: suspend (String) -> List<CiqualFood>,
    onSelect: (CiqualFood) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CiqualFood>>(emptyList()) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(Unit) {
        snapshotFlow { query }
            .debounce(300)
            .distinctUntilChanged()
            .filter { it.trim().length >= 2 }
            .collect { q -> results = try { onSearch(q.trim()) } catch (_: Exception) { emptyList() } }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.uiTagRoot(),
            topBar = {
                TopAppBar(
                    title = { Text("Aliment Ciqual") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Rechercher un aliment") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focusRequester),
                    singleLine = true,
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(results, key = { it.alimCode }) { food ->
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(food) }.padding(16.dp, 12.dp),
                        ) {
                            Text(food.alimNameFr, style = MaterialTheme.typography.bodyLarge)
                            food.alimGroupNameFr?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun unitOptions(): List<Pair<CookbookUnit, String>> = CookbookUnit.entries.map { it to it.frenchName }

internal fun ProductCategory.label(): String = when (this) {
    ProductCategory.PRODUCE -> "Fruits et légumes"
    ProductCategory.DAIRY -> "Produits laitiers"
    ProductCategory.MEAT -> "Viande"
    ProductCategory.FISH -> "Poisson"
    ProductCategory.GRAIN -> "Féculents"
    ProductCategory.SPICE -> "Épices"
    ProductCategory.CONDIMENT -> "Condiments"
    ProductCategory.FROZEN -> "Surgelés"
    ProductCategory.BEVERAGE -> "Boissons"
    ProductCategory.HOUSEHOLD -> "Maison"
    ProductCategory.HYGIENE -> "Hygiène"
    ProductCategory.CLEANING -> "Entretien"
    ProductCategory.OTHER -> "Autre"
}

internal fun ProductStockState.label(): String = when (this) {
    ProductStockState.IN_STOCK -> "En stock"
    ProductStockState.LOW -> "Stock faible"
    ProductStockState.OUT -> "Rupture"
}
