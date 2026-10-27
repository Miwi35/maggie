package com.maggie.app.ui.screens.cookbook.cooking

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.Need
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.model.Store
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityImmersion
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The shopping list inside the Cuisine activity (MAG-583): the cart in the header, the sheet or the panel, the states. */
@RunWith(AndroidJUnit4::class)
class CookingGroceryScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private object NoImmersion : ActivityImmersion {
        override fun enter() = Unit

        override fun exit() = Unit
    }

    private val log = mutableListOf<String>()
    private val actions = CookingGroceryActions(
        open = { log += "open" },
        close = { log += "close" },
        toggle = { log += "toggle:${it.label}" },
        retry = { log += "retry" },
        dismissMessage = { log += "dismiss" },
    )

    private val carrefour = Store(id = "s-1", name = "Carrefour", visitOrder = 1)
    private val parmesan = GroceryItem(
        id = "i-1",
        label = "Parmesan",
        product = Product(id = "p-parmesan", name = "Parmesan", category = ProductCategory.DAIRY),
        quantity = 200f,
        unit = CookbookUnit.G,
        store = carrefour,
    )
    private val bread = GroceryItem(id = "i-2", label = "Pain", store = carrefour, checked = true, position = 1)
    private val salt = GroceryItem(id = "i-3", label = "Sel", store = carrefour, position = 2)

    private fun state(
        items: List<GroceryItem> = listOf(salt, parmesan, bread),
        load: GroceryLoad = GroceryLoad.READY,
        message: String? = null,
    ) = CookingGroceryUiState(open = true, load = load, list = GroceryList("l", items), message = message)

    private val needs = mapOf("p-parmesan" to listOf(Need(300.0, "g")))

    private fun draw(state: CookingGroceryUiState, sheet: Boolean = true) = compose.setContent {
        val stores = cookingGroceryStores(state.groups, needs)
        if (sheet) CookingGrocerySheet(state, stores, actions) else CookingGroceryPanel(state, stores, actions)
    }

    @Test
    fun `the list shows the store, the meal's item first with its mark and what is missing, then the others`() {
        draw(state())

        compose.onNodeWithTag(UiTags.COOKING_GROCERY_SHEET).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_GROCERY_STORE).assertTextEquals("Carrefour")
        compose.onAllNodesWithTag(UiTags.COOKING_GROCERY_ITEM).assertCountEquals(3)
        compose.onNodeWithTag(UiTags.COOKING_GROCERY_MEAL).assertTextEquals("ce repas")
        compose.onAllNodesWithTag(UiTags.COOKING_GROCERY_MEAL).assertCountEquals(1)
        compose.onNodeWithTag(UiTags.COOKING_GROCERY_SHORT).assertTextEquals("il en faut 300 g")
        compose.onNodeWithText("Parmesan").assertIsDisplayed()
        compose.onNodeWithText("Pain").assertIsDisplayed()
        compose.onNodeWithText("Sel").assertIsDisplayed()

        val rows = compose.onAllNodesWithTag(UiTags.COOKING_GROCERY_ITEM).fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }
        val mark = compose.onNodeWithTag(UiTags.COOKING_GROCERY_MEAL).fetchSemanticsNode().boundsInRoot
        assertTrue(mark.top >= rows[0].boundsInRoot.top && mark.bottom <= rows[0].boundsInRoot.bottom)
    }

    @Test
    fun `every row and its box are made for a thumb`() {
        draw(state())

        compose.onAllNodesWithTag(UiTags.COOKING_GROCERY_CHECK).assertCountEquals(3)
        compose.onAllNodesWithTag(UiTags.COOKING_GROCERY_CHECK)[0].assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onAllNodesWithTag(UiTags.COOKING_GROCERY_ITEM)[0].assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(UiTags.COOKING_GROCERY_CLOSE).assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp)
    }

    @Test
    fun `one tap on a row ticks it`() {
        draw(state(), sheet = false)

        compose.onNodeWithText("Parmesan").performClick()

        assertEquals(listOf("toggle:Parmesan"), log)
    }

    @Test
    fun `the row's own action ticks it in the sheet too`() {
        draw(state())

        compose.onAllNodesWithTag(UiTags.COOKING_GROCERY_ITEM)[1].performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, log.size)
    }

    @Test
    fun `a bought item is shown, and nothing marks it for the meal`() {
        draw(state(listOf(bread)))

        compose.onNodeWithText("Pain").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_GROCERY_CHECK).assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.COOKING_GROCERY_MEAL).assertCountEquals(0)
    }

    @Test
    fun `an empty list says Rien à acheter`() {
        draw(state(items = emptyList()))

        compose.onNodeWithTag(UiTags.COOKING_GROCERY_EMPTY).assertTextEquals("Rien à acheter")
        compose.onAllNodesWithTag(UiTags.COOKING_GROCERY_ITEM).assertCountEquals(0)
    }

    @Test
    fun `while it loads, a spinner stands in for the list`() {
        draw(CookingGroceryUiState(open = true, load = GroceryLoad.LOADING))

        compose.onNodeWithTag(UiTags.COOKING_GROCERY_LOADING).assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.COOKING_GROCERY_EMPTY).assertCountEquals(0)
    }

    @Test
    fun `a read error offers Réessayer`() {
        draw(CookingGroceryUiState(open = true, load = GroceryLoad.ERROR))

        compose.onNodeWithTag(UiTags.COOKING_GROCERY_ERROR).assertIsDisplayed()
        // The sheet is a window of its own, out of reach of a synthetic touch under Robolectric: its own click action.
        compose.onNodeWithTag(UiTags.COOKING_GROCERY_RETRY).assertHeightIsAtLeast(48.dp).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("retry"), log)
    }

    @Test
    fun `the message of a refused tick is shown above the list`() {
        draw(state(message = "« Parmesan » n'a pas pu être coché : réessaie."))

        compose.onNodeWithTag(UiTags.COOKING_GROCERY_MESSAGE).assertTextEquals("« Parmesan » n'a pas pu être coché : réessaie.")
        compose.onAllNodesWithTag(UiTags.COOKING_GROCERY_ITEM).assertCountEquals(3)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun `held sideways the list is a panel with a close button`() {
        draw(state(), sheet = false)

        compose.onNodeWithTag(UiTags.COOKING_GROCERY_SHEET).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_GROCERY_CLOSE).performClick()

        assertEquals(listOf("close"), log)
    }

    private fun recipe(quantity: Float) = Recipe(
        id = "r-a",
        name = "Pâtes",
        ingredients = listOf(
            RecipeIngredient(
                ingredient = JsonObject(mapOf("id" to JsonPrimitive("p-parmesan"))),
                quantity = quantity,
                unit = CookbookUnit.G,
            ),
        ),
    )

    private fun drawActivity(grocery: CookingGroceryUiState) = compose.setContent {
        CookingActivityContent(
            state = CookingUiState(
                load = CookingLoad.READY,
                session = mealSession("meal-1", listOf("r-a"), 4),
                recipes = mapOf("r-a" to recipe(300f)),
            ),
            actions = CookingActions({}, {}, {}, {}, {}, {}),
            onQuit = {},
            onOpenMaggie = {},
            immersion = NoImmersion,
            grocery = grocery,
            groceryActions = actions,
        )
    }

    @Test
    fun `the header's cart shows what is left to buy and opens the list`() {
        drawActivity(state().copy(open = false))

        compose.onNodeWithTag(UiTags.COOKING_GROCERY_COUNT).assertTextEquals("2")
        compose.onNodeWithTag(UiTags.COOKING_GROCERY_BUTTON).assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp).performClick()
        compose.onAllNodesWithTag(UiTags.COOKING_GROCERY_SHEET).assertCountEquals(0)

        assertEquals(listOf("open"), log)
    }

    @Test
    fun `with everything bought the cart has no figure`() {
        drawActivity(state(listOf(bread)).copy(open = false))

        compose.onAllNodesWithTag(UiTags.COOKING_GROCERY_COUNT).assertCountEquals(0)
        compose.onNodeWithTag(UiTags.COOKING_GROCERY_BUTTON).assertIsDisplayed()
    }

    @Test
    fun `open over the activity, the list marks the meal's item from the queue's recipes`() {
        drawActivity(state())

        compose.onNodeWithTag(UiTags.COOKING_GROCERY_SHEET).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_GROCERY_MEAL).assertTextEquals("ce repas")
        compose.onAllNodesWithTag(UiTags.COOKING_GROCERY_SHORT).assertCountEquals(1)
    }
}
