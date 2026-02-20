package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.repository.RecipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditScreen(
    recipeId: String,
    recipeRepository: RecipeRepository,
    onConfirm: (String, JsonObject) -> Unit,
    onBack: () -> Unit,
) {
    var recipe by remember { mutableStateOf<Recipe?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    var name by remember { mutableStateOf("") }
    var servings by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val ingredientRows = remember { mutableStateListOf<IngredientRow>() }

    LaunchedEffect(recipeId) {
        isLoading = true
        try {
            val r = withContext(Dispatchers.IO) {
                recipeRepository.getRecipe(recipeId).getOrThrow()
            }
            recipe = r
            name = r.name
            servings = r.servings.toString()
            tagsText = r.tags.joinToString(", ")
            notes = r.notes ?: ""
            ingredientRows.clear()
            r.ingredients.forEach { ing ->
                ingredientRows.add(
                    IngredientRow(
                        ingredientIri = ing.ingredient,
                        quantity = ing.quantity.toString(),
                        unit = ing.unit,
                    ),
                )
            }
        } catch (_: Exception) {}
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modifier la recette") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
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
                    EditIngredientRowInput(
                        row = row,
                        onUpdate = { ingredientRows[index] = it },
                        onRemove = { ingredientRows.removeAt(index) },
                    )
                }

                Button(
                    onClick = {
                        val tags = tagsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        val data = buildJsonObject {
                            put("name", name)
                            put("servings", servings.toIntOrNull() ?: 4)
                            putJsonArray("tags") { tags.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                            put("notes", notes.ifBlank { null })
                            putJsonArray("ingredients") {
                                ingredientRows
                                    .filter { it.ingredientIri.isNotBlank() && it.quantity.isNotBlank() }
                                    .forEach { row ->
                                        addJsonObject {
                                            put("ingredient", row.ingredientIri)
                                            put("quantity", row.quantity.toFloatOrNull() ?: 0f)
                                            put("unit", row.unit.name.lowercase())
                                        }
                                    }
                            }
                        }
                        onConfirm(recipeId, data)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = name.isNotBlank(),
                ) {
                    Text("Enregistrer")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditIngredientRowInput(
    row: IngredientRow,
    onUpdate: (IngredientRow) -> Unit,
    onRemove: () -> Unit,
) {
    var unitExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OutlinedTextField(
            value = row.ingredientIri,
            onValueChange = { onUpdate(row.copy(ingredientIri = it)) },
            label = { Text("IRI ingrédient") },
            modifier = Modifier.weight(1f),
            singleLine = true,
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
