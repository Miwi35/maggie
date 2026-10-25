package com.maggie.app.ui.screens.cookbook.cooking

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.data.model.StepIngredient
import com.maggie.app.data.model.Utensil
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityImmersion
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        ready = { actionsLog += "ready" },
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
    fun `the last step of the last recipe offers Repas servi`() {
        draw(state(session(step = 2, recipeIndex = 2)))

        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertTextEquals("Repas servi")
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

    // --- The step by step (MAG-581)

    private val flourLine = RecipeIngredient(
        id = "l1",
        ingredient = JsonObject(mapOf("id" to JsonPrimitive("i-flour"))),
        ingredientName = "Farine",
        quantity = 300f,
        unit = CookbookUnit.G,
    )

    /** The reference example: water (10 min, background), cut, pasta (12 min ← 1), vegetables (8 min), drain (← 3 and 4). */
    private val reference = Recipe(
        id = "r-a",
        name = "Pâtes aux légumes",
        servings = 4,
        ingredients = listOf(flourLine),
        steps = listOf(
            RecipeStep(position = 1, text = "Mettre l'eau à bouillir", durationMinutes = 10, background = true, utensils = listOf(Utensil("u1", "Casserole"))),
            RecipeStep(position = 2, text = "Couper les légumes"),
            RecipeStep(
                position = 3,
                text = "Cuire les pâtes",
                durationMinutes = 12,
                background = true,
                ingredients = listOf(StepIngredient("i-flour", "Farine")),
                waitsFor = listOf(1),
            ),
            RecipeStep(position = 4, text = "Faire revenir les légumes", durationMinutes = 8),
            RecipeStep(position = 5, text = "Égoutter, mélanger", waitsFor = listOf(3, 4)),
        ),
    )

    private fun referenceState(step: Int, ready: List<Int> = emptyList(), lastRecipe: Boolean = false) = CookingUiState(
        load = CookingLoad.READY,
        session = mealSession("meal-1", if (lastRecipe) listOf("r-a") else listOf("r-a", "r-b"), 4).copy(step = step, ready = ready),
        recipes = mapOf("r-a" to reference, "r-b" to recipe("r-b", "Salade verte")),
    )

    @Test
    fun `a step shows its number, its text, what it needs and what comes next`() {
        draw(referenceState(step = 1))

        compose.onNodeWithTag(UiTags.COOKING_STEP_NUMBER).assertTextEquals("1")
        compose.onNodeWithTag(UiTags.COOKING_STEP_TEXT).assertTextEquals("Mettre l'eau à bouillir")
        compose.onNodeWithTag(UiTags.COOKING_STEP_UTENSIL).assertTextEquals("Casserole")
        compose.onNodeWithTag(UiTags.COOKING_UPNEXT).assertTextEquals("Ensuite · Couper les légumes")
        compose.onAllNodesWithTag(UiTags.COOKING_WAIT).assertCountEquals(0)
        compose.onNodeWithText("Recette 1 sur 2 · Étape 1 sur 5").assertIsDisplayed()
    }

    @Test
    fun `the ingredients of a step come with their scaled quantity`() {
        draw(referenceState(step = 3, ready = listOf(1)))

        compose.onNodeWithTag(UiTags.COOKING_STEP_INGREDIENT).assertTextEquals("300 g Farine")
    }

    @Test
    fun `the segments tell the steps done, the current one and those to come`() {
        draw(referenceState(step = 3))

        compose.onAllNodesWithTag(UiTags.COOKING_SEGMENT).assertCountEquals(5)
        compose.onAllNodesWithTag(UiTags.COOKING_SEGMENT)[0].assertContentDescriptionEquals("Étape 1 : faite")
        compose.onAllNodesWithTag(UiTags.COOKING_SEGMENT)[2].assertContentDescriptionEquals("Étape 3 : en cours")
        compose.onAllNodesWithTag(UiTags.COOKING_SEGMENT)[4].assertContentDescriptionEquals("Étape 5 : à venir")
    }

    @Test
    fun `step 3 waits for the water, holds Suivant, and Cest pret lifts it`() {
        draw(referenceState(step = 3))

        compose.onNodeWithTag(UiTags.COOKING_WAIT).assertIsDisplayed()
        compose.onNodeWithText("Attend l'eau à bouillir · étape 1").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertIsNotEnabled()

        compose.onNodeWithTag(UiTags.COOKING_READY).performClick()

        assertEquals(listOf("ready"), actionsLog)
    }

    @Test
    fun `step 5 waits for the pasta and the vegetables`() {
        draw(referenceState(step = 5, ready = listOf(1)))

        compose.onNodeWithText("Attend les pâtes et les légumes · étapes 3 et 4").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertIsNotEnabled()
    }

    @Test
    fun `once forced, the wait is gone and Suivant is back`() {
        draw(referenceState(step = 3, ready = listOf(1)))

        compose.onAllNodesWithTag(UiTags.COOKING_WAIT).assertCountEquals(0)
        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertIsEnabled()
    }

    @Test
    fun `a swipe to the left goes on and a swipe to the right goes back`() {
        draw(referenceState(step = 2))

        compose.onNodeWithTag(UiTags.COOKING_STEP).performTouchInput { swipeLeft() }
        compose.onNodeWithTag(UiTags.COOKING_STEP).performTouchInput { swipeRight() }

        assertEquals(listOf("next", "previous"), actionsLog)
    }

    @Test
    fun `a swipe to the left does not go on while the step waits`() {
        draw(referenceState(step = 3))

        compose.onNodeWithTag(UiTags.COOKING_STEP).performTouchInput { swipeLeft() }
        compose.onNodeWithTag(UiTags.COOKING_STEP).performTouchInput { swipeRight() }

        assertEquals(listOf("previous"), actionsLog)
    }

    @Test
    fun `a short drag is not a swipe`() {
        draw(referenceState(step = 2))

        compose.onNodeWithTag(UiTags.COOKING_STEP).performTouchInput {
            swipe(start = center, end = center.copy(x = center.x - 20f))
        }

        assertEquals(emptyList<String>(), actionsLog)
    }

    @Test
    fun `the last step of the last recipe reads Repas servi`() {
        draw(referenceState(step = 5, ready = listOf(1, 3, 4), lastRecipe = true))

        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertTextEquals("Repas servi")
        compose.onAllNodesWithTag(UiTags.COOKING_UPNEXT).assertCountEquals(0)
    }

    @Test
    fun `the last step of a recipe that is not the last still reads Suivant`() {
        draw(referenceState(step = 5, ready = listOf(1, 3, 4)))

        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertTextEquals("Suivant →")
    }

    @Test
    fun `Bon appetit says how long it took and for how many`() {
        val done = mealSession("meal-1", listOf("r-a"), 4, now = 0L).copy(phase = CookingPhase.DONE, finishedAt = 47 * 60_000L)
        draw(referenceState(step = 0).copy(session = done))

        compose.onNodeWithTag(UiTags.COOKING_DONE_SUMMARY).assertTextEquals("47 min en cuisine · 4 convives")
    }

    @Test
    fun `the main buttons of a step are at least 56 dp on a phone`() {
        draw(referenceState(step = 2))

        compose.onNodeWithTag(UiTags.COOKING_PREVIOUS).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertHeightIsAtLeast(56.dp)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `the main buttons of a step are at least 64 dp on a tablet`() {
        draw(referenceState(step = 3))

        compose.onNodeWithTag(UiTags.COOKING_PREVIOUS).assertHeightIsAtLeast(64.dp)
        compose.onNodeWithTag(UiTags.COOKING_READY).assertHeightIsAtLeast(64.dp)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `a tablet in portrait shows the whole step`() {
        draw(referenceState(step = 3))

        compose.onNodeWithTag(UiTags.COOKING_STEP_NUMBER).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_WAIT).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun `a phone in landscape keeps the buttons and the text on screen`() {
        draw(referenceState(step = 2))

        compose.onNodeWithTag(UiTags.COOKING_STEP_TEXT).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertIsDisplayed()
    }

    @Test
    fun `the number and the text grow with the format`() {
        assertEquals(StepSizes(72.sp, 28.sp), stepSizes(411))
        assertEquals(StepSizes(104.sp, 40.sp), stepSizes(600))
        assertEquals(StepSizes(120.sp, 46.sp), stepSizes(900))
    }
}
