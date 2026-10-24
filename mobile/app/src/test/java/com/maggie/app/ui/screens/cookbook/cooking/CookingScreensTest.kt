package com.maggie.app.ui.screens.cookbook.cooking

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.data.model.Utensil
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityImmersion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The screens of the Cuisine activity (MAG-580): preparation, « Pour combien ? », steps, and the states between. */
@RunWith(AndroidJUnit4::class)
class CookingScreensTest {

    @get:Rule
    val compose = ScreenRule()

    private object NoImmersion : ActivityImmersion {
        override fun enter() = Unit

        override fun exit() = Unit
    }

    private val actionsLog = mutableListOf<String>()
    private var quits = 0

    private val actions = CookingActions(
        setGuests = { actionsLog += "guests:$it" },
        confirmServings = { actionsLog += "confirm" },
        previous = { actionsLog += "previous" },
        next = { actionsLog += "next" },
        skipRecipe = { actionsLog += "skip" },
        retry = { actionsLog += "retry" },
    )

    private fun recipe(id: String, name: String, steps: Int = 2) = Recipe(
        id = id,
        name = name,
        servings = 4,
        ingredients = listOf(RecipeIngredient(ingredientName = "Farine", quantity = 200f, unit = CookbookUnit.G)),
        utensils = listOf(Utensil("u1", "Fouet")),
        steps = (1..steps).map { RecipeStep(position = it, text = "Faire l'étape $it de $name") },
    )

    private val pasta = recipe("r-a", "Pâtes")
    private val salad = recipe("r-b", "Salade verte")
    private val tart = recipe("r-c", "Tarte")

    private fun session(step: Int = 0, recipeIndex: Int = 0, guests: Int = 4) =
        mealSession("meal-1", listOf("r-a", "r-b", "r-c"), guests).copy(step = step, recipeIndex = recipeIndex)

    private fun state(
        at: CookingSession? = session(),
        load: CookingLoad = CookingLoad.READY,
        refreshing: Boolean = false,
    ) = CookingUiState(
        load = load,
        session = at,
        recipes = listOf(pasta, salad, tart).associateBy { it.id },
        refreshing = refreshing,
    )

    private fun draw(state: CookingUiState) = compose.setContent {
        CookingActivityContent(
            state = state,
            actions = actions,
            onQuit = { quits++ },
            onOpenMaggie = {},
            immersion = NoImmersion,
        )
    }

    @Test
    fun `the preparation lists what to take out, the guests and the recipes in order, the current one marked`() {
        draw(state(session(recipeIndex = 1)))

        compose.onNodeWithTag(UiTags.COOKING_PREPARATION).assertIsDisplayed()
        compose.onNodeWithText("On sort tout").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_GUESTS_VALUE).assertTextEquals("4")
        compose.onNodeWithText("Farine").assertExists()
        compose.onNodeWithText("Fouet").assertExists()
        compose.onNodeWithTag(UiTags.COOKING_QUEUE_CURRENT).assertExists()
        compose.onAllNodesWithTag(UiTags.COOKING_QUEUE_ITEM).assertCountEquals(2)
        compose.onNodeWithText("Pâtes").assertExists()
        compose.onNodeWithText("Tarte").assertExists()
        compose.onNodeWithText("Recette 2 sur 3 · Préparation").assertIsDisplayed()
    }

    @Test
    fun `C'est parti opens the first step`() {
        draw(state())

        compose.onNodeWithTag(UiTags.COOKING_START).performClick()

        assertEquals(listOf("next"), actionsLog)
    }

    @Test
    fun `the guests of the preparation change by one`() {
        draw(state())

        compose.onNodeWithTag(UiTags.COOKING_GUESTS_MINUS).performClick()
        compose.onNodeWithTag(UiTags.COOKING_GUESTS_PLUS).performClick()

        assertEquals(listOf("guests:3", "guests:5"), actionsLog)
    }

    @Test
    fun `the guests cannot go under one`() {
        draw(state(session(guests = 1)))

        compose.onNodeWithTag(UiTags.COOKING_GUESTS_MINUS).assertIsNotEnabled()
    }

    @Test
    fun `C'est parti waits while the quantities are on their way`() {
        draw(state(refreshing = true))

        compose.onNodeWithTag(UiTags.COOKING_START).assertIsNotEnabled()
    }

    @Test
    fun `a step shows its text and moves with Precedent and Suivant`() {
        draw(state(session(step = 1)))

        compose.onNodeWithTag(UiTags.COOKING_STEP).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_STEP_TEXT).assertTextEquals("Faire l'étape 1 de Pâtes")
        compose.onNodeWithText("Recette 1 sur 3 · Étape 1 sur 2").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.COOKING_PREVIOUS).performClick()
        compose.onNodeWithTag(UiTags.COOKING_NEXT).performClick()

        assertEquals(listOf("previous", "next"), actionsLog)
    }

    @Test
    fun `the last step of the last recipe offers Terminer`() {
        draw(state(session(step = 2, recipeIndex = 2)))

        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertTextEquals("Terminer")
    }

    @Test
    fun `Pour combien is filled with the recipe's servings and Continuer goes on`() {
        draw(state(recipeSession("r-a", 4)))

        compose.onNodeWithTag(UiTags.COOKING_SERVINGS).assertIsDisplayed()
        compose.onNodeWithText("Pour combien ?").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_SERVINGS_VALUE).assertTextEquals("4")

        compose.onNodeWithTag(UiTags.COOKING_SERVINGS_MINUS).performClick()
        compose.onNodeWithTag(UiTags.COOKING_CONTINUE).performClick()

        assertEquals(listOf("guests:3", "confirm"), actionsLog)
    }

    @Test
    fun `a recipe with no step says so and can be passed over`() {
        draw(
            CookingUiState(
                load = CookingLoad.READY,
                session = session(),
                recipes = mapOf("r-a" to recipe("r-a", "Pâtes", steps = 0)),
            ),
        )

        compose.onNodeWithTag(UiTags.COOKING_NO_STEPS).assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.COOKING_START).assertCountEquals(0)

        compose.onNodeWithTag(UiTags.COOKING_SKIP).performClick()

        assertEquals(listOf("skip"), actionsLog)
    }

    @Test
    fun `after the last recipe, Bon appetit leaves`() {
        draw(state(session().copy(phase = CookingPhase.DONE)))

        compose.onNodeWithText("Bon appétit").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_FINISH).performClick()

        assertEquals(1, quits)
    }

    @Test
    fun `loading shows a spinner`() {
        draw(CookingUiState(load = CookingLoad.LOADING))

        compose.onNodeWithTag(UiTags.COOKING_LOADING).assertIsDisplayed()
    }

    @Test
    fun `a meal with no recipe says so and goes back`() {
        draw(CookingUiState(load = CookingLoad.EMPTY))

        compose.onNodeWithTag(UiTags.COOKING_EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_EMPTY_BACK).performClick()

        assertEquals(1, quits)
    }

    @Test
    fun `a network error offers Reessayer and does not close the activity`() {
        draw(CookingUiState(load = CookingLoad.ERROR))

        compose.onNodeWithTag(UiTags.COOKING_ERROR).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_RETRY).performClick()

        assertEquals(listOf("retry"), actionsLog)
        assertEquals(0, quits)
    }

    @Test
    fun `the main buttons are at least 56 dp on a phone`() {
        draw(state())

        compose.onNodeWithTag(UiTags.COOKING_START).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(UiTags.COOKING_GUESTS_PLUS).assertHeightIsAtLeast(56.dp)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `the main buttons are at least 64 dp on a tablet`() {
        draw(state())

        compose.onNodeWithTag(UiTags.COOKING_START).assertHeightIsAtLeast(64.dp)
        compose.onNodeWithTag(UiTags.COOKING_GUESTS_PLUS).assertHeightIsAtLeast(64.dp)
    }

    @Test
    fun `the main button height follows the smallest width`() {
        assertEquals(56.dp, mainButtonHeight(411))
        assertEquals(64.dp, mainButtonHeight(600))
        assertEquals(64.dp, mainButtonHeight(800))
    }
}
