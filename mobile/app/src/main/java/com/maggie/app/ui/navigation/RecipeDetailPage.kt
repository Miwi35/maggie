package com.maggie.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.maggie.app.data.repository.RecipeRepository
import com.maggie.app.ui.screens.cookbook.cooking.CookingResumeViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeDetailScreen
import com.maggie.app.ui.screens.cookbook.recipes.RecipeDetailViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * A recipe's sheet with its ViewModels (MAG-615): « Cuisiner » or, while a Cuisine cooking it is in progress,
 * « Reprendre ». Out of NavGraph's body for the same reason as the activity pages; [onCook] gets the route to open.
 */
@Composable
fun RecipeDetailPage(
    recipeId: String,
    recipeRepository: RecipeRepository,
    onBack: (() -> Unit)?,
    onEdit: (String) -> Unit,
    onCook: (route: String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val resume by koinViewModel<CookingResumeViewModel>().resume.collectAsState()
    RecipeDetailScreen(
        recipeId = recipeId,
        recipeRepository = recipeRepository,
        viewModel = koinViewModel<RecipeDetailViewModel>(key = recipeId) { parametersOf(recipeId) },
        onBack = onBack,
        onEdit = onEdit,
        onCook = { onCook(Screen.CookingActivity.ofRecipe(it)) },
        onDelete = onDelete,
        resume = resume,
        onResume = { onCook(Screen.CookingActivity.of(it)) },
    )
}
