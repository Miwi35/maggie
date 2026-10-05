package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.RecipeCreateRequest
import com.maggie.app.data.api.RecipeIngredientRequest
import com.maggie.app.data.model.CiqualFood
import com.maggie.app.data.model.CookbookUnit
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

data class IngredientRow(
    val ciqualAlimCode: String = "",
    val ciqualFoodName: String = "",
    val ingredientId: String = "",
    val quantity: String = "",
    val unit: CookbookUnit = CookbookUnit.G,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeCreateScreen(
    onConfirm: (RecipeCreateRequest) -> Unit,
    onBack: () -> Unit,
    onSearchCiqual: suspend (String) -> List<CiqualFood> = { emptyList() },
) {
    var name by remember { mutableStateOf("") }
    var servings by remember { mutableStateOf("4") }
    var tagsText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val ingredientRows = remember { mutableStateListOf<IngredientRow>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nouvelle recette") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = servings,
                onValueChange = { servings = it },
                label = { Text("Portions") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = { Text("Tags (séparés par des virgules)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ingrédients")
                TextButton(onClick = { ingredientRows.add(IngredientRow()) }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Ajouter")
                }
            }

            ingredientRows.forEachIndexed { index, row ->
                IngredientRowInput(
                    row = row,
                    onUpdate = { ingredientRows[index] = it },
                    onRemove = { ingredientRows.removeAt(index) },
                    onSearchCiqual = onSearchCiqual,
                )
            }

            Button(
                onClick = {
                    val tags = tagsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    val ingredients = ingredientRows
                        .filter { it.ciqualAlimCode.isNotBlank() && it.quantity.isNotBlank() }
                        .map { row ->
                            RecipeIngredientRequest(
                                ciqualAlimCode = row.ciqualAlimCode,
                                quantity = row.quantity.toFloatOrNull() ?: 0f,
                                unit = row.unit.name.lowercase(),
                            )
                        }
                    onConfirm(
                        RecipeCreateRequest(
                            name = name,
                            servings = servings.toIntOrNull() ?: 4,
                            tags = tags,
                            notes = notes.ifBlank { null },
                            ingredients = ingredients,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotBlank(),
            ) {
                Text("Créer")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IngredientRowInput(
    row: IngredientRow,
    onUpdate: (IngredientRow) -> Unit,
    onRemove: () -> Unit,
    onSearchCiqual: suspend (String) -> List<CiqualFood>,
) {
    var unitExpanded by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed) {
        if (isPressed) {
            showSearchDialog = true
        }
    }

    if (showSearchDialog) {
        IngredientSearchDialog(
            onSelect = { food ->
                onUpdate(row.copy(ciqualAlimCode = food.alimCode, ciqualFoodName = food.alimNameFr))
                showSearchDialog = false
            },
            onDismiss = { showSearchDialog = false },
            onSearchCiqual = onSearchCiqual,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OutlinedTextField(
            value = row.ciqualFoodName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Ingrédient") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            interactionSource = interactionSource,
        )
        OutlinedTextField(
            value = row.quantity,
            onValueChange = { onUpdate(row.copy(quantity = it)) },
            label = { Text("Qté") },
            modifier = Modifier.weight(0.5f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        ExposedDropdownMenuBox(
            expanded = unitExpanded,
            onExpandedChange = { unitExpanded = it },
            modifier = Modifier.weight(0.6f),
        ) {
            OutlinedTextField(
                value = row.unit.name.lowercase(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Unité") },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(unitExpanded) },
                singleLine = true,
            )
            ExposedDropdownMenu(expanded = unitExpanded, onDismissRequest = { unitExpanded = false }) {
                CookbookUnit.entries.forEach { unit ->
                    DropdownMenuItem(
                        text = { Text(unit.name.lowercase()) },
                        onClick = {
                            onUpdate(row.copy(unit = unit))
                            unitExpanded = false
                        },
                    )
                }
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Supprimer")
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
internal fun IngredientSearchDialog(
    onSelect: (CiqualFood) -> Unit,
    onDismiss: () -> Unit,
    onSearchCiqual: suspend (String) -> List<CiqualFood>,
) {
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<CiqualFood>>(emptyList()) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(Unit) {
        snapshotFlow { searchQuery }
            .debounce(300)
            .distinctUntilChanged()
            .filter { it.length >= 2 }
            .collect { query ->
                searchResults = try {
                    onSearchCiqual(query)
                } catch (_: Exception) {
                    emptyList()
                }
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rechercher un ingrédient") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Rechercher") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    singleLine = true,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(300.dp).padding(top = 8.dp),
                ) {
                    items(searchResults) { food ->
                        Text(
                            text = food.alimNameFr,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(food) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
