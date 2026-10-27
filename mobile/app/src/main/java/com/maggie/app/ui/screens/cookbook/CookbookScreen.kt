package com.maggie.app.ui.screens.cookbook

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.maggie.app.data.model.Meal
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.BreadcrumbSection
import com.maggie.app.ui.components.ModuleBreadcrumb
import com.maggie.app.ui.navigation.LocalModuleBar
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekScreen
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListScreen
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListViewModel

/** What Cuisine shows: the week's meals first, as the mock-up opens it, or the recipes. */
enum class CookbookSection(val key: String, val title: String) {
    Meals("meals", "Repas"),
    Recipes("recipes", "Recettes"),
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
        // The breadcrumb pays the status bar, as the frame's bar did.
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.statusBars),
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
    val bar = LocalModuleBar.current
    ModuleBreadcrumb(
        module = "Cuisine",
        sections = CookbookSection.entries.map { BreadcrumbSection(it.key, it.title) },
        currentKey = section.key,
        onSelectSection = { picked -> CookbookSection.entries.firstOrNull { it.key == picked.key }?.let(onSelect) },
        onOpenDrawer = bar.onOpenDrawer,
        actions = bar.actions,
    )
}
