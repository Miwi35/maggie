package com.maggie.app.ui.screens.cookbook.recipes

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.repository.RecipeRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private val events = MutableSharedFlow<MercureEvent>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        recipeRepository = mockk()
        mercureService = mockk()
        authRepository = mockk()
        coEvery { recipeRepository.getRecipe("recipe-1") } returns Result.success(carbonara())
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns events
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.openSheet(): RecipeDetailViewModel {
        val viewModel = RecipeDetailViewModel("recipe-1", recipeRepository, mercureService, authRepository)
        advanceUntilIdle()
        return viewModel
    }

    private suspend fun TestScope.receive(viewModel: RecipeDetailViewModel, data: String) {
        events.emit(MercureEvent(data = data))
        advanceUntilIdle()
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
}
