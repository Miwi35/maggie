package com.maggie.app.ui.screens.cookbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Meal
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekScreen
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListScreen
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListViewModel
import com.maggie.app.ui.uiTagRoot

/** What Cuisine shows: the week's meals first, as the mock-up opens it, or the recipes. */
enum class CookbookSection(val title: String, val tag: String) {
    Meals("Repas", UiTags.COOKBOOK_SECTION_MEALS),
    Recipes("Recettes", UiTags.COOKBOOK_SECTION_RECIPES),
}

/**
 * « Cuisine › Repas ▾ » (MAG-628): one page, whose heading switches between Repas and Recettes. There is
 * no Planning: the week of meals is the planning. The recipe FAB only shows on Recettes.
 */
@Composable
fun CookbookScreen(
    recipeListViewModel: RecipeListViewModel,
    mealsWeekViewModel: MealsWeekViewModel,
    onRecipeClick: (String) -> Unit,
    onCreateRecipe: () -> Unit,
    onCreateMeal: (day: String, slot: String) -> Unit,
    onOpenMeal: (Meal) -> Unit,
    onChooseIngredients: (mealId: String) -> Unit,
    onLaunchRecipe: (recipeId: String) -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(CookbookSection.Meals) }

    Scaffold(
        floatingActionButton = {
            if (section == CookbookSection.Recipes) {
                FloatingActionButton(onClick = onCreateRecipe, modifier = Modifier.testTag(UiTags.COOKBOOK_RECIPE_CREATE)) {
                    Icon(Icons.Default.Add, contentDescription = "Nouvelle recette")
                }
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            CookbookHeading(section = section, onSelect = { section = it })
            when (section) {
                CookbookSection.Meals -> MealsWeekScreen(
                    viewModel = mealsWeekViewModel,
                    onCreateMeal = onCreateMeal,
                    onOpenMeal = onOpenMeal,
                    onChooseIngredients = onChooseIngredients,
                    onLaunchRecipe = onLaunchRecipe,
                )
                CookbookSection.Recipes -> RecipeListScreen(
                    viewModel = recipeListViewModel,
                    onRecipeClick = onRecipeClick,
                )
            }
        }
    }
}

@Composable
private fun CookbookHeading(section: CookbookSection, onSelect: (CookbookSection) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Cuisine › ", style = MaterialTheme.typography.titleLarge)
        Box {
            Row(
                modifier = Modifier.clickable { open = true }.testTag(UiTags.COOKBOOK_SECTION),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(section.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Changer de section", tint = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.uiTagRoot()) {
                CookbookSection.entries.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(entry.title) },
                        onClick = {
                            open = false
                            onSelect(entry)
                        },
                        modifier = Modifier.testTag(entry.tag),
                    )
                }
            }
        }
    }
}
