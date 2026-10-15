package com.maggie.app.ui.screens.cookbook.meals

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Recipe
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The menu of a meal, shared by its creation and its sheet (MAG-378). The sheet itself is an
 * `AlertDialog`, which hangs Robolectric's idling: its states are covered by
 * `MealDetailViewModelTest`, and what it draws of the menu is this.
 */
@RunWith(AndroidJUnit4::class)
class MealMenuSectionTest {
    @get:Rule val compose = ScreenRule()

    private val pasta = Recipe(id = "r-pasta", name = "Pâtes")
    private val salad = Recipe(id = "r-salad", name = "Salade verte")

    @Test
    fun `each recipe of the menu is listed with its own remove button`() {
        val removed = mutableListOf<String>()
        compose.setContent {
            MealMenuSection(recipes = listOf(pasta, salad), enabled = true, onOpenSearch = {}, onRemove = { removed += it })
        }
        compose.waitForIdle()

        compose.onAllNodesWithTag(UiTags.MEAL_MENU_RECIPE).assertCountEquals(2)
        compose.onAllNodesWithTag(UiTags.MEAL_MENU_RECIPE_REMOVE)[1].performClick()
        compose.waitForIdle()

        assertEquals(listOf("r-salad"), removed)
    }

    @Test
    fun `a tap on the Menu field opens the search`() {
        var opened = 0
        compose.setContent {
            MealMenuSection(recipes = emptyList(), enabled = true, onOpenSearch = { opened++ }, onRemove = {})
        }
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.MEAL_MENU_FIELD).performClick()
        compose.waitForIdle()

        assertEquals(1, opened)
    }
}
