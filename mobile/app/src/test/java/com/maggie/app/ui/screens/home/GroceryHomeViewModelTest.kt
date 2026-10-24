package com.maggie.app.ui.screens.home

import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.repository.HomeCardRepository
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Courses card of the Accueil (MAG-563): read once, read again when the API says it moved, one test per state. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroceryHomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<HomeCardRepository>()
    private val changes = MutableSharedFlow<Unit>()

    private fun answer(count: Int, itemName: String = "Lait"): JsonObject = Json.parseToJsonElement(
        """
        {"count":$count,"forRecipes":[{"name":"Parmesan","day":"today","slot":"dinner"}],
         "stores":[{"id":"s1","name":"Supermarché","count":$count,"more":0,
           "items":[{"id":"i1","name":"$itemName","quantity":2,"unit":"l","needed":null}]}],"extra":"ignored"}
        """.trimIndent(),
    ) as JsonObject

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.changes("grocery") } returns changes
        coEvery { repository.state("grocery", HomeDay.TODAY) } returns Result.success(answer(1))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the card loads while it reads, then holds the list by shop`() = runTest(dispatcher) {
        val viewModel = GroceryHomeViewModel(repository)

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
    fun `a failing read shows the failure, with no list`() = runTest(dispatcher) {
        coEvery { repository.state(any(), any()) } returns Result.failure(RuntimeException("503"))
        val viewModel = GroceryHomeViewModel(repository)

        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertTrue(ui.failed)
        assertFalse(ui.isLoading)
        assertNull(ui.state)
    }

    @Test
    fun `retrying after a failure reads again and clears it`() = runTest(dispatcher) {
        coEvery { repository.state(any(), any()) } returns Result.failure(RuntimeException("503"))
        val viewModel = GroceryHomeViewModel(repository)
        advanceUntilIdle()

        coEvery { repository.state("grocery", HomeDay.TODAY) } returns Result.success(answer(1))
        viewModel.refresh()
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.failed)
        assertNotNull(viewModel.uiState.value.state)
    }

    @Test
    fun `a change announced by the API reads the card again`() = runTest(dispatcher) {
        val viewModel = GroceryHomeViewModel(repository)
        advanceUntilIdle()

        coEvery { repository.state("grocery", HomeDay.TODAY) } returns Result.success(answer(2, itemName = "Beurre"))
        changes.emit(Unit)
        advanceUntilIdle()

        assertEquals("Beurre", viewModel.uiState.value.state?.stores?.single()?.items?.single()?.name)
        coVerify(exactly = 2) { repository.state("grocery", HomeDay.TODAY) }
    }

    @Test
    fun `a read that fails after a good one keeps the list and flags the failure`() = runTest(dispatcher) {
        val viewModel = GroceryHomeViewModel(repository)
        advanceUntilIdle()

        coEvery { repository.state(any(), any()) } returns Result.failure(RuntimeException("offline"))
        changes.emit(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.failed)
        assertEquals("Lait", viewModel.uiState.value.state?.stores?.single()?.items?.single()?.name)
    }

    @Test
    fun `the list is read for today whatever the selector says, the card does not follow Demain`() = runTest(dispatcher) {
        GroceryHomeViewModel(repository)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.state(any(), HomeDay.TOMORROW) }
    }

    private val emptyAnswer = Json.parseToJsonElement("""{"count":0,"forRecipes":[],"stores":[]}""") as JsonObject

    @Test
    fun `status - loading, then filled, empty, filled again on a push, failed, loading on retry`() = runTest(dispatcher) {
        val viewModel = GroceryHomeViewModel(repository)
        assertEquals(HomeCardStatus.LOADING, viewModel.uiState.value.status)

        advanceUntilIdle()
        assertEquals(HomeCardStatus.FILLED, viewModel.uiState.value.status)

        coEvery { repository.state("grocery", HomeDay.TODAY) } returns Result.success(emptyAnswer)
        changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.EMPTY, viewModel.uiState.value.status)

        coEvery { repository.state("grocery", HomeDay.TODAY) } returns Result.success(answer(1))
        changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.FILLED, viewModel.uiState.value.status)

        coEvery { repository.state(any(), any()) } returns Result.failure(RuntimeException("offline"))
        changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.ERROR, viewModel.uiState.value.status)

        coEvery { repository.state("grocery", HomeDay.TODAY) } returns Result.success(emptyAnswer)
        viewModel.refresh()
        assertEquals(HomeCardStatus.LOADING, viewModel.uiState.value.status)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.EMPTY, viewModel.uiState.value.status)
    }

    @Test
    fun `status - what the meals lack keeps an empty list filled`() = runTest(dispatcher) {
        coEvery { repository.state("grocery", HomeDay.TODAY) } returns Result.success(
            Json.parseToJsonElement("""{"count":0,"forRecipes":[{"name":"Parmesan","day":"today","slot":"dinner"}],"stores":[]}""") as JsonObject,
        )
        val viewModel = GroceryHomeViewModel(repository)
        advanceUntilIdle()

        assertEquals(HomeCardStatus.FILLED, viewModel.uiState.value.status)
    }
}
