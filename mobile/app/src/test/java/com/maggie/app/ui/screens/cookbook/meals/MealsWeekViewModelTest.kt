package com.maggie.app.ui.screens.cookbook.meals

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.api.MealUpdateRequest
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealSlot
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.repository.IngredientRepository
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.RecipeRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The week of meals, and the day each one lands on — MAG-251.
 *
 * The screen used to ask for a window of *instants* and read the day back out
 * of `startAt` through the device's time zone. Two conversions for a value that
 * is a day: a meal planned for Wednesday came back as Tuesday at 22:00 UTC and
 * was grouped under Tuesday wherever the device sat east of Greenwich.
 *
 * There is nothing to convert now. The three days below are a summer-time one,
 * a winter-time one and the day the clocks go back, and all three have to group
 * under themselves — which they do for the uninteresting reason that no offset
 * is involved any more. That is the claim.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MealsWeekViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mealRepository: MealRepository
    private lateinit var recipeRepository: RecipeRepository
    private lateinit var ingredientRepository: IngredientRepository
    private val mercureEvents = MutableSharedFlow<MercureEvent>()
    private lateinit var mercure: MercureService
    private lateinit var auth: AuthRepository
    private val token = MutableStateFlow<String?>("jwt")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mealRepository = mockk()
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(emptyList())
        recipeRepository = mockk()
        coEvery { recipeRepository.getScaledRecipe(any(), any()) } answers { Result.success(scaled(firstArg(), secondArg())) }
        ingredientRepository = mockk()
        coEvery { ingredientRepository.getIngredients() } returns Result.success(emptyList())
        mercure = mockk()
        every { mercure.subscribe(any()) } returns mercureEvents
        auth = mockk()
        coEvery { auth.getUserId() } returns "user-1"
        every { auth.token } returns token
    }

    private fun newViewModel() = MealsWeekViewModel(mealRepository, recipeRepository, ingredientRepository, mercure, auth)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun aMeal(day: String, slot: MealSlot = MealSlot.LUNCH) =
        Meal(id = "meal-$day-$slot", summary = "Déjeuner", date = day, slot = slot)

    @Test
    fun `asks for the week as a range of days, both ends included`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        val weekStart = viewModel.uiState.value.weekStart

        coVerify { mealRepository.getMeals(weekStart.toString(), weekStart.plusDays(6).toString()) }
    }

    @Test
    fun `a screen built before the sign-in loads the week once signed in`() = runTest(testDispatcher) {
        token.value = null
        coEvery { auth.getUserId() } returns null
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(emptyList())
        val viewModel = newViewModel()
        advanceUntilIdle()
        assertEquals(emptyMap<LocalDate, Map<MealSlot, List<Meal>>>(), viewModel.uiState.value.mealsByDaySlot)

        val today = viewModel.uiState.value.weekStart.toString()
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(aMeal(today)))
        coEvery { auth.getUserId() } returns "user-1"
        token.value = "jwt"
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.mealsByDaySlot.values.sumOf { it.values.sumOf(List<Meal>::size) })
        io.mockk.verify { mercure.subscribe(any()) }
    }

    @Test
    fun `groups each meal under its own day, whatever the season`() = runTest(testDispatcher) {
        val days = listOf("2026-10-07", "2026-12-09", "2026-10-25")
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(days.map { aMeal(it) })

        val viewModel = newViewModel()
        advanceUntilIdle()

        val grouped = viewModel.uiState.value.mealsByDaySlot
        days.forEach { day ->
            val meals = grouped[LocalDate.parse(day)]?.get(MealSlot.LUNCH)
            assertEquals("the meal of $day is not under $day", listOf(aMeal(day)), meals)
        }
    }

    @Test
    fun `keeps lunch and dinner of the same day apart`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(
            listOf(aMeal("2026-10-07", MealSlot.LUNCH), aMeal("2026-10-07", MealSlot.DINNER)),
        )

        val viewModel = newViewModel()
        advanceUntilIdle()

        val day = viewModel.uiState.value.mealsByDaySlot[LocalDate.parse("2026-10-07")]
        assertEquals(1, day?.get(MealSlot.LUNCH)?.size)
        assertEquals(1, day?.get(MealSlot.DINNER)?.size)
    }

    @Test
    fun `moving a week re-asks for that week`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        val weekStart = viewModel.uiState.value.weekStart

        viewModel.nextWeek()
        advanceUntilIdle()

        assertEquals(weekStart.plusWeeks(1), viewModel.uiState.value.weekStart)
        coVerify {
            mealRepository.getMeals(weekStart.plusWeeks(1).toString(), weekStart.plusWeeks(1).plusDays(6).toString())
        }
    }

    @Test
    fun `a failed load leaves the error on the state and stops loading`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.failure(RuntimeException("réseau coupé"))

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals("réseau coupé", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a successful load clears a previous error`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.failure(RuntimeException("réseau coupé"))
        val viewModel = newViewModel()
        advanceUntilIdle()

        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(aMeal("2026-10-07")))
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `deleting a meal takes it off the week without a reload`() = runTest(testDispatcher) {
        val meals = listOf(aMeal("2026-10-07"), aMeal("2026-10-07", MealSlot.DINNER))
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(meals)
        coEvery { mealRepository.deleteMeal(any()) } returns Result.success(Unit)
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.deleteMeal(meals[0])
        assertEquals(meals[0].id, viewModel.uiState.value.deletingMealId)
        advanceUntilIdle()

        val day = viewModel.uiState.value.mealsByDaySlot[LocalDate.parse("2026-10-07")]
        assertEquals(emptyList<Meal>(), day?.get(MealSlot.LUNCH))
        assertEquals(1, day?.get(MealSlot.DINNER)?.size)
        assertNull(viewModel.uiState.value.deletingMealId)
        assertNull(viewModel.uiState.value.deleteError)
        coVerify(exactly = 1) { mealRepository.deleteMeal(meals[0].id) }
    }

    @Test
    fun `a meal saved from its sheet shows its new title without a reload`() = runTest(testDispatcher) {
        val meals = listOf(aMeal("2026-10-07"), aMeal("2026-10-07", MealSlot.DINNER))
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(meals)
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.mealSaved(meals[0].copy(summary = "Dimanche en famille", customTitle = "Dimanche en famille"))

        val day = viewModel.uiState.value.mealsByDaySlot[LocalDate.parse("2026-10-07")]
        assertEquals("Dimanche en famille", day?.get(MealSlot.LUNCH)?.single()?.summary)
        assertEquals("Déjeuner", day?.get(MealSlot.DINNER)?.single()?.summary)
        coVerify(exactly = 1) { mealRepository.getMeals(any(), any()) }
    }

    @Test
    fun `a meal deleted from its sheet leaves the week without a reload`() = runTest(testDispatcher) {
        val meals = listOf(aMeal("2026-10-07"), aMeal("2026-10-07", MealSlot.DINNER))
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(meals)
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.mealDeleted(meals[0].id)

        val day = viewModel.uiState.value.mealsByDaySlot[LocalDate.parse("2026-10-07")]
        assertEquals(emptyList<Meal>(), day?.get(MealSlot.LUNCH))
        assertEquals(1, day?.get(MealSlot.DINNER)?.size)
        coVerify(exactly = 1) { mealRepository.getMeals(any(), any()) }
    }

    @Test
    fun `a refused deletion keeps the meal and says why`() = runTest(testDispatcher) {
        val meal = aMeal("2026-10-07")
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(meal))
        coEvery { mealRepository.deleteMeal(any()) } returns Result.failure(RuntimeException("403"))
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.deleteMeal(meal)
        advanceUntilIdle()

        val day = viewModel.uiState.value.mealsByDaySlot[LocalDate.parse("2026-10-07")]
        assertEquals(listOf(meal), day?.get(MealSlot.LUNCH))
        assertEquals("403", viewModel.uiState.value.deleteError)
        assertNull(viewModel.uiState.value.deletingMealId)

        viewModel.dismissDeleteError()
        assertNull(viewModel.uiState.value.deleteError)
    }

    @Test
    fun `a second delete while one is in flight sends nothing`() = runTest(testDispatcher) {
        val meal = aMeal("2026-10-07")
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(meal))
        coEvery { mealRepository.deleteMeal(any()) } returns Result.success(Unit)
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.deleteMeal(meal)
        viewModel.deleteMeal(meal)
        advanceUntilIdle()

        coVerify(exactly = 1) { mealRepository.deleteMeal(meal.id) }
    }

    @Test
    fun `a change announced on the meals topic reloads the week without the spinner`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(aMeal("2026-10-07")))

        mercureEvents.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.mealsByDaySlot[LocalDate.parse("2026-10-07")]?.get(MealSlot.LUNCH)?.size)
        assertEquals(false, viewModel.uiState.value.isLoading)
        coVerify { mercure.subscribe("/users/user-1/api/meals/{id}") }
    }

    // -- Cuisine › Repas, as the mock-up draws it (MAG-628) -----------------------------------------------------

    private val monday = LocalDate.now().with(WeekFields.of(Locale.FRANCE).dayOfWeek(), 1)

    /** What the API answers to `GET /api/recipes/{id}?guests=N`: 100 g of rice for two, scaled to N. */
    private fun scaled(id: String, guests: Int) = Recipe(
        id = id,
        name = "Recette $id",
        servings = 2,
        ingredients = listOf(RecipeIngredient(ingredientName = "Riz", quantity = 100f * guests / 2, unit = CookbookUnit.G)),
    )

    private fun plannedMeal(guests: Int = 4, recipeIds: List<String> = listOf("r1", "r2")) = Meal(
        id = "meal-1",
        summary = "Menu",
        date = monday.toString(),
        slot = MealSlot.LUNCH,
        recipes = recipeIds.map { "/api/recipes/$it" },
        guests = guests,
        guestsIsDefault = false,
    )

    private fun MealsWeekViewModel.theMeal() =
        uiState.value.mealsByDaySlot[monday]?.get(MealSlot.LUNCH)?.single()

    private val first = MealRecipeKey("meal-1", "r1")
    private val second = MealRecipeKey("meal-1", "r2")

    @Test
    fun `going back a week asks for the previous week`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        val weekStart = viewModel.uiState.value.weekStart

        viewModel.previousWeek()
        advanceUntilIdle()

        assertEquals(weekStart.minusWeeks(1), viewModel.uiState.value.weekStart)
        coVerify {
            mealRepository.getMeals(weekStart.minusWeeks(1).toString(), weekStart.minusWeeks(1).plusDays(6).toString())
        }
    }

    @Test
    fun `each recipe of a meal starts at the guests of the meal, with the quantities the API scaled to them`() =
        runTest(testDispatcher) {
            coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(plannedMeal(guests = 4)))

            val viewModel = newViewModel()
            advanceUntilIdle()

            coVerify(exactly = 1) { recipeRepository.getScaledRecipe("r1", 4) }
            coVerify(exactly = 1) { recipeRepository.getScaledRecipe("r2", 4) }
            val portions = viewModel.uiState.value.portions
            assertEquals(4, portions[first]?.guests)
            assertEquals("Recette r1", portions[first]?.recipe?.name)
            assertEquals(200f, portions[first]?.recipe?.ingredients?.single()?.quantity)
            assertEquals(4, portions[second]?.guests)
        }

    @Test
    fun `a quiet reload does not ask the API to scale the recipes again`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(plannedMeal()))
        newViewModel()
        advanceUntilIdle()

        mercureEvents.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        coVerify(exactly = 1) { recipeRepository.getScaledRecipe("r1", 4) }
    }

    @Test
    fun `plus on the convives saves the number on the meal and sends nothing else`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(plannedMeal(guests = 4)))
        val sent = slot<MealUpdateRequest>()
        coEvery { mealRepository.updateMeal("meal-1", capture(sent)) } answers {
            Result.success(plannedMeal(guests = 5))
        }
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.stepGuests("meal-1", +1)
        assertEquals("the number shows at once", 5, viewModel.theMeal()?.guests)
        advanceUntilIdle()

        assertEquals(MealUpdateRequest(guests = 5), sent.captured)
        assertEquals(5, viewModel.theMeal()?.guests)
        assertEquals(false, viewModel.theMeal()?.guestsIsDefault)
    }

    @Test
    fun `quick taps on the convives send one request with the last number`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(plannedMeal(guests = 4)))
        coEvery { mealRepository.updateMeal(any(), any()) } returns Result.success(plannedMeal(guests = 6))
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.stepGuests("meal-1", +1)
        viewModel.stepGuests("meal-1", +1)
        advanceUntilIdle()

        coVerify(exactly = 1) { mealRepository.updateMeal("meal-1", MealUpdateRequest(guests = 6)) }
    }

    @Test
    fun `minus on the convives stops at one and sends nothing below`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(plannedMeal(guests = 2)))
        coEvery { mealRepository.updateMeal(any(), any()) } returns Result.success(plannedMeal(guests = 1))
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.stepGuests("meal-1", -1)
        viewModel.stepGuests("meal-1", -1)
        advanceUntilIdle()

        assertEquals(1, viewModel.theMeal()?.guests)
        coVerify(exactly = 1) { mealRepository.updateMeal("meal-1", MealUpdateRequest(guests = 1)) }
    }

    @Test
    fun `a meal at one convive that is stepped down is not even asked about`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(plannedMeal(guests = 1)))
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.stepGuests("meal-1", -1)
        advanceUntilIdle()

        assertEquals(1, viewModel.theMeal()?.guests)
        coVerify(exactly = 0) { mealRepository.updateMeal(any(), any()) }
    }

    @Test
    fun `a refused save puts the number back and says why`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(plannedMeal(guests = 4)))
        coEvery { mealRepository.updateMeal(any(), any()) } returns Result.failure(RuntimeException("403"))
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.stepGuests("meal-1", +1)
        advanceUntilIdle()

        assertEquals(4, viewModel.theMeal()?.guests)
        assertEquals("403", viewModel.uiState.value.guestsError)

        viewModel.dismissGuestsError()
        assertNull(viewModel.uiState.value.guestsError)
    }

    @Test
    fun `a recipe that still follows the meal follows its convives, one that was changed does not`() =
        runTest(testDispatcher) {
            coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(plannedMeal(guests = 4)))
            coEvery { mealRepository.updateMeal(any(), any()) } returns Result.success(plannedMeal(guests = 5))
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.stepPortions("meal-1", "r2", -1)
            advanceUntilIdle()

            viewModel.stepGuests("meal-1", +1)
            advanceUntilIdle()

            assertEquals(5, viewModel.uiState.value.portions[first]?.guests)
            assertEquals(3, viewModel.uiState.value.portions[second]?.guests)
            coVerify { recipeRepository.getScaledRecipe("r1", 5) }
        }

    @Test
    fun `minus on a recipe changes its simulation and writes nothing`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(plannedMeal(guests = 4)))
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.stepPortions("meal-1", "r1", -1)
        viewModel.stepPortions("meal-1", "r1", -1)
        assertEquals("the number shows at once", 2, viewModel.uiState.value.portions[first]?.guests)
        advanceUntilIdle()

        val portions = viewModel.uiState.value.portions
        assertEquals(2, portions[first]?.guests)
        assertEquals(100f, portions[first]?.recipe?.ingredients?.single()?.quantity)
        assertEquals("the other recipe stays at the meal's guests", 4, portions[second]?.guests)
        assertEquals("the meal keeps its convives", 4, viewModel.theMeal()?.guests)
        coVerify(exactly = 1) { recipeRepository.getScaledRecipe("r1", 2) }
        coVerify(exactly = 0) { mealRepository.updateMeal(any(), any()) }
        coVerify(exactly = 0) { recipeRepository.updateRecipe(any(), any()) }
    }

    @Test
    fun `plus on a recipe goes above the meal's convives`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(plannedMeal(guests = 4)))
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.stepPortions("meal-1", "r1", +1)
        advanceUntilIdle()

        assertEquals(5, viewModel.uiState.value.portions[first]?.guests)
        coVerify(exactly = 0) { mealRepository.updateMeal(any(), any()) }
    }

    @Test
    fun `the portions of a recipe stop at one`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(plannedMeal(guests = 1)))
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.stepPortions("meal-1", "r1", -1)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.portions[first]?.guests)
    }

    @Test
    fun `a simulation the API could not scale keeps the last quantities and says why`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(plannedMeal(guests = 4)))
        val viewModel = newViewModel()
        advanceUntilIdle()
        coEvery { recipeRepository.getScaledRecipe("r1", 3) } returns Result.failure(RuntimeException("réseau"))

        viewModel.stepPortions("meal-1", "r1", -1)
        advanceUntilIdle()

        val portions = viewModel.uiState.value.portions[first]
        assertEquals(3, portions?.guests)
        assertEquals(200f, portions?.recipe?.ingredients?.single()?.quantity)
        assertEquals(false, portions?.isLoading)
        assertEquals(com.maggie.app.ui.screens.cookbook.recipes.SIMULATION_ERROR, portions?.error)
    }

    @Test
    fun `a meal that leaves the week leaves its recipes' simulations`() = runTest(testDispatcher) {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(plannedMeal(guests = 4)))
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.mealDeleted("meal-1")
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(emptyList())
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(emptyMap<MealRecipeKey, Any>(), viewModel.uiState.value.portions)
    }
}
