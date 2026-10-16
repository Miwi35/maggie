package com.maggie.app.ui.screens.cookbook.recipes

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.repository.RecipeRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeCreateViewModelTest {

    private lateinit var recipeRepository: RecipeRepository
    private lateinit var viewModel: RecipeCreateViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        recipeRepository = mockk()
        viewModel = RecipeCreateViewModel(recipeRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the sheet opens empty, for four servings`() {
        assertEquals(RecipeForm(servings = "4"), viewModel.uiState.value.form)
    }

    @Test
    fun `steps are added, moved and removed`() {
        viewModel.addStep()
        viewModel.addStep()
        viewModel.addStep()
        viewModel.moveStep(key = 3, delta = -1)
        viewModel.removeStep(key = 1)

        assertEquals(listOf(3, 2), viewModel.uiState.value.form.steps.map { it.key })
    }

    @Test
    fun `the reference example is sent as written`() = runTest {
        viewModel.edit { RecipeForm.of(pastaDinner()) }
        coEvery { recipeRepository.createRecipe(any()) } returns Result.success(pastaDinner())
        var saved = false

        viewModel.save { saved = true }
        advanceUntilIdle()

        assertEquals(true, saved)
        coVerify { recipeRepository.createRecipe(RecipeForm.of(pastaDinner()).toRequest()) }
    }

    @Test
    fun `a 400 naming a step is shown on that step`() = runTest {
        viewModel.edit { RecipeForm.of(pastaDinner()) }
        coEvery { recipeRepository.createRecipe(any()) } returns Result.failure(ApiRefusalException(400, "Step 5 waits for an unknown step."))

        viewModel.save {}
        advanceUntilIdle()

        assertEquals(mapOf(5 to "Step 5 waits for an unknown step."), viewModel.uiState.value.stepErrors)
        assertNull(viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.isSaving)
    }

    @Test
    fun `a refusal naming no step is shown on the sheet`() = runTest {
        coEvery { recipeRepository.createRecipe(any()) } returns Result.failure(ApiRefusalException(400, "The name is required."))

        viewModel.save {}
        advanceUntilIdle()

        assertEquals("The name is required.", viewModel.uiState.value.error)
    }
}
