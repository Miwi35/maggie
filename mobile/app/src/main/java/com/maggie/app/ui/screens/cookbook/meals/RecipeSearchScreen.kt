package com.maggie.app.ui.screens.cookbook.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maggie.app.data.model.Recipe
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot

/**
 * The recipes to put on a meal's menu, in a full screen of their own (MAG-378): an
 * autocomplete never opens as a dropdown on the phone. A tap on a recipe adds it and
 * leaves the search open, so a menu of several dishes is chosen in one visit.
 */
@Composable
fun RecipeSearchScreen(
    recipes: List<Recipe>,
    chosenIds: Set<String>,
    onPick: (Recipe) -> Unit,
    onDone: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val matches = recipes.filter { it.name.contains(query.trim(), ignoreCase = true) }

    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize().uiTagRoot()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDone, modifier = Modifier.testTag(UiTags.MEAL_RECIPE_SEARCH_DONE)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terminer")
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Rechercher une recette") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                            .focusRequester(focusRequester)
                            .testTag(UiTags.MEAL_RECIPE_SEARCH_FIELD),
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(matches, key = { it.id }) { recipe ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(recipe) }
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .testTag(UiTags.MEAL_RECIPE_RESULT),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = recipe.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (recipe.id in chosenIds) {
                                Icon(Icons.Default.Check, contentDescription = "Déjà au menu")
                            }
                        }
                        HorizontalDivider()
                    }
                    if (matches.isEmpty()) {
                        item {
                            Text(
                                text = "Aucune recette ne correspond.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
