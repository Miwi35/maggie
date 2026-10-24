package com.maggie.app.ui.screens.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.GroceryHomeLine
import com.maggie.app.data.model.GroceryHomeRecipeNeed
import com.maggie.app.data.model.GroceryHomeState
import com.maggie.app.data.model.GroceryHomeStore
import com.maggie.app.data.model.Need
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Courses card (MAG-563): the list by shop in five lines and « + n », the recipes' line above, nothing to tick. */
@RunWith(AndroidJUnit4::class)
class GroceryHomeCardScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun lines(count: Int) = (1..count).map { GroceryHomeLine(id = "i$it", name = "Article $it", quantity = 1f, unit = "piece") }

    private fun store(name: String?, shown: Int, more: Int = 0) =
        GroceryHomeStore(id = name?.lowercase(), name = name, count = shown + more, items = lines(shown), more = more)

    private fun draw(ui: GroceryHomeUiState, onOpen: () -> Unit = {}, onRetry: () -> Unit = {}) =
        compose.setContent { GroceryHomeCardContent(ui, onOpen, onRetry) }

    @Test
    fun `a shop draws its five lines and the rest as « + 1 »`() {
        draw(GroceryHomeUiState(GroceryHomeState(count = 6, stores = listOf(store("Supermarché", 5, more = 1))), isLoading = false))

        compose.onNodeWithText("Courses").assertIsDisplayed()
        compose.onNodeWithText("Supermarché").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.HOME_GROCERY_ITEM).assertCountEquals(5)
        compose.onNodeWithTag(UiTags.HOME_GROCERY_MORE).assertIsDisplayed()
        compose.onNodeWithText("+ 1").assertIsDisplayed()
    }

    @Test
    fun `no « + n » when the shop's lines all fit`() {
        draw(GroceryHomeUiState(GroceryHomeState(count = 3, stores = listOf(store("Supermarché", 3))), isLoading = false))

        compose.onAllNodesWithTag(UiTags.HOME_GROCERY_MORE).assertCountEquals(0)
    }

    @Test
    fun `each shop gets its own block, and lines with no shop sit under « Non assigné »`() {
        draw(
            GroceryHomeUiState(
                GroceryHomeState(count = 5, stores = listOf(store("Primeur", 2), store("Supermarché", 2), store(null, 1))),
                isLoading = false,
            ),
        )

        compose.onAllNodesWithTag(UiTags.HOME_GROCERY_STORE).assertCountEquals(3)
        compose.onNodeWithText("Non assigné").assertIsDisplayed()
    }

    @Test
    fun `a line says what the meals need, else its own quantity`() {
        val state = GroceryHomeState(
            count = 2,
            stores = listOf(
                GroceryHomeStore(
                    id = "s", name = "Supermarché", count = 2,
                    items = listOf(
                        GroceryHomeLine("a", "Farine", quantity = 1f, unit = "kg", needed = listOf(Need(800.0, "g"))),
                        GroceryHomeLine("b", "Piles", quantity = 4f, unit = "piece"),
                    ),
                ),
            ),
        )
        draw(GroceryHomeUiState(state, isLoading = false))

        compose.onNodeWithText("800 g").assertIsDisplayed()
        compose.onNodeWithText("4 pièces").assertIsDisplayed()
    }

    @Test
    fun `what the meals lack heads the card, before the shops`() {
        val state = GroceryHomeState(
            count = 1,
            forRecipes = listOf(GroceryHomeRecipeNeed("Parmesan", "today", "dinner"), GroceryHomeRecipeNeed("Crème fraîche", "tomorrow", "lunch")),
            stores = listOf(store("Supermarché", 1)),
        )
        draw(GroceryHomeUiState(state, isLoading = false))

        compose.onNodeWithTag(UiTags.HOME_GROCERY_RECIPES).assertIsDisplayed()
        compose.onNodeWithText("Pour les recettes · parmesan (ce soir), crème fraîche (demain)").assertIsDisplayed()
    }

    @Test
    fun `an empty list and nothing the meals lack draws nothing`() {
        draw(GroceryHomeUiState(GroceryHomeState(), isLoading = false))

        compose.onNodeWithTag(UiTags.homeCard(HomeModules.GROCERY)).assertDoesNotExist()
        compose.onNodeWithText("Courses").assertDoesNotExist()
    }

    @Test
    fun `what the meals lack is enough to draw the card, with an empty list`() {
        draw(GroceryHomeUiState(GroceryHomeState(forRecipes = listOf(GroceryHomeRecipeNeed("Parmesan", "today", "dinner"))), isLoading = false))

        compose.onNodeWithTag(UiTags.homeCard(HomeModules.GROCERY)).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_GROCERY_RECIPES).assertIsDisplayed()
    }

    @Test
    fun `a failed read keeps the card, with its error`() {
        draw(GroceryHomeUiState(isLoading = false, failed = true))

        compose.onNodeWithTag(UiTags.homeCard(HomeModules.GROCERY)).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_GROCERY_ERROR).assertIsDisplayed()
    }

    @Test
    fun `a tap on the card or on a line opens Courses`() {
        var opened = 0
        draw(GroceryHomeUiState(GroceryHomeState(count = 1, stores = listOf(store("Supermarché", 1))), isLoading = false), onOpen = { opened++ })

        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.GROCERY)).performClick()
        compose.onNodeWithText("Article 1").performClick()

        assertEquals(2, opened)
    }

    @Test
    fun `the card has no tick box, no button and no suggestion`() {
        draw(GroceryHomeUiState(GroceryHomeState(count = 2, stores = listOf(store("Supermarché", 2))), isLoading = false))

        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onAllNodesWithTag(UiTags.HOME_GROCERY_RETRY).assertCountEquals(0)
        compose.onAllNodesWithText("tu en prends souvent", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test
    fun `a failed read says so and « Réessayer » reads again`() {
        var retried = 0
        draw(GroceryHomeUiState(state = null, isLoading = false, failed = true), onRetry = { retried++ })

        compose.onNodeWithTag(UiTags.HOME_GROCERY_ERROR).assertIsDisplayed()
        compose.onNodeWithText("Réessayer").performClick()

        assertEquals(1, retried)
    }

    @Test
    fun `quantityText is null with no need and no quantity`() {
        assertNull(GroceryHomeLine("x", "Sel").quantityText())
    }
}
