package com.maggie.app.ui.screens.cookbook.meals

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Recipe
import com.maggie.app.ui.UiTags

/**
 * A meal's menu (MAG-378): the « Menu » field, which only opens the recipe search, and the
 * recipes chosen under it, each with its own remove button. Shared by the creation of a meal
 * and its sheet.
 */
@Composable
fun MealMenuSection(
    recipes: List<Recipe>,
    enabled: Boolean,
    onOpenSearch: () -> Unit,
    onRemove: (recipeId: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MenuField(onOpen = onOpenSearch)

        recipes.forEach { recipe ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = recipe.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).testTag(UiTags.MEAL_MENU_RECIPE),
                )
                IconButton(
                    onClick = { onRemove(recipe.id) },
                    enabled = enabled,
                    modifier = Modifier.testTag(UiTags.MEAL_MENU_RECIPE_REMOVE),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Retirer ${recipe.name}")
                }
            }
        }
    }
}

/** A field that is only a way into the search: a tap anywhere on it opens the search. */
@Composable
private fun MenuField(onOpen: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    // Each release opens it; a press dragged off the field ends in a cancel and does not.
    LaunchedEffect(interactions) {
        interactions.interactions.collect { if (it is PressInteraction.Release) onOpen() }
    }

    OutlinedTextField(
        value = "",
        onValueChange = {},
        readOnly = true,
        label = { Text("Menu") },
        placeholder = { Text("Ajouter une recette") },
        interactionSource = interactions,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.MEAL_MENU_FIELD),
        singleLine = true,
    )
}
