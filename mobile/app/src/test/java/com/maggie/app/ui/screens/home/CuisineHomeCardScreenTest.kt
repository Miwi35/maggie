package com.maggie.app.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CuisineHomeMeal
import com.maggie.app.data.model.CuisineHomeSlot
import com.maggie.app.data.model.CuisineHomeState
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.cookbook.cooking.CookingOrigin
import com.maggie.app.ui.screens.cookbook.cooking.CookingPhase
import com.maggie.app.ui.screens.cookbook.cooking.CookingResume
import com.maggie.app.ui.screens.cookbook.cooking.CookingSession
import com.maggie.app.ui.screens.cookbook.cooking.QueuedRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Cuisine card (MAG-564, MAG-591): lunch and dinner, every recipe of a meal on one line, what is missing only when it is. */
@RunWith(AndroidJUnit4::class)
class CuisineHomeCardScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun names(vararg names: String) = names.toList()

    private fun meal(id: String, recipes: List<String>, guests: Int = 4, missing: List<String> = emptyList()) =
        CuisineHomeMeal(id = id, title = recipes.firstOrNull().orEmpty(), guests = guests, recipes = recipes, missing = missing)

    private fun state(lunch: List<CuisineHomeMeal> = emptyList(), dinner: List<CuisineHomeMeal> = emptyList()) =
        CuisineHomeState(slots = listOf(CuisineHomeSlot("lunch", lunch), CuisineHomeSlot("dinner", dinner)))

    private fun draw(ui: CuisineHomeUiState, onOpen: () -> Unit = {}, onRetry: () -> Unit = {}, resume: CookingResume? = null) =
        compose.setContent { CuisineHomeCardContent(ui, onOpen, onRetry, resume = resume) }

    private fun resuming(mealId: String) = CookingResume(
        CookingSession(
            origin = CookingOrigin(CookingOrigin.Kind.MEAL, mealId),
            queue = listOf(QueuedRecipe("r-a", 4)),
            guests = 4,
            phase = CookingPhase.COOKING,
            step = 2,
        ),
    )

    @Test
    fun `the pill of the meal in progress reads Reprendre, the other meal keeps Lancer`() {
        draw(
            CuisineHomeUiState(state(lunch = listOf(meal("m1", names("Soupe"))), dinner = listOf(meal("m2", names("Tarte")))), isLoading = false),
            resume = resuming("m2"),
        )

        compose.onNodeWithContentDescription("Reprendre la cuisine de Tarte").assertIsDisplayed()
        compose.onNodeWithContentDescription("Lancer la cuisine de Soupe").assertIsDisplayed()
    }

    @Test
    fun `lunch and dinner are two rows`() {
        draw(CuisineHomeUiState(state(lunch = listOf(meal("m", names("Soupe")))), isLoading = false))

        compose.onNodeWithText("Cuisine").assertIsDisplayed()
        compose.onNodeWithText("Déjeuner").assertIsDisplayed()
        compose.onNodeWithText("Dîner").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.HOME_CUISINE_SLOT).assertCountEquals(2)
    }

    @Test
    fun `a day without meal draws nothing`() {
        draw(CuisineHomeUiState(state(), isLoading = false))

        compose.onNodeWithTag(UiTags.homeCard(HomeModules.CUISINE)).assertDoesNotExist()
        compose.onNodeWithText("Cuisine").assertDoesNotExist()
    }

    @Test
    fun `a failed read keeps the card, with its error`() {
        draw(CuisineHomeUiState(isLoading = false, failed = true))

        compose.onNodeWithTag(UiTags.homeCard(HomeModules.CUISINE)).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_CUISINE_ERROR).assertIsDisplayed()
    }

    @Test
    fun `a meal of three recipes shows all three on one line, with its guests`() {
        draw(CuisineHomeUiState(state(dinner = listOf(meal("m", names("Salade", "Tarte", "Fruits")))), isLoading = false))

        compose.onNodeWithText("Salade · Tarte · Fruits").assertIsDisplayed()
        compose.onNodeWithText("4 pers.").assertIsDisplayed()
    }

    @Test
    fun `the guests sit under the recipe name so a long name keeps the row's width`() {
        val ui = CuisineHomeUiState(state(dinner = listOf(meal("m", names("Bœuf bourguignon")))), isLoading = false)
        compose.setContent { Box(Modifier.width(328.dp)) { CuisineHomeCardContent(ui, onOpen = {}, onRetry = {}) } }

        val name = compose.onNodeWithText("Bœuf bourguignon").getBoundsInRoot()
        val guests = compose.onNodeWithText("4 pers.").getBoundsInRoot()

        assertTrue("« 4 pers. » (top ${guests.top}) shares the name's line (bottom ${name.bottom})", guests.top >= name.bottom)
    }

    @Test
    fun `several meals in a slot are all listed`() {
        draw(CuisineHomeUiState(state(lunch = listOf(meal("a", names("Soupe")), meal("b", names("Quiche")))), isLoading = false))

        compose.onAllNodesWithTag(UiTags.HOME_CUISINE_MEAL).assertCountEquals(2)
        compose.onNodeWithText("Soupe").assertIsDisplayed()
        compose.onNodeWithText("Quiche").assertIsDisplayed()
    }

    @Test
    fun `an empty slot reads « — »`() {
        draw(CuisineHomeUiState(state(dinner = listOf(meal("m", names("Soupe")))), isLoading = false))

        compose.onAllNodesWithTag(UiTags.HOME_CUISINE_EMPTY).assertCountEquals(1)
        compose.onNodeWithText("—").assertIsDisplayed()
    }

    @Test
    fun `what is missing is shown only when something is`() {
        draw(
            CuisineHomeUiState(
                state(
                    lunch = listOf(meal("a", names("Soupe"), missing = names("Parmesan", "Basilic"))),
                    dinner = listOf(meal("b", names("Pâtes"))),
                ),
                isLoading = false,
            ),
        )

        compose.onAllNodesWithTag(UiTags.HOME_CUISINE_MISSING).assertCountEquals(1)
        compose.onNodeWithText("manque : parmesan, basilic").assertIsDisplayed()
    }

    @Test
    fun `a tap on the header opens Repas`() {
        var opened = 0
        draw(CuisineHomeUiState(state(dinner = listOf(meal("m", names("Soupe")))), isLoading = false), onOpen = { opened++ })

        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.CUISINE)).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `a tap on a meal opens Repas too`() {
        var opened = 0
        draw(CuisineHomeUiState(state(dinner = listOf(meal("m", names("Soupe")))), isLoading = false), onOpen = { opened++ })

        compose.onNodeWithText("Soupe").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `a card still reading shows no meal`() {
        draw(CuisineHomeUiState())

        compose.onAllNodesWithTag(UiTags.HOME_CUISINE_SLOT).assertCountEquals(0)
        compose.onAllNodesWithTag(UiTags.HOME_CUISINE_ERROR).assertCountEquals(0)
    }

    @Test
    fun `a failed read says so and « Réessayer » reads again`() {
        var retried = 0
        draw(CuisineHomeUiState(state = null, isLoading = false, failed = true), onRetry = { retried++ })

        compose.onNodeWithTag(UiTags.HOME_CUISINE_ERROR).assertIsDisplayed()
        compose.onNodeWithText("Réessayer").performClick()

        assertEquals(1, retried)
    }
}
