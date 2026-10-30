package com.maggie.app.ui.screens.cookbook.recipes

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.HealthProfile
import com.maggie.app.data.repository.HealthProfileRepository
import com.maggie.app.data.repository.IngredientRepository
import com.maggie.app.data.repository.RecipeRepository
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.ProductCategory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The open recipe sheet follows the recipe: one transition per message received. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var recipeRepository: RecipeRepository
    private lateinit var ingredientRepository: IngredientRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private lateinit var healthProfileRepository: HealthProfileRepository
    private val events = MutableSharedFlow<MercureEvent>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        recipeRepository = mockk()
        ingredientRepository = mockk()
        coEvery { ingredientRepository.getIngredients() } returns Result.success(emptyList())
        mercureService = mockk()
        authRepository = mockk()
        coEvery { recipeRepository.getRecipe("recipe-1") } returns Result.success(carbonara())
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns events
        healthProfileRepository = mockk()
        coEvery { healthProfileRepository.getProfile() } returns Result.success(HealthProfile())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.openSheet(): RecipeDetailViewModel {
        val viewModel = RecipeDetailViewModel("recipe-1", recipeRepository, ingredientRepository, mercureService, authRepository, healthProfileRepository)
        advanceUntilIdle()
        return viewModel
    }

    private suspend fun TestScope.receive(viewModel: RecipeDetailViewModel, data: String) {
        events.emit(MercureEvent(data = data))
        advanceUntilIdle()
    }

    @Test
    fun `the day's need of the health profile is the ring's reference (MAG-707)`() = runTest {
        coEvery { healthProfileRepository.getProfile() } returns Result.success(HealthProfile(bmrKcal = 1648, needKcal = 1980))

        val viewModel = openSheet()

        assertEquals(1980, viewModel.uiState.value.dailyKcal)
    }

    @Test
    fun `without a complete profile there is no reference`() = runTest {
        val viewModel = openSheet()

        assertNull(viewModel.uiState.value.dailyKcal)
        assertEquals("recipe-1", viewModel.uiState.value.recipe?.id)
    }

    @Test
    fun `a profile that cannot be read leaves the ring out, not the sheet`() = runTest {
        coEvery { healthProfileRepository.getProfile() } returns Result.failure(RuntimeException("boom"))

        val viewModel = openSheet()

        assertNull(viewModel.uiState.value.dailyKcal)
        assertNull(viewModel.uiState.value.error)
        assertEquals("recipe-1", viewModel.uiState.value.recipe?.id)
    }

    @Test
    fun `it subscribes to the user-scoped recipe topic`() = runTest {
        openSheet()

        verify { mercureService.subscribe("/users/user-1/api/recipes/{id}") }
    }

    @Test
    fun `it shows the recipe it loaded`() = runTest {
        val viewModel = openSheet()

        assertEquals(carbonara(), viewModel.uiState.value.recipe)
        assertEquals(false, viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a load that fails shows its error`() = runTest {
        coEvery { recipeRepository.getRecipe("recipe-1") } returns Result.failure(RuntimeException("offline"))

        val viewModel = openSheet()

        assertEquals("offline", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.recipe)
    }

    @Test
    fun `a quantity changed elsewhere shows without any action`() = runTest {
        val viewModel = openSheet()

        receive(viewModel, published(publishedLine(350)))

        assertEquals(350f, viewModel.uiState.value.recipe!!.ingredients.single().quantity)
        assertEquals("Sans crème", viewModel.uiState.value.recipe!!.notes)
        coVerify(exactly = 1) { recipeRepository.getRecipe("recipe-1") }
    }

    @Test
    fun `notes and tags changed elsewhere show`() = runTest {
        val viewModel = openSheet()

        receive(viewModel, published(""""notes":"Avec du poivre","tags":["rapide"]"""))

        assertEquals("Avec du poivre", viewModel.uiState.value.recipe!!.notes)
        assertEquals(listOf("rapide"), viewModel.uiState.value.recipe!!.tags)
    }

    @Test
    fun `a message about another recipe changes nothing`() = runTest {
        val viewModel = openSheet()

        receive(viewModel, published(publishedLine(999), id = "recipe-2"))

        assertEquals(carbonara(), viewModel.uiState.value.recipe)
    }

    @Test
    fun `a message that cannot be read fetches the recipe again`() = runTest {
        val viewModel = openSheet()
        coEvery { recipeRepository.getRecipe("recipe-1") } returns Result.success(carbonara(500f))

        receive(viewModel, "not json")

        assertEquals(500f, viewModel.uiState.value.recipe!!.ingredients.single().quantity)
    }

    @Test
    fun `a recipe deleted elsewhere says so`() = runTest {
        val viewModel = openSheet()

        receive(viewModel, """{"@id":"/api/recipes/recipe-1","deleted":true}""")

        assertNull(viewModel.uiState.value.recipe)
        assertEquals("Cette recette a été supprimée.", viewModel.uiState.value.error)
    }

    @Test
    fun `a change published while the first load is in flight is not lost`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { recipeRepository.getRecipe("recipe-1") } coAnswers {
            gate.await()
            Result.success(if (calls++ == 0) carbonara() else carbonara(500f))
        }
        val viewModel = RecipeDetailViewModel("recipe-1", recipeRepository, ingredientRepository, mercureService, authRepository, healthProfileRepository)
        runCurrent()

        events.emit(MercureEvent(data = published(publishedLine(500))))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(viewModel.uiState.value.recipe!!.ingredients.single().quantity, 500f)
    }

    private fun scaled(quantity: Float) = Result.success(carbonara(quantity))

    @Test
    fun `no simulation is shown before the user steps`() = runTest {
        val viewModel = openSheet()

        assertNull(viewModel.uiState.value.simulation)
        assertEquals(carbonara().ingredients, viewModel.uiState.value.ingredients)
    }

    @Test
    fun `stepping starts from the recipe's servings and shows the quantities the API scaled`() = runTest {
        coEvery { recipeRepository.getScaledRecipe("recipe-1", 5) } returns scaled(250f)
        val viewModel = openSheet()

        viewModel.stepGuests(1)
        assertEquals(RecipeSimulation(guests = 5, isLoading = true), viewModel.uiState.value.simulation)
        advanceUntilIdle()

        val simulation = viewModel.uiState.value.simulation!!
        assertEquals(5, simulation.guests)
        assertEquals(false, simulation.isLoading)
        assertEquals(250f, viewModel.uiState.value.ingredients.single().quantity)
        assertEquals(200f, viewModel.uiState.value.recipe!!.ingredients.single().quantity)
    }

    @Test
    fun `quick taps ask the API once, for the last number`() = runTest {
        coEvery { recipeRepository.getScaledRecipe("recipe-1", 7) } returns scaled(350f)
        val viewModel = openSheet()

        viewModel.stepGuests(1)
        viewModel.stepGuests(1)
        viewModel.stepGuests(1)
        advanceUntilIdle()

        assertEquals(7, viewModel.uiState.value.simulation!!.guests)
        coVerify(exactly = 1) { recipeRepository.getScaledRecipe(any(), any()) }
    }

    @Test
    fun `an answer that arrives after a newer tap is dropped`() = runTest {
        val slow = CompletableDeferred<Result<com.maggie.app.data.model.Recipe>>()
        coEvery { recipeRepository.getScaledRecipe("recipe-1", 5) } coAnswers { slow.await() }
        coEvery { recipeRepository.getScaledRecipe("recipe-1", 6) } returns scaled(300f)
        val viewModel = openSheet()

        viewModel.stepGuests(1)
        testScheduler.advanceTimeBy(SIMULATION_DEBOUNCE_MS + 1)
        testScheduler.runCurrent()
        viewModel.stepGuests(1)
        slow.complete(scaled(250f))
        advanceUntilIdle()

        assertEquals(6, viewModel.uiState.value.simulation!!.guests)
        assertEquals(300f, viewModel.uiState.value.ingredients.single().quantity)
    }

    @Test
    fun `the number never goes under one`() = runTest {
        coEvery { recipeRepository.getScaledRecipe("recipe-1", 1) } returns scaled(50f)
        val viewModel = openSheet()

        repeat(6) { viewModel.stepGuests(-1) }
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.simulation!!.guests)
    }

    @Test
    fun `back to the recipe's parts shows the original and cancels what was in flight`() = runTest {
        coEvery { recipeRepository.getScaledRecipe("recipe-1", 5) } returns scaled(250f)
        val viewModel = openSheet()
        viewModel.stepGuests(1)
        advanceUntilIdle()

        viewModel.resetSimulation()
        assertNull(viewModel.uiState.value.simulation)
        assertEquals(200f, viewModel.uiState.value.ingredients.single().quantity)

        viewModel.stepGuests(1)
        viewModel.resetSimulation()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.simulation)
        coVerify(exactly = 1) { recipeRepository.getScaledRecipe(any(), any()) }
    }

    @Test
    fun `a failed simulation says so and keeps the recipe's own quantities`() = runTest {
        coEvery { recipeRepository.getScaledRecipe("recipe-1", 5) } returns Result.failure(RuntimeException("offline"))
        val viewModel = openSheet()

        viewModel.stepGuests(1)
        advanceUntilIdle()

        val simulation = viewModel.uiState.value.simulation!!
        assertEquals(SIMULATION_ERROR, simulation.error)
        assertEquals(5, simulation.guests)
        assertEquals(200f, viewModel.uiState.value.ingredients.single().quantity)
    }

    @Test
    fun `a recipe changed elsewhere during a simulation scales the new recipe again`() = runTest {
        coEvery { recipeRepository.getScaledRecipe("recipe-1", 5) } returns scaled(250f) andThen scaled(437.5f)
        val viewModel = openSheet()
        viewModel.stepGuests(1)
        advanceUntilIdle()

        receive(viewModel, published(publishedLine(350)))

        assertEquals(437.5f, viewModel.uiState.value.ingredients.single().quantity)
        assertEquals(350f, viewModel.uiState.value.recipe!!.ingredients.single().quantity)
    }

    @Test
    fun `the sheet counts what a serving brings from the catalogue`() = runTest {
        coEvery { ingredientRepository.getIngredients() } returns Result.success(
            listOf(Ingredient("i-1", "Pâtes", null, ProductCategory.GRAIN, kcalPer100g = 350f, proteinPer100g = 12f, carbsPer100g = 70f, fatPer100g = 2f)),
        )
        coEvery { recipeRepository.getRecipe("recipe-1") } returns Result.success(carbonara().copy(ingredients = listOf(pastaLine(400f).copy(ingredient = JsonObject(mapOf("id" to JsonPrimitive("i-1")))))))

        val viewModel = openSheet()

        assertEquals(RecipeNutrition(kcal = 350, protein = 12, carbs = 70, fat = 2, skipped = 0), viewModel.uiState.value.nutrition)
    }

    @Test
    fun `a catalogue that cannot be read leaves the sheet without figures`() = runTest {
        coEvery { ingredientRepository.getIngredients() } returns Result.failure(RuntimeException("offline"))

        val viewModel = openSheet()

        assertNull(viewModel.uiState.value.nutrition)
        assertEquals(carbonara(), viewModel.uiState.value.recipe)
    }
}
