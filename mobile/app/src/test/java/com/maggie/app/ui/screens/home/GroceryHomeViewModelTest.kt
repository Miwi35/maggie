package com.maggie.app.ui.screens.home

import com.maggie.app.data.model.GroceryHomeLine
import com.maggie.app.data.model.GroceryHomeRecipeNeed
import com.maggie.app.data.model.GroceryHomeState
import com.maggie.app.data.model.GroceryHomeStore
import com.maggie.app.data.repository.GroceryCardRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** The Courses card of the Accueil (MAG-563, MAG-590): read once, read again when Mercure says it moved, one test per state. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroceryHomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<GroceryCardRepository>()
    private val changes = MutableSharedFlow<Unit>()

    private fun answer(count: Int, itemName: String = "Lait") = GroceryHomeState(
        count = count,
        forRecipes = listOf(GroceryHomeRecipeNeed("Parmesan", "today", "dinner")),
        stores = listOf(
            GroceryHomeStore(
                id = "s1",
                name = "Supermarché",
                count = count,
                items = listOf(GroceryHomeLine(id = "i1", name = itemName, quantity = 2f, unit = "l")),
            ),
        ),
    )

    private val emptyAnswer = GroceryHomeState()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.changes() } returns changes
        coEvery { repository.read(any()) } returns Result.success(answer(1))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = GroceryHomeViewModel(repository, clock = { Instant.parse("2026-10-23T12:00:00Z") })

    @Test
    fun `the card loads while it reads, then holds the list by shop`() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.state)

        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertFalse(ui.isLoading)
        assertFalse(ui.failed)
        assertEquals("Supermarché", ui.state?.stores?.single()?.name)
        assertEquals("Lait", ui.state?.stores?.single()?.items?.single()?.name)
        assertEquals("Parmesan", ui.state?.forRecipes?.single()?.name)
    }

    @Test
    fun `the card is read for the owner's day, taken from the device clock`() = runTest(dispatcher) {
        viewModel()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.read(LocalDate.of(2026, 10, 23)) }
    }

    @Test
    fun `just after midnight in Paris the card reads the day that has begun there`() = runTest(dispatcher) {
        GroceryHomeViewModel(repository, clock = { Instant.parse("2026-10-23T22:30:00Z") })
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.read(LocalDate.of(2026, 10, 24)) }
    }

    @Test
    fun `a failing read shows the failure, with no list`() = runTest(dispatcher) {
        coEvery { repository.read(any()) } returns Result.failure(RuntimeException("503"))
        val viewModel = viewModel()

        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertTrue(ui.failed)
        assertFalse(ui.isLoading)
        assertNull(ui.state)
    }

    @Test
    fun `retrying after a failure reads again and clears it`() = runTest(dispatcher) {
        coEvery { repository.read(any()) } returns Result.failure(RuntimeException("503"))
        val viewModel = viewModel()
        advanceUntilIdle()

        coEvery { repository.read(any()) } returns Result.success(answer(1))
        viewModel.refresh()
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.failed)
        assertNotNull(viewModel.uiState.value.state)
    }

    @Test
    fun `a change announced by Mercure reads the card again`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        coEvery { repository.read(any()) } returns Result.success(answer(2, itemName = "Beurre"))
        changes.emit(Unit)
        advanceUntilIdle()

        assertEquals("Beurre", viewModel.uiState.value.state?.stores?.single()?.items?.single()?.name)
        coVerify(exactly = 2) { repository.read(any()) }
    }

    @Test
    fun `a refresh that fails after a good read keeps the list as it was`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        coEvery { repository.read(any()) } returns Result.failure(RuntimeException("offline"))
        changes.emit(Unit)
        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertFalse(ui.failed)
        assertFalse(ui.isLoading)
        assertEquals("Lait", ui.state?.stores?.single()?.items?.single()?.name)
        assertEquals(HomeCardStatus.FILLED, ui.status)
    }

    @Test
    fun `a retry that fails keeps the list and flags the failure`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        coEvery { repository.read(any()) } returns Result.failure(RuntimeException("offline"))
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.failed)
        assertEquals("Lait", viewModel.uiState.value.state?.stores?.single()?.items?.single()?.name)
    }

    @Test
    fun `status - loading, then filled, empty, filled again on a push, failed on a retry, loading on retry`() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertEquals(HomeCardStatus.LOADING, viewModel.uiState.value.status)

        advanceUntilIdle()
        assertEquals(HomeCardStatus.FILLED, viewModel.uiState.value.status)

        coEvery { repository.read(any()) } returns Result.success(emptyAnswer)
        changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.EMPTY, viewModel.uiState.value.status)

        coEvery { repository.read(any()) } returns Result.success(answer(1))
        changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.FILLED, viewModel.uiState.value.status)

        coEvery { repository.read(any()) } returns Result.failure(RuntimeException("offline"))
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(HomeCardStatus.ERROR, viewModel.uiState.value.status)

        coEvery { repository.read(any()) } returns Result.success(emptyAnswer)
        viewModel.refresh()
        assertEquals(HomeCardStatus.LOADING, viewModel.uiState.value.status)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.EMPTY, viewModel.uiState.value.status)
    }

    @Test
    fun `status - no list and a failing first read is an error`() = runTest(dispatcher) {
        coEvery { repository.read(any()) } returns Result.failure(RuntimeException("offline"))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(HomeCardStatus.ERROR, viewModel.uiState.value.status)
    }

    @Test
    fun `status - what the meals lack keeps an empty list filled`() = runTest(dispatcher) {
        coEvery { repository.read(any()) } returns Result.success(
            GroceryHomeState(forRecipes = listOf(GroceryHomeRecipeNeed("Parmesan", "today", "dinner"))),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(HomeCardStatus.FILLED, viewModel.uiState.value.status)
    }
}
