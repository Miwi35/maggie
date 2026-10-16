package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.maggie.app.data.model.CiqualFood
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Utensil
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

@Composable
fun RecipeCreateScreen(
    viewModel: RecipeCreateViewModel,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    onSearchCiqual: suspend (String) -> List<CiqualFood> = { emptyList() },
    onLoadUtensils: suspend () -> List<Utensil> = { emptyList() },
) {
    val state by viewModel.uiState.collectAsState()

    RecipeFormScreen(
        title = "Nouvelle recette",
        saveLabel = "Créer",
        form = state.form,
        isSaving = state.isSaving,
        error = state.error,
        stepErrors = state.stepErrors,
        edit = viewModel::edit,
        onSave = { viewModel.save(onSaved) },
        onBack = onBack,
        onSearchCiqual = onSearchCiqual,
        onLoadUtensils = onLoadUtensils,
    )
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
