package com.maggie.app.ui.screens.cookbook.recipes

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.repository.RecipeRepository
import io.mockk.coEvery
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
import org.junit.Before
import org.junit.Test

/** The edit sheet: what is not being typed follows the recipe, what is being typed stays. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeEditViewModelTest {

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

    private fun TestScope.openSheet(): RecipeEditViewModel {
        val viewModel = RecipeEditViewModel("recipe-1", recipeRepository, mercureService, authRepository)
        advanceUntilIdle()
        return viewModel
    }

    private suspend fun TestScope.receive(data: String) {
        events.emit(MercureEvent(data = data))
        advanceUntilIdle()
    }

    @Test
    fun `it subscribes to the user-scoped recipe topic`() = runTest {
        openSheet()

        verify { mercureService.subscribe("/users/user-1/api/recipes/{id}") }
    }

    @Test
    fun `it fills the form with the recipe`() = runTest {
        val viewModel = openSheet()

        assertEquals(RecipeForm.of(carbonara()), viewModel.uiState.value.form)
        assertEquals("pâtes", viewModel.uiState.value.form.tagsText)
        assertEquals(false, viewModel.uiState.value.isLoading)
    }

    @Test
    fun `fields untouched follow a recipe changed elsewhere, with no banner`() = runTest {
        val viewModel = openSheet()

        receive(published(publishedLine(350) + ""","notes":"Avec du poivre","tags":["rapide"]"""))

        val state = viewModel.uiState.value
        assertEquals("350.0", state.form.ingredients.single().quantity)
        assertEquals("Avec du poivre", state.form.notes)
        assertEquals("rapide", state.form.tagsText)
        assertEquals(false, state.changedElsewhere)
    }

    @Test
    fun `a field being edited is kept, the others update, and the banner shows`() = runTest {
        val viewModel = openSheet()
        viewModel.onNotes("Ma version")

        receive(published(""""notes":"Version distante","servings":2"""))

        val state = viewModel.uiState.value
        assertEquals("Ma version", state.form.notes)
        assertEquals("2", state.form.servings)
        assertEquals(true, state.changedElsewhere)
    }

    @Test
    fun `no banner when the value published is the one being typed`() = runTest {
        val viewModel = openSheet()
        viewModel.onNotes("Pareil")

        receive(published(""""notes":"Pareil""""))

        assertEquals("Pareil", viewModel.uiState.value.form.notes)
        assertEquals(false, viewModel.uiState.value.changedElsewhere)
    }

    @Test
    fun `the ingredient list being edited is kept as a whole`() = runTest {
        val viewModel = openSheet()
        val edited = viewModel.uiState.value.form.ingredients.map { it.copy(quantity = "120") }
        viewModel.onIngredients(edited)

        receive(published(publishedLine(350)))

        assertEquals(edited, viewModel.uiState.value.form.ingredients)
        assertEquals(true, viewModel.uiState.value.changedElsewhere)
    }

    @Test
    fun `reloading takes what the recipe now holds and hides the banner`() = runTest {
        val viewModel = openSheet()
        viewModel.onNotes("Ma version")
        receive(published(""""notes":"Version distante""""))

        viewModel.reload()

        assertEquals("Version distante", viewModel.uiState.value.form.notes)
        assertEquals(false, viewModel.uiState.value.changedElsewhere)
    }

    @Test
    fun `a field edited after a change elsewhere is still compared with the latest recipe`() = runTest {
        val viewModel = openSheet()
        receive(published(""""notes":"Version distante""""))

        viewModel.onNotes("Ma version")
        receive(published(""""notes":"Encore une""""))

        assertEquals("Ma version", viewModel.uiState.value.form.notes)
    }

    @Test
    fun `a message about another recipe changes nothing`() = runTest {
        val viewModel = openSheet()

        receive(published(""""notes":"Autre"""", id = "recipe-2"))

        assertEquals("Sans crème", viewModel.uiState.value.form.notes)
        assertEquals(false, viewModel.uiState.value.changedElsewhere)
    }

    @Test
    fun `a message that cannot be read merges the recipe fetched again`() = runTest {
        val viewModel = openSheet()
        coEvery { recipeRepository.getRecipe("recipe-1") } returns Result.success(carbonara(500f))

        receive("not json")

        assertEquals("500.0", viewModel.uiState.value.form.ingredients.single().quantity)
    }

    @Test
    fun `the request sends the form as the sheet shows it`() = runTest {
        val viewModel = openSheet()
        receive(published(publishedLine(350)))

        val request = viewModel.uiState.value.form.toRequest().toString()

        assertEquals(true, request.contains(""""quantity":350.0"""))
        assertEquals(true, request.contains(""""ciqualAlimCode":"9810""""))
    }

    @Test
    fun `a change published while the first load is in flight is not lost`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { recipeRepository.getRecipe("recipe-1") } coAnswers {
            gate.await()
            Result.success(if (calls++ == 0) carbonara() else carbonara(500f))
        }
        val viewModel = RecipeEditViewModel("recipe-1", recipeRepository, mercureService, authRepository)
        runCurrent()

        events.emit(MercureEvent(data = published(publishedLine(500))))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(viewModel.uiState.value.form.ingredients.single().quantity.toFloat(), 500f)
    }

    @Test
    fun `no banner when the field edited here is not the one changed there`() = runTest {
        val viewModel = openSheet()
        viewModel.onName("Ma carbonara")

        receive(published(""""notes":"Version distante""""))

        assertEquals("Ma carbonara", viewModel.uiState.value.form.name)
        assertEquals("Version distante", viewModel.uiState.value.form.notes)
        assertEquals(false, viewModel.uiState.value.changedElsewhere)
    }
}
