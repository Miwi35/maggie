package com.maggie.app.ui.screens.cookbook

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Recipe
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekUiState
import com.maggie.app.ui.screens.cookbook.meals.MealsWeekViewModel
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListUiState
import com.maggie.app.ui.screens.cookbook.recipes.RecipeListViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** « Cuisine › Repas ▾ » (MAG-628): one page that opens on the week's meals, with the section as a dropdown. */
@RunWith(AndroidJUnit4::class)
class CookbookScreenTest {
    @get:Rule val compose = ScreenRule()

    private fun show(onCreateRecipe: () -> Unit = {}) {
        val recipes = mockk<RecipeListViewModel>(relaxed = true) {
            every { uiState } returns MutableStateFlow(RecipeListUiState(recipes = listOf(Recipe(id = "r1", name = "Chili sin carne"))))
        }
        val meals = mockk<MealsWeekViewModel>(relaxed = true) {
            every { uiState } returns MutableStateFlow(MealsWeekUiState())
        }
        compose.setContent {
            CookbookScreen(
                recipeListViewModel = recipes,
                mealsWeekViewModel = meals,
                onRecipeClick = {},
                onCreateRecipe = onCreateRecipe,
                onCreateMeal = { _, _ -> },
                onOpenMeal = {},
                onChooseIngredients = {},
                onLaunchRecipe = {},
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `it opens on Repas, under the heading Cuisine › Repas`() {
        show()

        compose.onNodeWithTag(UiTags.BREADCRUMB_MODULE).assertTextEquals("Cuisine")
        compose.onNode(hasTestTag(UiTags.BREADCRUMB_SECTION) and hasText("Repas")).assertExists()
        compose.onNodeWithTag(UiTags.MEALS_WEEK_LABEL).assertExists()
        compose.onNodeWithText("Chili sin carne").assertDoesNotExist()
    }

    @Test
    fun `there is no Planning and no tab row`() {
        show()

        compose.onNodeWithText("Planning").assertDoesNotExist()
        compose.onNodeWithText("Planning des repas").assertDoesNotExist()
    }

    @Test
    fun `the caret offers Repas and Recettes, and Recettes shows the list`() {
        show()

        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()
        compose.onNodeWithTag(UiTags.breadcrumbMenu("meals")).assertExists()
        compose.onNodeWithTag(UiTags.breadcrumbMenu("recipes")).performClick()

        compose.onNode(hasTestTag(UiTags.BREADCRUMB_SECTION) and hasText("Recettes")).assertExists()
        compose.onNodeWithText("Chili sin carne").assertExists()
        compose.onNodeWithTag(UiTags.MEALS_WEEK_LABEL).assertDoesNotExist()
    }

    @Test
    fun `the new recipe button is on Recettes only`() {
        var created = 0
        show { created++ }
        compose.onNodeWithTag(UiTags.COOKBOOK_RECIPE_CREATE).assertDoesNotExist()

        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()
        compose.onNodeWithTag(UiTags.breadcrumbMenu("recipes")).performClick()
        compose.onNodeWithContentDescription("Nouvelle recette").assertExists()
        compose.onNodeWithTag(UiTags.COOKBOOK_RECIPE_CREATE).performClick()

        assertEquals(1, created)

        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()
        compose.onNodeWithTag(UiTags.breadcrumbMenu("meals")).performClick()
        compose.onNodeWithTag(UiTags.COOKBOOK_RECIPE_CREATE).assertDoesNotExist()
    }
}
