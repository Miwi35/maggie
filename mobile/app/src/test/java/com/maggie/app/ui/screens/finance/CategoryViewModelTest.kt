package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.CategoryCreateRequest
import com.maggie.app.data.model.Category
import com.maggie.app.data.repository.CategoryRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var viewModel: CategoryViewModel

    private val sampleCategories = listOf(
        Category(id = "cat-1", name = "Alimentation", obligation = "mandatory"),
        Category(id = "cat-2", name = "Loisirs", obligation = "optional"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        categoryRepository = mockk()
        coEvery { categoryRepository.getCategories() } returns Result.success(sampleCategories)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load populates categories sorted by name`() = runTest {
        viewModel = CategoryViewModel(categoryRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.categories.size)
        assertEquals("Alimentation", state.categories[0].name)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `createCategory dispatches request and refreshes`() = runTest {
        coEvery { categoryRepository.createCategory(any()) } returns Result.success(
            Category(id = "cat-3", name = "Transport", obligation = "mandatory"),
        )
        viewModel = CategoryViewModel(categoryRepository)
        advanceUntilIdle()

        viewModel.createCategory(CategoryCreateRequest(name = "Transport", obligation = "mandatory"))
        advanceUntilIdle()

        coVerify { categoryRepository.createCategory(any()) }
        coVerify(atLeast = 2) { categoryRepository.getCategories() }
    }

    @Test
    fun `deleteCategory removes it from state`() = runTest {
        coEvery { categoryRepository.deleteCategory("cat-1") } returns Result.success(Unit)
        viewModel = CategoryViewModel(categoryRepository)
        advanceUntilIdle()

        viewModel.deleteCategory("cat-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.categories.size)
        assertEquals("cat-2", state.categories[0].id)
    }

    @Test
    fun `load failure sets error`() = runTest {
        coEvery { categoryRepository.getCategories() } returns Result.failure(RuntimeException("boom"))
        viewModel = CategoryViewModel(categoryRepository)
        advanceUntilIdle()

        assertEquals("boom", viewModel.uiState.value.error)
    }
}
