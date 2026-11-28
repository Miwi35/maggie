package com.maggie.app.ui.screens.grocery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.CiqualFood
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.ProductStockState
import com.maggie.app.data.model.RecurringFrequency
import com.maggie.app.data.model.Store
import com.maggie.app.data.model.frenchName
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.cookbook.grocery.AislePickerDialog
import com.maggie.app.ui.screens.cookbook.grocery.RecurringSetting
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.maggieSurface
import com.maggie.app.ui.uiTagRoot
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * The product sheet (MAG-605), drawn like the mock-up (MAG-1053): a head with ✕, the breadcrumb, the
 * name, « Enregistrer » and ⋮, then every field of a catalogue product, saved together, the recurrence
 * (applied on tap) and the deletion (asked first, from ⋮). « Magasin habituel » and « À défaut » each
 * open a full-screen store picker, then their rayon — the aisles of that store only, hidden until a
 * store is chosen.
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
    recurring: RecurringFrequency?,
    onRecurringChange: (RecurringFrequency?) -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = editor.draft
    val surface = maggieSurface()
    var confirmingDelete by remember { mutableStateOf(false) }
    var pickingStore by remember { mutableStateOf<StoreSlot?>(null) }
    var pickingAisle by remember { mutableStateOf<StoreSlot?>(null) }
    var searchingCiqual by remember { mutableStateOf(false) }
    var editingPackaging by remember { mutableStateOf(false) }
    var editingSeason by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (!editor.isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.uiTagRoot().testTag(UiTags.PRODUCT_SHEET),
            containerColor = surface.background,
            topBar = {
                ProductSheetHeader(
                    name = draft.name,
                    isSaving = editor.isSaving,
                    onDismiss = onDismiss,
                    onSave = onSave,
                    onDelete = { confirmingDelete = true },
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                editor.error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(UiTags.PRODUCT_SHEET_ERROR),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Nom", fontSize = 12.sp, color = surface.textMuted)
                    NameField(value = draft.name, onValueChange = { v -> onDraftChange { it.copy(name = v) } })
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceTile(
                        label = "Catégorie",
                        value = draft.category.label(),
                        options = ProductCategory.entries.map { it to it.label() },
                        onSelect = { c -> onDraftChange { it.copy(category = c ?: it.category) } },
                        modifier = Modifier.weight(1f),
                    )
                    ChoiceTile(
                        label = "Unité par défaut",
                        value = draft.defaultUnit?.frenchName ?: "Aucune",
                        options = unitOptions(),
                        none = "Aucune",
                        onSelect = { u -> onDraftChange { it.copy(defaultUnit = u) } },
                        modifier = Modifier.weight(1f),
                    )
                }

                CiqualField(
                    label = editor.ciqualFoodName ?: draft.ciqualAlimCode?.let { "Aliment n° $it" },
                    onOpen = { searchingCiqual = true },
                    onClear = { onPickCiqual(null) },
                )

                SectionLabel("Conditionnement")
                FilledRow(
                    key = "Se vend en",
                    value = packagingValue(draft),
                    onClick = { editingPackaging = true },
                    modifier = Modifier.testTag(UiTags.PRODUCT_SHEET_PACKAGING),
                )

                SectionLabel("Ce qu'il m'en reste")
                StockSegments(
                    selected = draft.stockState,
                    onSelect = { s -> onDraftChange { it.copy(stockState = s) } },
                )
                RestockStepper(
                    draft = draft,
                    onChange = { n -> onDraftChange { it.copy(restockQuantity = n.toString()) } },
                )
                CheckRow(
                    label = "Remettre sur la liste tout seul",
                    checked = draft.autoRestock,
                    onCheckedChange = { v -> onDraftChange { it.copy(autoRestock = v) } },
                )

                SectionLabel("Où l'acheter")
                StoreCascade(
                    storeLabel = "Magasin habituel",
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
                    slot = StoreSlot.FALLBACK,
                    draft = draft,
                    stores = stores,
                    aislesByStore = aislesByStore,
                    storeTag = UiTags.PRODUCT_SHEET_FALLBACK_STORE,
                    aisleTag = UiTags.PRODUCT_SHEET_FALLBACK_AISLE,
                    onPickStore = { pickingStore = StoreSlot.FALLBACK },
                    onPickAisle = { pickingAisle = StoreSlot.FALLBACK },
                )

                SectionLabel("Saison")
                FilledRow(
                    key = "Mois de saison",
                    value = seasonValue(draft.seasonMonths),
                    onClick = { editingSeason = true },
                    modifier = Modifier.testTag(UiTags.PRODUCT_SHEET_SEASON),
                )

                SectionLabel("Récurrence")
                RecurringSetting(recurring = recurring, onChange = onRecurringChange)

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (editingPackaging) {
        ModalBottomSheet(
            modifier = Modifier.uiTagRoot(),
            onDismissRequest = { editingPackaging = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Se vend en", style = MaterialTheme.typography.titleMedium)
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
            }
        }
    }

    if (editingSeason) {
        ModalBottomSheet(
            modifier = Modifier.uiTagRoot(),
            onDismissRequest = { editingSeason = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Mois de saison", style = MaterialTheme.typography.titleMedium)
                SeasonChips(
                    months = draft.seasonMonths,
                    onChange = { months -> onDraftChange { it.copy(seasonMonths = months) } },
                )
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            modifier = Modifier.uiTagRoot(),
            title = { Text("Supprimer ${editor.original.name} ?") },
            text = { Text("Le produit disparaît du catalogue.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                    modifier = Modifier.testTag(UiTags.PRODUCT_SHEET_DELETE_CONFIRM),
                ) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Annuler") } },
        )
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

/** ✕, « Courses › Produits » over the name, « Enregistrer » as a pill, ⋮ with the deletion. */
@Composable
private fun ProductSheetHeader(
    name: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val surface = maggieSurface()
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Fermer")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("Courses › Produits", fontSize = 12.sp, color = surface.textMuted, maxLines = 1)
            Text(
                text = name.ifBlank { "Nouveau produit" },
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(surface.text.copy(alpha = if (isSaving) 0.5f else 1f))
                .clickable(enabled = !isSaving, role = Role.Button, onClick = onSave)
                .padding(horizontal = 18.dp)
                .testTag(UiTags.PRODUCT_SHEET_SAVE),
            contentAlignment = Alignment.Center,
        ) {
            Text("Enregistrer", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = surface.background)
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(48.dp).testTag(UiTags.PRODUCT_SHEET_MORE),
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "Plus d'options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.uiTagRoot()) {
                DropdownMenuItem(
                    text = { Text("Supprimer le produit", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                    modifier = Modifier.testTag(UiTags.PRODUCT_SHEET_DELETE),
                )
            }
        }
    }
}

/** 12 sp, spaced, capitals, muted — the mock-up's section label. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        letterSpacing = 0.14.em,
        color = maggieSurface().textMuted,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit) {
    val surface = maggieSurface()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = surface.text, fontSize = 16.sp),
        cursorBrush = SolidColor(surface.text),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(surface.raised, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
}

/** The mock-up's tile: its key in 12 sp muted over its value in 15 sp, an optional chevron, an optional indent. */
@Composable
private fun FilledRow(
    key: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    chevron: Boolean = true,
    indent: Boolean = false,
) {
    val surface = maggieSurface()
    Row(
        modifier = modifier
            .padding(start = if (indent) 20.dp else 0.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(surface.raised, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(key, fontSize = 12.sp, color = surface.textMuted)
            Text(value, fontSize = 15.sp)
        }
        if (chevron) Icon(Icons.Default.ChevronRight, contentDescription = null, tint = surface.textMuted)
    }
}

/** A tile whose touch opens the menu of choices. */
@Composable
private fun <T> ChoiceTile(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
    none: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        FilledRow(key = label, value = value, onClick = { expanded = true }, chevron = false)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.uiTagRoot()) {
            if (none != null) {
                DropdownMenuItem(text = { Text(none) }, onClick = { onSelect(null); expanded = false })
            }
            options.forEach { (option, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

/** « En stock · Bas · Épuisé »: the chosen segment is Courses' blue. */
@Composable
private fun StockSegments(selected: ProductStockState, onSelect: (ProductStockState) -> Unit) {
    val surface = maggieSurface()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(surface.raised, RoundedCornerShape(999.dp))
            .padding(4.dp),
    ) {
        ProductStockState.entries.forEach { state ->
            val isSelected = state == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isSelected) MaggieTokens.Module.courses.second else surface.raised)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(state) })
                    .testTag(UiTags.productSheetStock(state)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.label(),
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = if (isSelected) surface.background else surface.text,
                )
            }
        }
    }
}

/** « Réassort », « 1 filet », and one more or one less, never below 0. */
@Composable
private fun RestockStepper(draft: ProductDraft, onChange: (Int) -> Unit) {
    val surface = maggieSurface()
    val count = draft.restockCount()
    val unit = draft.packagingUnit?.frenchName ?: "conditionnement"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(surface.raised, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Réassort", fontSize = 12.sp, color = surface.textMuted)
            Text(restockValue(draft), fontSize = 15.sp)
        }
        StepButton(
            icon = Icons.Default.Remove,
            description = "Un $unit de moins",
            tag = UiTags.PRODUCT_SHEET_RESTOCK_MINUS,
            onClick = { onChange((count - 1).coerceAtLeast(0)) },
        )
        StepButton(
            icon = Icons.Default.Add,
            description = "Un $unit de plus",
            tag = UiTags.PRODUCT_SHEET_RESTOCK_PLUS,
            onClick = { onChange(count + 1) },
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(maggieSurface().raised)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null)
    }
}

/** A line with its label and a Courses-blue check box on the right. */
@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val surface = maggieSurface()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(surface.raised, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .then(
                    if (checked) {
                        Modifier.background(MaggieTokens.Module.courses.second)
                    } else {
                        Modifier.border(BorderStroke(2.dp, surface.textMuted), RoundedCornerShape(6.dp))
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Default.Check, contentDescription = null, tint = surface.background, modifier = Modifier.size(16.dp))
        }
    }
}

/** A store, then — once one is chosen — its rayon, « Rayon chez <magasin> », indented; no store, no rayon row. */
@Composable
private fun StoreCascade(
    storeLabel: String,
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
    val storeName = stores.firstOrNull { it.id == storeId }?.name
    FilledRow(
        key = storeLabel,
        value = storeName ?: "Aucun",
        onClick = onPickStore,
        modifier = Modifier.testTag(storeTag),
    )
    if (storeId != null) {
        val aisleName = choice?.let { c ->
            if (c.isNew) c.value else aislesByStore[storeId].orEmpty().firstOrNull { it.id == c.value }?.name
        }
        FilledRow(
            key = storeName?.let { "Rayon chez $it" } ?: "Rayon",
            value = aisleName ?: "Aucun",
            onClick = onPickAisle,
            indent = true,
            modifier = Modifier.testTag(aisleTag),
        )
    }
}

@Composable
private fun CiqualField(label: String?, onOpen: () -> Unit, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledRow(
            key = "Aliment Ciqual",
            value = label ?: "Aucun",
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


internal fun ProductStockState.label(): String = when (this) {
    ProductStockState.IN_STOCK -> "En stock"
    ProductStockState.LOW -> "Bas"
    ProductStockState.OUT -> "Épuisé"
}
