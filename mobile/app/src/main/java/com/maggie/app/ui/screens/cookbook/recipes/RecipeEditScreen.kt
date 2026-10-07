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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CiqualFood
import com.maggie.app.data.model.RecipeIngredient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditScreen(
    recipeId: String,
    viewModel: RecipeEditViewModel,
    onConfirm: (String, JsonObject) -> Unit,
    onBack: () -> Unit,
    onSearchCiqual: suspend (String) -> List<CiqualFood> = { emptyList() },
) {
    val state by viewModel.uiState.collectAsState()
    val form = state.form
    val isLoading = state.isLoading

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
                if (state.changedElsewhere) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Recette modifiée ailleurs : vos changements en cours sont conservés.",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = viewModel::reload) { Text("Recharger") }
                        }
                    }
                }

                OutlinedTextField(
                    value = form.name,
                    onValueChange = viewModel::onName,
                    label = { Text("Nom") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = form.servings,
                    onValueChange = viewModel::onServings,
                    label = { Text("Portions") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                OutlinedTextField(
                    value = form.tagsText,
                    onValueChange = viewModel::onTags,
                    label = { Text("Tags (séparés par des virgules)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = form.notes,
                    onValueChange = viewModel::onNotes,
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
                    TextButton(onClick = { viewModel.onIngredients(form.ingredients + IngredientRow()) }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("Ajouter")
                    }
                }

                form.ingredients.forEachIndexed { index, row ->
                    IngredientRowInput(
                        row = row,
                        onUpdate = { updated -> viewModel.onIngredients(form.ingredients.mapIndexed { i, r -> if (i == index) updated else r }) },
                        onRemove = { viewModel.onIngredients(form.ingredients.filterIndexed { i, _ -> i != index }) },
                        onSearchCiqual = onSearchCiqual,
                    )
                }

                Button(
                    onClick = { onConfirm(recipeId, form.toRequest()) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = form.name.isNotBlank(),
                ) {
                    Text("Enregistrer")
                }
            }
        }
    }
}

/** The id of the ingredient a recipe line points at, whether the API embeds it or sends its IRI. */
internal fun RecipeIngredient.ingredientId(): String {
    val ref = when (val value = ingredient) {
        is JsonObject -> (value["id"] as? JsonPrimitive)?.contentOrNull
        is JsonPrimitive -> value.contentOrNull?.substringAfterLast('/')
        else -> null
    }
    return ref.orEmpty()
}
