package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
    val ciqualFoodId: String = "",
    val ciqualFoodName: String = "",
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
                        .filter { it.ciqualFoodId.isNotBlank() && it.quantity.isNotBlank() }
                        .map { row ->
                            RecipeIngredientRequest(
                                ciqualFood = "/api/ciqual_foods/${row.ciqualFoodId}",
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

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun IngredientRowInput(
    row: IngredientRow,
    onUpdate: (IngredientRow) -> Unit,
    onRemove: () -> Unit,
    onSearchCiqual: suspend (String) -> List<CiqualFood>,
) {
    var unitExpanded by remember { mutableStateOf(false) }
    var ciqualExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf(row.ciqualFoodName) }
    var searchResults by remember { mutableStateOf<List<CiqualFood>>(emptyList()) }

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
                ciqualExpanded = searchResults.isNotEmpty()
            }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ExposedDropdownMenuBox(
            expanded = ciqualExpanded,
            onExpandedChange = { ciqualExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    if (it != row.ciqualFoodName) {
                        onUpdate(row.copy(ciqualFoodId = "", ciqualFoodName = ""))
                    }
                },
                label = { Text("Aliment Ciqual") },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable),
                singleLine = true,
            )
            ExposedDropdownMenu(
                expanded = ciqualExpanded,
                onDismissRequest = { ciqualExpanded = false },
            ) {
                searchResults.forEach { food ->
                    DropdownMenuItem(
                        text = { Text(food.alimNameFr) },
                        onClick = {
                            searchQuery = food.alimNameFr
                            onUpdate(row.copy(ciqualFoodId = food.id, ciqualFoodName = food.alimNameFr))
                            ciqualExpanded = false
                        },
                    )
                }
            }
        }
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
