package com.maggie.app.ui.screens.cookbook.meals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.MealCreateRequest
import com.maggie.app.data.model.Recipe
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealCreateDialog(
    date: String,
    slot: String,
    recipeListViewModel: RecipeListViewModel,
    onConfirm: (MealCreateRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    val recipeUiState by recipeListViewModel.uiState.collectAsState()
    var summary by remember { mutableStateOf("") }
    val selectedRecipeIds = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val slotLabel = if (slot == "lunch") "Déjeuner" else "Dîner"
            Text("$slotLabel — $date")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Text("Recettes", style = MaterialTheme.typography.labelMedium)

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    recipeUiState.recipes.forEach { recipe ->
                        FilterChip(
                            selected = recipe.id in selectedRecipeIds,
                            onClick = {
                                if (recipe.id in selectedRecipeIds) {
                                    selectedRecipeIds.remove(recipe.id)
                                } else {
                                    selectedRecipeIds.add(recipe.id)
                                }
                            },
                            label = { Text(recipe.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val recipeIris = selectedRecipeIds.map { "/api/recipes/$it" }
                    val summaryText = summary.ifBlank {
                        recipeUiState.recipes
                            .filter { it.id in selectedRecipeIds }
                            .joinToString(", ") { it.name }
                    }
                    onConfirm(
                        MealCreateRequest(
                            summary = summaryText,
                            startAt = "${date}T12:00:00+01:00",
                            endAt = "${date}T13:00:00+01:00",
                            slot = slot,
                            recipes = recipeIris,
                        ),
                    )
                },
                enabled = summary.isNotBlank() || selectedRecipeIds.isNotEmpty(),
            ) {
                Text("Créer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
