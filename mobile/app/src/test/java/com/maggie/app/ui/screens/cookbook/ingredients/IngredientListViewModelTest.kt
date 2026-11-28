package com.maggie.app.ui.screens.cookbook.ingredients

import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.repository.IngredientRepository
import io.mockk.coEvery
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IngredientListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<IngredientRepository>()
    private val oignon = Ingredient("1", "Oignon jaune", null, ProductCategory.MEAT, kcalPer100g = 40f, ciqualAlimCode = "20034")
    private val sel = Ingredient("2", "Sel", null, ProductCategory.MEAT)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.getIngredients() } returns Result.success(listOf(oignon, sel))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refresh loads the ingredients`() = runTest {
        val viewModel = IngredientListViewModel(repository)
        advanceUntilIdle()

        assertEquals(listOf(oignon, sel), viewModel.uiState.value.ingredients)
        assertEquals(false, viewModel.uiState.value.isLoading)
    }

    @Test
    fun `search narrows visible`() = runTest {
        val viewModel = IngredientListViewModel(repository)
        advanceUntilIdle()

        viewModel.search("oignon")

        assertEquals(listOf(oignon), viewModel.uiState.value.visible)
    }

    @Test
    fun `filterBy narrows visible`() = runTest {
        val viewModel = IngredientListViewModel(repository)
        advanceUntilIdle()

        viewModel.filterBy(IngredientPill.NONE)

        assertEquals(listOf(sel), viewModel.uiState.value.visible)
    }
}
