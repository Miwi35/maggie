package com.maggie.app.ui.screens.grocery

import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.StoreRepository
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
class StoreViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var storeRepository: StoreRepository

    private val carrefour = Store(id = "s-1", name = "Carrefour", visitOrder = 1)
    private val biocoop = Store(id = "s-2", name = "Biocoop", visitOrder = 2)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        storeRepository = mockk()
        coEvery { storeRepository.getStores() } returns Result.success(listOf(carrefour, biocoop))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun renameUpdatesTheList() = runTest {
        coEvery { storeRepository.renameStore("s-1", "Carrefour Market") } returns
            Result.success(carrefour.copy(name = "Carrefour Market"))
        val viewModel = StoreViewModel(storeRepository)
        advanceUntilIdle()

        viewModel.renameStore("s-1", "  Carrefour Market ")
        advanceUntilIdle()

        assertEquals(listOf("Carrefour Market", "Biocoop"), viewModel.uiState.value.stores.map { it.name })
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun renameFailureKeepsTheNameAndShowsTheError() = runTest {
        coEvery { storeRepository.renameStore(any(), any()) } returns Result.failure(RuntimeException("refusé"))
        val viewModel = StoreViewModel(storeRepository)
        advanceUntilIdle()

        viewModel.renameStore("s-1", "Autre")
        advanceUntilIdle()

        assertEquals(listOf("Carrefour", "Biocoop"), viewModel.uiState.value.stores.map { it.name })
        assertEquals("refusé", viewModel.uiState.value.error)
    }

    @Test
    fun blankRenameIsNotSent() = runTest {
        val viewModel = StoreViewModel(storeRepository)
        advanceUntilIdle()

        viewModel.renameStore("s-1", "   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { storeRepository.renameStore(any(), any()) }
    }
}
