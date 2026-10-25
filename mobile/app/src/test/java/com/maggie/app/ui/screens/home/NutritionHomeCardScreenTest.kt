package com.maggie.app.ui.screens.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.NutritionHomeMeal
import com.maggie.app.data.model.NutritionHomeSlot
import com.maggie.app.data.model.NutritionHomeState
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Cuisine card (MAG-564, MAG-591): lunch and dinner, every recipe of a meal on one line, what is missing only when it is. */
@RunWith(AndroidJUnit4::class)
class NutritionHomeCardScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun names(vararg names: String) = names.toList()

    private fun meal(id: String, recipes: List<String>, guests: Int = 4, missing: List<String> = emptyList()) =
        NutritionHomeMeal(id = id, title = recipes.firstOrNull().orEmpty(), guests = guests, recipes = recipes, missing = missing)

    private fun state(lunch: List<NutritionHomeMeal> = emptyList(), dinner: List<NutritionHomeMeal> = emptyList()) =
        NutritionHomeState(slots = listOf(NutritionHomeSlot("lunch", lunch), NutritionHomeSlot("dinner", dinner)))

    private fun draw(ui: NutritionHomeUiState, onOpen: () -> Unit = {}, onRetry: () -> Unit = {}) =
        compose.setContent { NutritionHomeCardContent(ui, onOpen, onRetry) }

    @Test
    fun `lunch and dinner are two rows`() {
        draw(NutritionHomeUiState(state(lunch = listOf(meal("m", names("Soupe")))), isLoading = false))

        compose.onNodeWithText("Cuisine").assertIsDisplayed()
        compose.onNodeWithText("Déjeuner").assertIsDisplayed()
        compose.onNodeWithText("Dîner").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.HOME_NUTRITION_SLOT).assertCountEquals(2)
    }

    @Test
    fun `a day without meal draws nothing`() {
        draw(NutritionHomeUiState(state(), isLoading = false))

        compose.onNodeWithTag(UiTags.homeCard(HomeModules.NUTRITION)).assertDoesNotExist()
        compose.onNodeWithText("Cuisine").assertDoesNotExist()
    }

    @Test
    fun `a failed read keeps the card, with its error`() {
        draw(NutritionHomeUiState(isLoading = false, failed = true))

        compose.onNodeWithTag(UiTags.homeCard(HomeModules.NUTRITION)).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_NUTRITION_ERROR).assertIsDisplayed()
    }

    @Test
    fun `a meal of three recipes shows all three on one line, with its guests`() {
        draw(NutritionHomeUiState(state(dinner = listOf(meal("m", names("Salade", "Tarte", "Fruits")))), isLoading = false))

        compose.onNodeWithText("Salade · Tarte · Fruits").assertIsDisplayed()
        compose.onNodeWithText("4 pers.").assertIsDisplayed()
    }

    @Test
    fun `several meals in a slot are all listed`() {
        draw(NutritionHomeUiState(state(lunch = listOf(meal("a", names("Soupe")), meal("b", names("Quiche")))), isLoading = false))

        compose.onAllNodesWithTag(UiTags.HOME_NUTRITION_MEAL).assertCountEquals(2)
        compose.onNodeWithText("Soupe").assertIsDisplayed()
        compose.onNodeWithText("Quiche").assertIsDisplayed()
    }

    @Test
    fun `an empty slot reads « — »`() {
        draw(NutritionHomeUiState(state(dinner = listOf(meal("m", names("Soupe")))), isLoading = false))

        compose.onAllNodesWithTag(UiTags.HOME_NUTRITION_EMPTY).assertCountEquals(1)
        compose.onNodeWithText("—").assertIsDisplayed()
    }

    @Test
    fun `what is missing is shown only when something is`() {
        draw(
            NutritionHomeUiState(
                state(
                    lunch = listOf(meal("a", names("Soupe"), missing = names("Parmesan", "Basilic"))),
                    dinner = listOf(meal("b", names("Pâtes"))),
                ),
                isLoading = false,
            ),
        )

        compose.onAllNodesWithTag(UiTags.HOME_NUTRITION_MISSING).assertCountEquals(1)
        compose.onNodeWithText("manque : parmesan, basilic").assertIsDisplayed()
    }

    @Test
    fun `a tap on the header opens Repas`() {
        var opened = 0
        draw(NutritionHomeUiState(state(dinner = listOf(meal("m", names("Soupe")))), isLoading = false), onOpen = { opened++ })

        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.NUTRITION)).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `a tap on a meal opens Repas too`() {
        var opened = 0
        draw(NutritionHomeUiState(state(dinner = listOf(meal("m", names("Soupe")))), isLoading = false), onOpen = { opened++ })

        compose.onNodeWithText("Soupe").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `a card still reading shows no meal`() {
        draw(NutritionHomeUiState())

        compose.onAllNodesWithTag(UiTags.HOME_NUTRITION_SLOT).assertCountEquals(0)
        compose.onAllNodesWithTag(UiTags.HOME_NUTRITION_ERROR).assertCountEquals(0)
    }

    @Test
    fun `a failed read says so and « Réessayer » reads again`() {
        var retried = 0
        draw(NutritionHomeUiState(state = null, isLoading = false, failed = true), onRetry = { retried++ })

        compose.onNodeWithTag(UiTags.HOME_NUTRITION_ERROR).assertIsDisplayed()
        compose.onNodeWithText("Réessayer").performClick()

        assertEquals(1, retried)
    }
}
