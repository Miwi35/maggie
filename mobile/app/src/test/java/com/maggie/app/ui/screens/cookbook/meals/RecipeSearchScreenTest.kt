package com.maggie.app.ui.screens.cookbook.meals

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Recipe
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RecipeSearchScreenTest {
    @get:Rule val compose = ScreenRule()

    private val recipes = listOf(Recipe(id = "r1", name = "Riz au curry"), Recipe(id = "r2", name = "Salade verte"))

    @Test
    fun `the field has the focus on opening and a space typed in it keeps the search open`() {
        var done = 0
        compose.setContent {
            RecipeSearchScreen(recipes = recipes, chosenIds = emptySet(), onPick = {}, onDone = { done++ })
        }
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.MEAL_RECIPE_SEARCH_FIELD).assertIsFocused()
        compose.onNodeWithTag(UiTags.MEAL_RECIPE_SEARCH_FIELD).performKeyInput { pressKey(Key.Spacebar) }
        compose.onNodeWithTag(UiTags.MEAL_RECIPE_SEARCH_FIELD).performTextInput("Riz au")
        compose.waitForIdle()

        assertEquals(0, done)
    }

    @Test
    fun `a recipe already on the menu is no longer offered`() {
        compose.setContent {
            RecipeSearchScreen(recipes = recipes, chosenIds = setOf("r1"), onPick = {}, onDone = {})
        }
        compose.waitForIdle()

        compose.onAllNodesWithText("Riz au curry").assertCountEquals(0)
        compose.onAllNodesWithTag(UiTags.MEAL_RECIPE_RESULT).assertCountEquals(1)
    }
}
