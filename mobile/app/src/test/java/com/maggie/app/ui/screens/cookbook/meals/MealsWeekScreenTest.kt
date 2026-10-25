package com.maggie.app.ui.screens.cookbook.meals

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.api.MealUpdateRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealSlot
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.repository.IngredientRepository
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.RecipeRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.cookbook.recipes.SIMULATION_DEBOUNCE_MS
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Cuisine › Repas as the mock-up draws it (MAG-628): the week, one block per day, one card per meal
 * with its convives and its recipes, each recipe with its portions. What a tap does stays what it was
 * since MAG-378: a meal opens its sheet and never plans a second one.
 */
@RunWith(AndroidJUnit4::class)
class MealsWeekScreenTest {
    @get:Rule val compose = ScreenRule()

    private val monday = LocalDate.now().with(WeekFields.of(Locale.FRANCE).dayOfWeek(), 1)
    private val curry = Recipe(id = "r-curry", name = "Riz au curry", servings = 2)
    private val salad = Recipe(id = "r-salad", name = "Salade verte", servings = 2)
    private val lunch = Meal(
        id = "meal-lunch",
        summary = "Riz au curry, Salade verte",
        date = monday.toString(),
        slot = MealSlot.LUNCH,
        recipes = listOf("/api/recipes/r-curry", "/api/recipes/r-salad"),
        guests = 4,
        guestsIsDefault = false,
    )

    private lateinit var meals: MealRepository
    private lateinit var recipes: RecipeRepository

    private fun show(
        planned: List<Meal> = listOf(lunch),
        onCreateMeal: (String, String) -> Unit = { _, _ -> },
        onOpenMeal: (Meal) -> Unit = {},
        onChooseIngredients: (String) -> Unit = {},
        onLaunchRecipe: (String) -> Unit = {},
    ) {
        meals = mockk()
        coEvery { meals.getMeals(any(), any()) } returns Result.success(planned)
        coEvery { meals.updateMeal(any(), any()) } returns Result.success(lunch.copy(guests = 5))
        coEvery { meals.deleteMeal(any()) } returns Result.success(Unit)
        // The API's answer: 100 g of the recipe's own ingredient per two people, scaled to the guests asked.
        recipes = mockk()
        coEvery { recipes.getScaledRecipe(any(), any()) } answers {
            val recipe = listOf(curry, salad).first { it.id == firstArg<String>() }
            val guests = secondArg<Int>()
            Result.success(
                recipe.copy(
                    ingredients = listOf(
                        RecipeIngredient(
                            ingredientName = "Ingrédient de ${recipe.name}",
                            quantity = 100f * guests / 2,
                            unit = CookbookUnit.G,
                        ),
                    ),
                ),
            )
        }
        val ingredients = mockk<IngredientRepository>()
        coEvery { ingredients.getIngredients() } returns Result.success(emptyList())
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        val auth = mockk<AuthRepository>()
        coEvery { auth.getUserId() } returns "user-1"

        compose.setContent {
            MealsWeekScreen(
                viewModel = MealsWeekViewModel(meals, recipes, ingredients, mercure, auth),
                onCreateMeal = onCreateMeal,
                onOpenMeal = onOpenMeal,
                onChooseIngredients = onChooseIngredients,
                onLaunchRecipe = onLaunchRecipe,
            )
        }
        compose.waitForIdle()
    }

    private fun ComposeContentTestRule.waitForText(text: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `the week is named by its first and last day`() {
        show()

        compose.onNodeWithTag(UiTags.MEALS_WEEK_LABEL).assertTextEquals(weekRangeLabel(monday))
    }

    @Test
    fun `one block per day, in order, each titled with its weekday and number`() {
        show()

        val titles = (0L..6L).map { offset -> dayTitle(monday.plusDays(offset)) }
        titles.forEach { compose.onNodeWithText(it).assertExists() }
        assertTopToBottom(*titles.map { it to compose.onNodeWithText(it) }.toTypedArray())
    }

    @Test
    fun `a meal is a card with its slot, its convives and each of its recipes`() {
        show()

        compose.onNodeWithTag(UiTags.MEAL_CARD).assertExists()
        compose.onNodeWithText("Déjeuner").assertExists()
        compose.onNodeWithTag(UiTags.MEAL_CARD_GUESTS_VALUE).assertTextEquals("4")
        compose.waitForText("Riz au curry")
        compose.onNodeWithText("Salade verte").assertExists()
        assertEquals(2, compose.onAllNodesWithTag(UiTags.MEAL_RECIPE_LAUNCH).fetchSemanticsNodes().size)
    }

    @Test
    fun `each recipe starts at the convives of the meal and shows the quantities for them`() {
        show()

        compose.waitForText("Ingrédient de Riz au curry 200 g")
        compose.onAllNodes(hasTestTag(UiTags.MEAL_RECIPE_PORTIONS_VALUE))[0].assertTextEquals("4")
        compose.onAllNodes(hasTestTag(UiTags.MEAL_RECIPE_PORTIONS_VALUE))[1].assertTextEquals("4")
    }

    @Test
    fun `a meal planned at dinner is under the same day as a lunch, the lunch first`() {
        show(listOf(lunch.copy(id = "meal-dinner", slot = MealSlot.DINNER, recipes = emptyList()), lunch))

        assertTopToBottom(
            "Déjeuner" to compose.onNodeWithText("Déjeuner"),
            "Dîner" to compose.onNodeWithText("Dîner"),
        )
    }

    @Test
    fun `minus on a recipe shows its new portions and the quantities the API scaled, and saves nothing`() {
        show()
        compose.waitForText("Ingrédient de Riz au curry 200 g")

        compose.onAllNodes(hasTestTag(UiTags.MEAL_RECIPE_PORTIONS_MINUS))[0].performClick()
        compose.onAllNodes(hasTestTag(UiTags.MEAL_RECIPE_PORTIONS_MINUS))[0].performClick()

        compose.onAllNodes(hasTestTag(UiTags.MEAL_RECIPE_PORTIONS_VALUE))[0].assertTextEquals("2")
        compose.onAllNodes(hasTestTag(UiTags.MEAL_RECIPE_PORTIONS_VALUE))[1].assertTextEquals("4")
        compose.waitForText("Ingrédient de Riz au curry 100 g")
        coVerify(exactly = 0) { meals.updateMeal(any(), any()) }
        compose.onNodeWithTag(UiTags.MEAL_CARD_GUESTS_VALUE).assertTextEquals("4")
    }

    @Test
    fun `plus on the convives shows the new number at once and saves it on the meal`() {
        show()

        compose.onNodeWithTag(UiTags.MEAL_CARD_GUESTS_PLUS).performClick()

        compose.onNodeWithTag(UiTags.MEAL_CARD_GUESTS_VALUE).assertTextEquals("5")
        // The save waits for the debounce, which only runs as the test clock advances: the recipes
        // that follow the meal wait for the same pause, and their new quantities are what moves it.
        compose.waitForText("Ingrédient de Riz au curry 250 g")
        compose.mainClock.advanceTimeBy(SIMULATION_DEBOUNCE_MS + 100)
        compose.waitForIdle()
        coVerify(exactly = 1) { meals.updateMeal("meal-lunch", MealUpdateRequest(guests = 5)) }
    }

    @Test
    fun `minus on one convive is not offered`() {
        show(listOf(lunch.copy(guests = 1)))

        compose.onNodeWithTag(UiTags.MEAL_CARD_GUESTS_MINUS).assertIsNotEnabled()
    }

    @Test
    fun `a tap on the meal opens its sheet and does not plan another one`() {
        val opened = mutableListOf<Meal>()
        val created = mutableListOf<Pair<String, String>>()
        show(onOpenMeal = { opened += it }, onCreateMeal = { day, slot -> created += day to slot })

        compose.onNodeWithTag(UiTags.MEAL_ROW).performClick()

        assertEquals(listOf(lunch), opened)
        assertEquals(emptyList<Pair<String, String>>(), created)
    }

    @Test
    fun `a long press on the meal asks before deleting it, and the confirmation deletes`() {
        show()

        compose.onNodeWithTag(UiTags.MEAL_ROW).performTouchInput { longClick() }
        compose.onNodeWithText("Supprimer ce repas ?").assertExists()
        compose.onNodeWithTag(UiTags.MEAL_DELETE_CONFIRM).performClick()

        coVerify(timeout = 5_000) { meals.deleteMeal("meal-lunch") }
    }

    @Test
    fun `Lancer starts the Cuisine activity on that recipe`() {
        val launched = mutableListOf<String>()
        show(onLaunchRecipe = { launched += it })

        compose.onAllNodes(hasTestTag(UiTags.MEAL_RECIPE_LAUNCH))[1].performClick()

        assertEquals(listOf("r-salad"), launched)
    }

    @Test
    fun `the ingredients action opens the choice of what goes on the list for that meal`() {
        val chosen = mutableListOf<String>()
        show(onChooseIngredients = { chosen += it })

        compose.onNodeWithTag(UiTags.MEAL_CARD_INGREDIENTS).performClick()

        assertEquals(listOf("meal-lunch"), chosen)
    }

    @Test
    fun `the add button of an empty day plans a meal on that day, at lunch`() {
        val created = mutableListOf<Pair<String, String>>()
        show(onCreateMeal = { day, slot -> created += day to slot })

        compose.onNodeWithTag(UiTags.mealAddDay(3)).performScrollTo().performClick()

        assertEquals(listOf(monday.plusDays(3).toString() to "lunch"), created)
    }

    @Test
    fun `the add button of a day whose lunch is planned offers the dinner`() {
        val created = mutableListOf<Pair<String, String>>()
        show(onCreateMeal = { day, slot -> created += day to slot })

        compose.onNodeWithTag(UiTags.mealAddDay(0)).performScrollTo().performClick()

        assertEquals(listOf(monday.toString() to "dinner"), created)
    }

    @Test
    fun `an empty day shows only its title and the add button`() {
        show(emptyList())

        compose.onNodeWithText(dayTitle(monday.plusDays(1))).assertExists()
        compose.onNodeWithTag(UiTags.mealAddDay(1)).assertExists()
        assertEquals(7, compose.onAllNodesWithText("+ Ajouter un repas").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("Déjeuner")).fetchSemanticsNodes().size)
    }

    @Test
    fun `the arrows move the week`() {
        show()

        compose.onNodeWithTag(UiTags.MEALS_WEEK_NEXT).performClick()
        compose.onNodeWithTag(UiTags.MEALS_WEEK_LABEL).assertTextEquals(weekRangeLabel(monday.plusWeeks(1)))
        compose.onNodeWithTag(UiTags.MEALS_WEEK_PREVIOUS).performClick()
        compose.onNodeWithTag(UiTags.MEALS_WEEK_PREVIOUS).performClick()
        compose.onNodeWithTag(UiTags.MEALS_WEEK_LABEL).assertTextEquals(weekRangeLabel(monday.minusWeeks(1)))
    }

    @Test
    fun `a refused save of the convives says why`() {
        show()
        coEvery { meals.updateMeal(any(), any()) } returns Result.failure(RuntimeException("Refusé"))

        compose.onNodeWithTag(UiTags.MEAL_CARD_GUESTS_PLUS).performClick()

        compose.waitForText("Refusé")
    }
}

internal fun dayTitle(day: LocalDate): String = dayLabel(day)
