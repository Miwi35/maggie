package com.maggie.app.ui.screens.cookbook.meals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealSlot
import com.maggie.app.data.model.Recipe
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListViewModel
import com.maggie.app.ui.uiTagRoot

/**
 * The sheet of a meal already planned (MAG-378): the menu with a remove button per recipe and the
 * « Menu » field to add more, the title — the recipes' names until the owner types his own —
 * and « Supprimer », which asks first.
 */
@Composable
fun MealDetailDialog(
    meal: Meal,
    viewModel: MealDetailViewModel,
    recipeListViewModel: RecipeListViewModel,
    onSaved: (Meal) -> Unit,
    onDeleted: () -> Unit,
    onChooseIngredients: () -> Unit,
    onLaunch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val recipeUiState by recipeListViewModel.uiState.collectAsState()
    var searching by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(meal.id) {
        viewModel.open(meal)
        recipeListViewModel.refresh()
    }

    // The sheet shows nothing until the ViewModel has started from the meal.
    if (state.meal?.id != meal.id) return

    val menu = state.recipeIds.map { id ->
        recipeUiState.recipes.firstOrNull { it.id == id } ?: Recipe(id = id, name = "Recette")
    }
    val slotLabel = if (meal.slot == MealSlot.LUNCH) "Déjeuner" else "Dîner"
    val defaultTitle = menu.joinToString(", ") { it.name }.ifEmpty { slotLabel }

    AlertDialog(
        onDismissRequest = { if (!state.isBusy) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !state.isBusy,
            dismissOnClickOutside = !state.isBusy,
        ),
        modifier = Modifier.uiTagRoot(),
        title = { Text("$slotLabel — ${meal.date}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::setTitle,
                    label = { Text("Titre") },
                    placeholder = { Text(defaultTitle) },
                    supportingText = { Text("Laisse vide pour le titre par défaut : $defaultTitle") },
                    enabled = !state.isBusy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.MEAL_DETAIL_TITLE),
                )

                MealMenuSection(
                    recipes = menu,
                    enabled = !state.isBusy,
                    onOpenSearch = { searching = true },
                    onRemove = viewModel::remove,
                )

                state.error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        onClick = onChooseIngredients,
                        enabled = !state.isBusy && !state.hasChanges && state.recipeIds.isNotEmpty(),
                        modifier = Modifier.testTag(UiTags.MEAL_DETAIL_INGREDIENTS),
                    ) { Text("Ingrédients") }
                    // « Lancer » opens the Cuisine activity on this meal (MAG-580); unsaved changes would be cooked stale.
                    TextButton(
                        onClick = onLaunch,
                        enabled = !state.isBusy && !state.hasChanges && state.recipeIds.isNotEmpty(),
                        modifier = Modifier.testTag(UiTags.MEAL_DETAIL_LAUNCH),
                    ) { Text("Lancer") }
                    TextButton(
                        onClick = { confirmingDelete = true },
                        enabled = !state.isBusy,
                        modifier = Modifier.testTag(UiTags.MEAL_DETAIL_DELETE),
                    ) {
                        if (state.isDeleting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Supprimer", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.save(onSaved) },
                enabled = !state.isBusy && state.hasChanges,
                modifier = Modifier.testTag(UiTags.MEAL_DETAIL_SAVE),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Enregistrer")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isBusy) { Text("Fermer") }
        },
    )

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            modifier = Modifier.uiTagRoot(),
            title = { Text("Supprimer ce repas ?") },
            text = { Text(meal.summary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        viewModel.delete(onDeleted)
                    },
                    modifier = Modifier.testTag(UiTags.MEAL_DELETE_CONFIRM),
                ) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Annuler") } },
        )
    }

    if (searching) {
        RecipeSearchScreen(
            recipes = recipeUiState.recipes,
            chosenIds = state.recipeIds.toSet(),
            onPick = viewModel::add,
            onDone = { searching = false },
        )
    }
}
