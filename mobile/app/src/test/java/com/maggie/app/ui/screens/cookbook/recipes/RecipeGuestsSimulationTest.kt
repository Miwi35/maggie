package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** « Pour N personnes » on the recipe sheet (MAG-483): starts at the servings, steps, goes back, says when it failed. */
@RunWith(AndroidJUnit4::class)
class RecipeGuestsSimulationTest {

    @get:Rule
    val compose = ScreenRule()

    private val steps = mutableListOf<Int>()
    private var resets = 0

    private fun show(simulation: RecipeSimulation?) {
        compose.setContent {
            GuestsSimulation(servings = 4, simulation = simulation, onStep = { steps += it }, onReset = { resets++ })
        }
    }

    @Test
    fun `it starts at the servings of the recipe, with nothing to go back to`() {
        show(null)

        compose.onNodeWithTag(UiTags.RECIPE_GUESTS + "_value").assertTextEquals("4")
        compose.onNodeWithText("personnes").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.RECIPE_GUESTS_RESET).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.RECIPE_GUESTS_SIMULATION).assertDoesNotExist()
    }

    @Test
    fun `the buttons step by one`() {
        show(null)

        compose.onNodeWithTag(UiTags.RECIPE_GUESTS + "_plus").performClick()
        compose.onNodeWithTag(UiTags.RECIPE_GUESTS + "_minus").performClick()

        assertEquals(listOf(1, -1), steps)
    }

    @Test
    fun `a simulation shows its number, says nothing is saved, and Parts de la recette goes back`() {
        show(RecipeSimulation(guests = 6))

        compose.onNodeWithTag(UiTags.RECIPE_GUESTS + "_value").assertTextEquals("6")
        compose.onNodeWithTag(UiTags.RECIPE_GUESTS_SIMULATION).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.RECIPE_GUESTS_RESET).performClick()

        assertEquals(1, resets)
    }

    @Test
    fun `one person is in the singular and cannot go lower`() {
        show(RecipeSimulation(guests = 1))

        compose.onNodeWithText("personne").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.RECIPE_GUESTS + "_minus").assertIsNotEnabled()
    }

    @Test
    fun `a failed simulation shows its message`() {
        show(RecipeSimulation(guests = 6, error = SIMULATION_ERROR))

        compose.onNodeWithTag(UiTags.RECIPE_GUESTS_ERROR).assertTextEquals(SIMULATION_ERROR)
    }
}
