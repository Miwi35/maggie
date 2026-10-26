package com.maggie.app.ui.screens.cookbook.recipes

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.data.repository.IngredientRepository
import com.maggie.app.data.repository.RecipeRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The recipe list, and above all the subscription it makes.
 *
 * This ViewModel subscribed to `"/api/recipes/{id}"` — no user scope — while
 * the API publishes to `/users/{userId}/api/recipes/{id}`. The SSE connection
 * opened, stayed open, and delivered nothing, for as long as the screen had
 * existed. Nothing failed, so nothing was noticed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var recipeRepository: RecipeRepository
    private lateinit var ingredientRepository: IngredientRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository

    private val recipe = Recipe(id = "recipe-1", name = "Tarte aux pommes")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        recipeRepository = mockk()
        ingredientRepository = mockk()
        mercureService = mockk()
        authRepository = mockk()

        every { recipeRepository.observeRecipes() } returns flowOf(listOf(recipe))
        coEvery { recipeRepository.refreshRecipes() } returns Result.success(listOf(recipe))
        coEvery { ingredientRepository.getIngredients() } returns Result.success(emptyList())
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns emptyFlow()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() =
        RecipeListViewModel(recipeRepository, ingredientRepository, mercureService, authRepository)

    @Test
    fun `it subscribes to the user-scoped recipe topic`() = runTest {
        createViewModel()
        advanceUntilIdle()

        // The user id substituted, the resource id left as the URI-template
        // placeholder the topic selector expects.
        verify { mercureService.subscribe("/users/user-1/api/recipes/{id}") }
    }

    @Test
    fun `it does not subscribe at all when no user is signed in`() = runTest {
        coEvery { authRepository.getUserId() } returns null

        createViewModel()
        advanceUntilIdle()

        verify(exactly = 0) { mercureService.subscribe(any()) }
    }

    @Test
    fun `it exposes the recipes the repository observes`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf(recipe), viewModel.uiState.value.recipes)
    }

    @Test
    fun `a failed refresh surfaces as an error and stops the spinner`() = runTest {
        coEvery { recipeRepository.refreshRecipes() } returns Result.failure(RuntimeException("offline"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("offline", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.isLoading)
    }

    // -- The list as the mock-up draws it (MAG-628): search, pills, « 515 kcal · 35 min » ---------------------------

    private val tarte = Recipe(id = "r-tarte", name = "Tarte aux pommes", tags = listOf("Dessert", "Végétarien"))
    private val gratin = Recipe(id = "r-gratin", name = "Gratin dauphinois", tags = listOf("Végétarien", "Plat"))
    private val curry = Recipe(id = "r-curry", name = "Curry de pois chiches", tags = listOf("Plat"))

    private fun listOf3() {
        every { recipeRepository.observeRecipes() } returns flowOf(listOf(tarte, gratin, curry))
        coEvery { recipeRepository.refreshRecipes() } returns Result.success(listOf(tarte, gratin, curry))
    }

    private fun names(viewModel: RecipeListViewModel) = viewModel.uiState.value.visible.map { it.name }

    @Test
    fun `the search keeps the recipes whose name holds the words, whatever the case and the accents`() = runTest {
        listOf3()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.search("  GRATIN ")
        assertEquals(listOf("Gratin dauphinois"), names(viewModel))

        viewModel.search("pois chiche")
        assertEquals(listOf("Curry de pois chiches"), names(viewModel))

        viewModel.search("vegetarien")
        assertEquals(emptyList<String>(), names(viewModel))

        viewModel.search("")
        assertEquals(3, names(viewModel).size)
    }

    @Test
    fun `the pills are the tags the recipes carry, once each, and nothing else`() = runTest {
        listOf3()
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf("Dessert", "Plat", "Végétarien"), viewModel.uiState.value.tags)
    }

    @Test
    fun `a pill keeps the recipes with its tag, the same pill again turns it off`() = runTest {
        listOf3()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.filterBy("Végétarien")
        assertEquals(listOf("Tarte aux pommes", "Gratin dauphinois"), names(viewModel))

        viewModel.filterBy("Végétarien")
        assertNull(viewModel.uiState.value.tag)
        assertEquals(3, names(viewModel).size)
    }

    @Test
    fun `a pill and a search both apply`() = runTest {
        listOf3()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.filterBy("Plat")
        viewModel.search("curry")

        assertEquals(listOf("Curry de pois chiches"), names(viewModel))
    }

    @Test
    fun `a pill whose last recipe is gone turns itself off`() = runTest {
        val recipes = kotlinx.coroutines.flow.MutableStateFlow(listOf(tarte, curry))
        every { recipeRepository.observeRecipes() } returns recipes
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.filterBy("Dessert")

        recipes.value = listOf(curry)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.tag)
        assertEquals(listOf("Curry de pois chiches"), names(viewModel))
    }

    private fun rice(kcal: Float?) = Ingredient("rice", "Riz", null, ProductCategory.MEAT, kcal)

    private fun withRice(quantity: Float, minutes: Int?) = Recipe(
        id = "r-tarte",
        name = "Tarte aux pommes",
        servings = 2,
        ingredients = listOf(
            RecipeIngredient(ingredient = JsonObject(mapOf("id" to JsonPrimitive("rice"))), quantity = quantity, unit = CookbookUnit.G),
        ),
        steps = listOfNotNull(minutes?.let { RecipeStep(position = 1, text = "Cuire", durationMinutes = it) }),
    )

    @Test
    fun `the subtitle is the kcal of a person and the time of the steps`() = runTest {
        // 400 g at 250 kcal / 100 g is 1 000 kcal, 500 for each of the two servings.
        coEvery { recipeRepository.refreshRecipes() } returns Result.success(listOf(withRice(400f, 35)))
        coEvery { ingredientRepository.getIngredients() } returns Result.success(listOf(rice(250f)))
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("500 kcal · 35 min", viewModel.uiState.value.facts(tarte))
    }

    @Test
    fun `the subtitle keeps only the parts that exist`() = runTest {
        coEvery { ingredientRepository.getIngredients() } returns Result.success(listOf(rice(250f)))
        coEvery { recipeRepository.refreshRecipes() } returns Result.success(listOf(withRice(400f, null)))
        val noTime = createViewModel().also { advanceUntilIdle() }
        assertEquals("500 kcal", noTime.uiState.value.facts(tarte))

        coEvery { ingredientRepository.getIngredients() } returns Result.success(listOf(rice(null)))
        coEvery { recipeRepository.refreshRecipes() } returns Result.success(listOf(withRice(400f, 35)))
        val noKcal = createViewModel().also { advanceUntilIdle() }
        assertEquals("35 min", noKcal.uiState.value.facts(tarte))

        coEvery { recipeRepository.refreshRecipes() } returns Result.success(listOf(withRice(400f, null)))
        val nothing = createViewModel().also { advanceUntilIdle() }
        assertNull(nothing.uiState.value.facts(tarte))
    }

    @Test
    fun `a recipe the server has not described has no subtitle`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.facts(Recipe(id = "unknown", name = "Autre")))
    }
}
