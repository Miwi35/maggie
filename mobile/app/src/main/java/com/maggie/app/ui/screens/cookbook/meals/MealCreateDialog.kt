package com.maggie.app.ui.screens.cookbook.meals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.GuestsStepper
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListViewModel
import com.maggie.app.ui.uiTagRoot

/**
 * Plans a meal on a day and a slot (MAG-378). No title to type: the API names the meal after
 * its menu. The « Menu » field opens a full-screen search; the recipes chosen are listed
 * under it, each with its own remove button.
 */
@Composable
fun MealCreateDialog(
    date: String,
    slot: String,
    viewModel: MealCreateViewModel,
    recipeListViewModel: RecipeListViewModel,
    onCreated: (mealId: String, withRecipes: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val recipeUiState by recipeListViewModel.uiState.collectAsState()
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        recipeListViewModel.refresh()
        viewModel.loadDefaultGuests()
    }
    DisposableEffect(Unit) { onDispose { viewModel.reset() } }

    AlertDialog(
        onDismissRequest = { if (!state.isSubmitting) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !state.isSubmitting,
            dismissOnClickOutside = !state.isSubmitting,
        ),
        modifier = Modifier.uiTagRoot(),
        title = {
            val slotLabel = if (slot == "lunch") "Déjeuner" else "Dîner"
            Text("$slotLabel — $date")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MealMenuSection(
                    recipes = state.recipes,
                    enabled = !state.isSubmitting,
                    onOpenSearch = { searching = true },
                    onRemove = viewModel::remove,
                )

                GuestsField(
                    guests = state.guests,
                    isDefault = state.guestsIsDefault,
                    enabled = !state.isSubmitting,
                    onStep = viewModel::stepGuests,
                )

                state.error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.submit(date, slot, onCreated) },
                enabled = !state.isSubmitting,
                modifier = Modifier.testTag(UiTags.MEAL_CREATE_SUBMIT),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Créer")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isSubmitting) { Text("Annuler") }
        },
    )

    if (searching) {
        RecipeSearchScreen(
            recipes = recipeUiState.recipes,
            chosenIds = state.recipes.map { it.id }.toSet(),
            onPick = viewModel::add,
            onDone = { searching = false },
        )
    }
}

/** « Convives »: the number of people, by steps; marked « par défaut » while it follows the setting. */
@Composable
private fun GuestsField(guests: Int, isDefault: Boolean, enabled: Boolean, onStep: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Convives", style = MaterialTheme.typography.bodyLarge)
            if (isDefault) {
                Text(
                    text = "par défaut",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(UiTags.MEAL_GUESTS_DEFAULT),
                )
            }
        }
        GuestsStepper(value = guests, onStep = onStep, tag = UiTags.MEAL_GUESTS, enabled = enabled)
    }
}
