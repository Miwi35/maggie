package com.maggie.app.ui.screens.cookbook.cooking

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.CookingTimer
import com.maggie.app.data.model.CookingTimerStatus
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
import org.junit.Assert.assertTrue
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
        resumeInProgress = { actionsLog += "resume" },
        replaceInProgress = { actionsLog += "replace" },
        restart = { actionsLog += "restart" },
        startAndNext = { actionsLog += "start-and-next" },
        toggleTimer = { actionsLog += "toggle" },
        addMinutes = { actionsLog += "add:$it" },
        openStep = { recipeIndex, position -> actionsLog += "open:$recipeIndex:$position" },
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

    private fun referenceState(
        step: Int,
        done: Map<String, List<Int>> = emptyMap(),
        lastRecipe: Boolean = false,
        recipeIndex: Int = 0,
    ) = CookingUiState(
        load = CookingLoad.READY,
        session = mealSession("meal-1", if (lastRecipe) listOf("r-a") else listOf("r-a", "r-b"), 4)
            .copy(step = step, recipeIndex = recipeIndex, doneSteps = done),
        recipes = mapOf("r-a" to reference, "r-b" to recipe("r-b", "Salade verte")),
    )

    @Test
    fun `a step shows its number, its text, what it needs and what comes next`() {
        draw(referenceState(step = 1))

        compose.onNodeWithTag(UiTags.COOKING_STEP_NUMBER).assertTextEquals("1")
        compose.onNodeWithTag(UiTags.COOKING_STEP_TEXT).assertTextEquals("Mettre l'eau à bouillir")
        compose.onNodeWithTag(UiTags.COOKING_STEP_UTENSIL).assertTextEquals("Casserole")
        compose.onNodeWithTag(UiTags.COOKING_UPNEXT).assertTextEquals("Ensuite · Couper les légumes")
        compose.onNodeWithText("Recette 1 sur 2 · Étape 1 sur 5").assertIsDisplayed()
    }

    @Test
    fun `the ingredients of a step come with their scaled quantity`() {
        draw(referenceState(step = 3))

        compose.onNodeWithTag(UiTags.COOKING_STEP_INGREDIENT).assertTextEquals("300 g Farine")
    }

    @Test
    fun `the segments tell the steps done, the current one and those to come`() {
        draw(referenceState(step = 3, done = mapOf("r-a" to listOf(1, 2))))

        compose.onAllNodesWithTag(UiTags.COOKING_SEGMENT).assertCountEquals(5)
        compose.onAllNodesWithTag(UiTags.COOKING_SEGMENT)[0].assertContentDescriptionEquals("Étape 1 : faite")
        compose.onAllNodesWithTag(UiTags.COOKING_SEGMENT)[2].assertContentDescriptionEquals("Étape 3 : en cours")
        compose.onAllNodesWithTag(UiTags.COOKING_SEGMENT)[4].assertContentDescriptionEquals("Étape 5 : à venir")
    }

    @Test
    fun `Suivant stays enabled on a step that awaits another, nothing holds it back`() {
        draw(referenceState(step = 5))

        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertIsEnabled()
        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertTextEquals("Suivant →")
        compose.onNodeWithTag(UiTags.COOKING_NEXT).performClick()

        assertEquals(listOf("next"), actionsLog)
    }

    @Test
    fun `Suivant stays enabled on the step that awaits two timers, one of them still running`() {
        draw(timedState(step = 5, water, timer("t-3", "Pâtes", 3, CookingTimerStatus.RUNNING, 552)))

        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertIsEnabled()
        compose.onNodeWithTag(UiTags.COOKING_NEXT).performClick()

        assertEquals(listOf("next"), actionsLog)
    }

    @Test
    fun `no state of the screen offers a button to say a step is ready`() {
        draw(timedState(step = 3, timer("t-1", "Eau", 1, CookingTimerStatus.RUNNING, 130)))

        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertIsEnabled()
        compose.onAllNodesWithText("C'est prêt").assertCountEquals(0)
        compose.onAllNodesWithText("OK").assertCountEquals(0)
    }

    @Test
    fun `a swipe to the left goes on and a swipe to the right goes back`() {
        draw(referenceState(step = 2))

        compose.onNodeWithTag(UiTags.COOKING_STEP).performTouchInput { swipeLeft() }
        compose.onNodeWithTag(UiTags.COOKING_STEP).performTouchInput { swipeRight() }

        assertEquals(listOf("next", "previous"), actionsLog)
    }

    @Test
    fun `a swipe to the left goes on from a step that awaits another too`() {
        draw(referenceState(step = 5))

        compose.onNodeWithTag(UiTags.COOKING_STEP).performTouchInput { swipeLeft() }
        compose.onNodeWithTag(UiTags.COOKING_STEP).performTouchInput { swipeRight() }

        assertEquals(listOf("next", "previous"), actionsLog)
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
        draw(referenceState(step = 5, done = mapOf("r-a" to listOf(1, 2, 3, 4)), lastRecipe = true))

        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertTextEquals("Repas servi")
        compose.onAllNodesWithTag(UiTags.COOKING_UPNEXT).assertCountEquals(0)
    }

    @Test
    fun `the last step of a recipe that is not the last still reads Suivant`() {
        draw(referenceState(step = 5, done = mapOf("r-a" to listOf(1, 2, 3, 4))))

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
        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertHeightIsAtLeast(64.dp)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `a tablet in portrait shows the whole step`() {
        draw(referenceState(step = 3))

        compose.onNodeWithTag(UiTags.COOKING_STEP_NUMBER).assertIsDisplayed()
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

    // --- The timers (MAG-582)

    private val clock = 5_000_000L

    private fun timer(id: String, label: String, step: Int, status: CookingTimerStatus, rest: Int, finishedAt: String? = null) =
        CookingTimer(
            id = id,
            label = label,
            recipe = "/api/recipes/r-a",
            stepPosition = step,
            durationSeconds = 600,
            remainingSeconds = rest,
            status = status,
            finishedAt = finishedAt,
        )

    private fun timedState(step: Int, vararg timers: CookingTimer, done: Map<String, List<Int>> = emptyMap()) = referenceState(step, done).copy(
        timers = timers.toList(),
        deadlines = timers.filter { it.status == CookingTimerStatus.RUNNING }.associate { it.id to clock + it.remainingSeconds * 1000L },
        now = clock,
    )

    private val water = timer("t-1", "Eau", 1, CookingTimerStatus.RUNNING, 552)

    @Test
    fun `a background step offers Lancer et continuer, its timer full and waiting`() {
        draw(timedState(step = 1))

        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertTextEquals("Lancer et continuer")
        compose.onNodeWithTag(UiTags.COOKING_STEP_TIMER_CLOCK).assertTextEquals("10:00")
        compose.onNodeWithText("Eau").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_STEP_TIMER_SUB).assertTextEquals("en fond : passe à la suite")
        compose.onAllNodesWithTag(UiTags.COOKING_TIMER_TOGGLE).assertCountEquals(0)

        compose.onNodeWithTag(UiTags.COOKING_NEXT).performClick()

        assertEquals(listOf("start-and-next"), actionsLog)
    }

    @Test
    fun `a background step whose timer was acknowledged reads Suivant, not Lancer et continuer`() {
        draw(timedState(step = 1).copy(launched = setOf("r-a" to 1)))

        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertTextEquals("Suivant →")
        compose.onNodeWithTag(UiTags.COOKING_NEXT).performClick()

        assertEquals(listOf("next"), actionsLog)
    }

    @Test
    fun `a swipe on a background step starts its timer too`() {
        draw(timedState(step = 1))

        compose.onNodeWithTag(UiTags.COOKING_STEP).performTouchInput { swipeLeft() }

        assertEquals(listOf("start-and-next"), actionsLog)
    }

    @Test
    fun `once its timer runs, the background step reads Suivant and takes -1, Pause and +1`() {
        draw(timedState(step = 1, water))

        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertTextEquals("Suivant →")
        compose.onNodeWithTag(UiTags.COOKING_STEP_TIMER_CLOCK).assertTextEquals("09:12")
        compose.onNodeWithTag(UiTags.COOKING_TIMER_TOGGLE).assertTextEquals("Pause")

        compose.onNodeWithTag(UiTags.COOKING_TIMER_MINUS).performClick()
        compose.onNodeWithTag(UiTags.COOKING_TIMER_TOGGLE).performClick()
        compose.onNodeWithTag(UiTags.COOKING_TIMER_PLUS).performClick()
        compose.onNodeWithTag(UiTags.COOKING_NEXT).performClick()

        assertEquals(listOf("add:-1", "toggle", "add:1", "next"), actionsLog)
    }

    @Test
    fun `a timed step that is not in the background has Demarrer, and Suivant as its main button`() {
        draw(timedState(step = 4))

        compose.onNodeWithTag(UiTags.COOKING_TIMER_TOGGLE).assertTextEquals("Démarrer")
        compose.onNodeWithTag(UiTags.COOKING_TIMER_MINUS).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertTextEquals("Suivant →")
        compose.onNodeWithTag(UiTags.COOKING_STEP_TIMER_SUB).assertTextEquals("à lancer")

        compose.onNodeWithTag(UiTags.COOKING_TIMER_TOGGLE).performClick()

        assertEquals(listOf("toggle"), actionsLog)
    }

    @Test
    fun `a paused timer offers Reprendre and keeps its rest`() {
        draw(timedState(step = 4, timer("t-4", "Légumes", 4, CookingTimerStatus.PAUSED, 300)))

        compose.onNodeWithTag(UiTags.COOKING_TIMER_TOGGLE).assertTextEquals("Reprendre")
        compose.onNodeWithTag(UiTags.COOKING_STEP_TIMER_CLOCK).assertTextEquals("05:00")
        compose.onNodeWithTag(UiTags.COOKING_STEP_TIMER_SUB).assertTextEquals("en pause")
    }

    // --- The steps of the meal (MAG-614)

    private val landscape = "w800dp-h360dp-land-xhdpi"
    private val tablet = "w800dp-h1280dp-xhdpi"

    private fun stateOf(vararg expected: String) =
        expected.map { SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, it) }

    @Test
    fun `a phone upright shows the banner with its progress, and no column`() {
        draw(timedState(step = 2, done = mapOf("r-a" to listOf(1))))

        compose.onNodeWithTag(UiTags.COOKING_STEPS_BANNER).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_STEPS_BANNER_PROGRESS).assertTextEquals("1 / 7 faites")
        compose.onAllNodesWithTag(UiTags.COOKING_STEPS_COLUMN).assertCountEquals(0)
        compose.onAllNodesWithTag(UiTags.COOKING_STEPS_BANNER_RINGING).assertCountEquals(0)
        compose.onAllNodesWithTag(UiTags.COOKING_STEP_ROW).assertCountEquals(0)
        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertIsDisplayed()
    }

    @Test
    fun `touching the banner opens the sheet with the steps of every recipe, and a row goes to its step`() {
        draw(timedState(step = 2, done = mapOf("r-a" to listOf(1))))

        compose.onNodeWithTag(UiTags.COOKING_STEPS_BANNER).performClick()

        compose.onNodeWithTag(UiTags.COOKING_STEPS_SHEET).assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.COOKING_STEP_ROW).assertCountEquals(7)
        compose.onAllNodesWithTag(UiTags.COOKING_STEPS_RECIPE).assertCountEquals(2)
        compose.onNodeWithTag(UiTags.COOKING_STEPS_PROGRESS).assertTextEquals("1 / 7 faites")

        // The row's own click action: under Robolectric a synthetic touch does not reach a bottom sheet's window
        // (the real tap does, Maestro's journey taps it on a device).
        compose.onAllNodesWithTag(UiTags.COOKING_STEP_ROW)[3].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertEquals(listOf("open:0:4"), actionsLog)
        compose.onAllNodesWithTag(UiTags.COOKING_STEPS_SHEET).assertCountEquals(0)
    }

    @Test
    fun `the banner says a timer rang, since its line is out of sight`() {
        draw(timedState(step = 4, timer("t-3", "Pâtes", 3, CookingTimerStatus.DONE, 0, "2026-10-25T10:00:00+00:00")))

        compose.onNodeWithTag(UiTags.COOKING_STEPS_BANNER_RINGING).assertTextEquals("Pâtes : c'est prêt")
    }

    @Test
    fun `the banner counts the other timers that rang`() {
        draw(
            timedState(
                step = 4,
                timer("t-1", "Eau", 1, CookingTimerStatus.DONE, 0, "2026-10-25T10:00:00+00:00"),
                timer("t-3", "Pâtes", 3, CookingTimerStatus.DONE, 0, "2026-10-25T10:01:00+00:00"),
            ),
        )

        compose.onNodeWithTag(UiTags.COOKING_STEPS_BANNER_RINGING).assertTextEquals("Eau : c'est prêt (+1)")
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun `a phone sideways shows the steps column at the left, with the step and its buttons beside it`() {
        draw(timedState(step = 3, done = mapOf("r-a" to listOf(1, 2))))

        compose.onNodeWithTag(UiTags.COOKING_STEPS_COLUMN).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_STEPS_PROGRESS).assertTextEquals("2 / 7 faites")
        compose.onAllNodesWithTag(UiTags.COOKING_STEPS_BANNER).assertCountEquals(0)
        compose.onAllNodesWithTag(UiTags.COOKING_STEP_ROW).assertCountEquals(7)
        compose.onNodeWithTag(UiTags.COOKING_STEP_TEXT).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertIsDisplayed()
        val column = compose.onNodeWithTag(UiTags.COOKING_STEPS_COLUMN).fetchSemanticsNode().boundsInRoot
        val step = compose.onNodeWithTag(UiTags.COOKING_STEP).fetchSemanticsNode().boundsInRoot
        assertTrue("the column is left of the step", column.right <= step.left + 1f)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `a tablet shows the steps column, upright too`() {
        draw(timedState(step = 3))

        compose.onNodeWithTag(UiTags.COOKING_STEPS_COLUMN).assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.COOKING_STEPS_BANNER).assertCountEquals(0)
        compose.onNodeWithTag(UiTags.COOKING_STEPS_PROGRESS).assertTextEquals("0 / 7 faites")
        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun `the column names the recipes when there are several, and lists their steps in order`() {
        draw(timedState(step = 3))

        compose.onAllNodesWithTag(UiTags.COOKING_STEPS_RECIPE).assertCountEquals(2)
        compose.onAllNodesWithTag(UiTags.COOKING_STEPS_RECIPE)[0].assertTextEquals("Pâtes aux légumes")
        compose.onAllNodesWithTag(UiTags.COOKING_STEPS_RECIPE)[1].assertTextEquals("Salade verte")
        compose.onAllNodesWithTag(UiTags.COOKING_STEP_ROW)[0].assert(hasAnyDescendant(hasText("Mettre l'eau à bouillir")))
        compose.onAllNodesWithTag(UiTags.COOKING_STEP_ROW)[5].assert(hasAnyDescendant(hasText("Faire l'étape 1 de Salade verte")))
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun `a step done carries a check, the one on screen is marked, the others are to come`() {
        draw(timedState(step = 3, done = mapOf("r-a" to listOf(1, 2))))

        compose.onAllNodesWithTag(UiTags.COOKING_STEP_CHECK).assertCountEquals(2)
        compose.onAllNodesWithTag(UiTags.COOKING_STEP_CURRENT).assertCountEquals(1)
        val rows = compose.onAllNodesWithTag(UiTags.COOKING_STEP_ROW)
        rows[0].assert(hasAnyDescendant(hasTestTag(UiTags.COOKING_STEP_CHECK)))
        rows[1].assert(hasAnyDescendant(hasTestTag(UiTags.COOKING_STEP_CHECK)))
        rows[2].assert(hasAnyDescendant(hasTestTag(UiTags.COOKING_STEP_CURRENT)))
        rows[3].assert(!hasAnyDescendant(hasTestTag(UiTags.COOKING_STEP_CHECK)) and !hasAnyDescendant(hasTestTag(UiTags.COOKING_STEP_CURRENT)))
        stateOf("faite", "faite", "en cours", "à venir", "à venir").forEachIndexed { index, matcher -> rows[index].assert(matcher) }
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun `the step on screen is marked in its own recipe only`() {
        draw(referenceState(step = 1, recipeIndex = 1))

        compose.onAllNodesWithTag(UiTags.COOKING_STEP_CURRENT).assertCountEquals(1)
        val rows = compose.onAllNodesWithTag(UiTags.COOKING_STEP_ROW)
        rows[0].assert(stateOf("à venir").single())
        rows[5].assert(stateOf("en cours").single())
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun `touching a row goes to its step, in its recipe`() {
        draw(timedState(step = 3))

        compose.onAllNodesWithTag(UiTags.COOKING_STEP_ROW)[3].performScrollTo().performClick()
        compose.onAllNodesWithTag(UiTags.COOKING_STEP_ROW)[5].performScrollTo().performClick()

        assertEquals(listOf("open:0:4", "open:1:1"), actionsLog)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun `a timer sits on its step's line with its rest, several at once`() {
        draw(timedState(step = 4, water, timer("t-3", "Pâtes", 3, CookingTimerStatus.RUNNING, 130)))

        val rows = compose.onAllNodesWithTag(UiTags.COOKING_STEP_ROW)
        rows[0].assert(hasAnyDescendant(hasTestTag(UiTags.COOKING_TIMER_ROW_CLOCK) and hasText("09:12")))
        rows[2].assert(hasAnyDescendant(hasTestTag(UiTags.COOKING_TIMER_ROW_CLOCK) and hasText("02:10")))
        rows[1].assert(!hasAnyDescendant(hasTestTag(UiTags.COOKING_TIMER_ROW_CLOCK)))
        compose.onAllNodesWithTag(UiTags.COOKING_TIMER_ROW_CLOCK).assertCountEquals(2)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun `a paused timer says so on its line and keeps its rest`() {
        draw(timedState(step = 2, timer("t-1", "Eau", 1, CookingTimerStatus.PAUSED, 300)))

        compose.onNodeWithTag(UiTags.COOKING_TIMER_ROW_CLOCK).assertTextEquals("05:00")
        compose.onAllNodesWithTag(UiTags.COOKING_STEP_ROW)[0].assert(hasAnyDescendant(hasText("en pause")))
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun `a timer that rang lights its line, says it is ready, and opens no modal`() {
        draw(timedState(step = 4, timer("t-3", "Pâtes", 3, CookingTimerStatus.DONE, 0, "2026-10-25T10:00:00+00:00")))

        compose.onNodeWithTag(UiTags.COOKING_STEP_RINGING).assertTextEquals("c'est prêt")
        val rows = compose.onAllNodesWithTag(UiTags.COOKING_STEP_ROW)
        rows[2].assert(hasAnyDescendant(hasTestTag(UiTags.COOKING_STEP_RINGING)))
        rows[2].assert(stateOf("à venir, minuteur terminé").single())
        rows[1].assert(!hasAnyDescendant(hasTestTag(UiTags.COOKING_STEP_RINGING)))
        compose.onAllNodesWithText("OK").assertCountEquals(0)
        compose.onNodeWithTag(UiTags.COOKING_NEXT).assertIsEnabled()

        rows[2].performClick()

        assertEquals(listOf("open:0:3"), actionsLog)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `a tablet shows the rung line too`() {
        draw(timedState(step = 4, timer("t-1", "Eau", 1, CookingTimerStatus.DONE, 0, "2026-10-25T10:00:00+00:00")))

        compose.onNodeWithTag(UiTags.COOKING_STEP_RINGING).assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.COOKING_STEPS_BANNER_RINGING).assertCountEquals(0)
    }

    @Test
    fun `the steps column or banner does not show before the cooking starts`() {
        draw(state(recipeSession("r-a", 4)))

        compose.onAllNodesWithTag(UiTags.COOKING_STEPS_BANNER).assertCountEquals(0)
        compose.onAllNodesWithTag(UiTags.COOKING_STEPS_COLUMN).assertCountEquals(0)
    }

    @Test
    fun `a timer that could not start says so under the step`() {
        draw(timedState(step = 1).copy(timerError = CookingViewModel.START_FAILED))

        compose.onNodeWithTag(UiTags.COOKING_TIMER_ERROR).assertTextEquals(CookingViewModel.START_FAILED)
    }

    @Test
    fun `with timers running the cross leaves at once, asking nothing`() {
        draw(timedState(step = 4, timer("t-3", "Pâtes", 3, CookingTimerStatus.RUNNING, 552), timer("t-4", "Légumes", 4, CookingTimerStatus.PAUSED, 300)))

        compose.onNodeWithTag(UiTags.ACTIVITY_QUIT).performClick()

        compose.onAllNodesWithText("Quitter l'activité ?").assertCountEquals(0)
        assertEquals(1, quits)
        assertEquals(emptyList<String>(), actionsLog)
    }

    @Test
    fun `with no timer running the cross leaves at once`() {
        draw(timedState(step = 2))

        compose.onNodeWithTag(UiTags.ACTIVITY_QUIT).performClick()

        assertEquals(1, quits)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun `Recommencer sits at the head of the steps column and asks the restart`() {
        draw(timedState(step = 3, done = mapOf("r-a" to listOf(1, 2))))

        compose.onNodeWithTag(UiTags.COOKING_RESTART).assertIsDisplayed()
        compose.onNodeWithText("Recommencer").assertExists()
        compose.onNodeWithTag(UiTags.COOKING_RESTART).performClick()

        assertEquals(listOf("restart"), actionsLog)
    }

    @Test
    fun `Recommencer is in the sheet on a phone upright, which it closes`() {
        draw(timedState(step = 2))

        compose.onNodeWithTag(UiTags.COOKING_STEPS_BANNER).performClick()
        compose.onNodeWithTag(UiTags.COOKING_RESTART).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertEquals(listOf("restart"), actionsLog)
        compose.onAllNodesWithTag(UiTags.COOKING_STEPS_SHEET).assertCountEquals(0)
    }

    private fun conflictState(launchKind: CookingOrigin.Kind = CookingOrigin.Kind.RECIPE) = CookingUiState(
        load = CookingLoad.CONFLICT,
        conflict = CookingConflict(session(step = 3), launchKind, recipeName = "Pâtes", stepCount = 5),
    )

    @Test
    fun `another Cuisine in progress is a choice, with where it stands`() {
        draw(conflictState())

        compose.onNodeWithTag(UiTags.COOKING_CONFLICT).assertIsDisplayed()
        compose.onNodeWithText("Une cuisine est déjà en cours").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.COOKING_CONFLICT_DETAIL).assertTextEquals("Pâtes · étape 3/5")
        compose.onNodeWithTag(UiTags.COOKING_RESUME).assertTextEquals("Reprendre")
        compose.onNodeWithTag(UiTags.COOKING_REPLACE).assertTextEquals("Recommencer avec cette recette")
        compose.onAllNodesWithTag(UiTags.COOKING_STEPS_COLUMN).assertCountEquals(0)
    }

    @Test
    fun `from a meal the choice says ce repas`() {
        draw(conflictState(CookingOrigin.Kind.MEAL))

        compose.onNodeWithTag(UiTags.COOKING_REPLACE).assertTextEquals("Recommencer avec ce repas")
    }

    @Test
    fun `Reprendre and Recommencer on the choice call their actions`() {
        draw(conflictState())

        compose.onNodeWithTag(UiTags.COOKING_RESUME).performClick()
        compose.onNodeWithTag(UiTags.COOKING_REPLACE).performClick()

        assertEquals(listOf("resume", "replace"), actionsLog)
    }
}
