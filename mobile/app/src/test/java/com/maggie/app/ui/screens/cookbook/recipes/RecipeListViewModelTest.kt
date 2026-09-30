package com.maggie.app.ui.screens.cookbook.recipes

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Recipe
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
import org.junit.Assert.assertEquals
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
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository

    private val recipe = Recipe(id = "recipe-1", name = "Tarte aux pommes")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        recipeRepository = mockk()
        mercureService = mockk()
        authRepository = mockk()

        every { recipeRepository.observeRecipes() } returns flowOf(listOf(recipe))
        coEvery { recipeRepository.refreshRecipes() } returns Result.success(listOf(recipe))
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns emptyFlow()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() =
        RecipeListViewModel(recipeRepository, mercureService, authRepository)

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
}
