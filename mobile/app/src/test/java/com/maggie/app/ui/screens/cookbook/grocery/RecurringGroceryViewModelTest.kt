package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.RecurringFrequency
import com.maggie.app.data.model.RecurringGroceryItem
import com.maggie.app.data.repository.RecurringGroceryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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
class RecurringGroceryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: RecurringGroceryRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository

    private val milk = RecurringGroceryItem(
        id = "rec-milk",
        product = "/api/products/p-milk",
        label = "Lait",
        frequency = RecurringFrequency.WEEKLY,
        nextDueAt = "2026-10-20",
    )
    private val soap = RecurringGroceryItem(
        id = "rec-soap",
        product = "/api/products/p-soap",
        label = "Savon",
        frequency = RecurringFrequency.MONTHLY,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        mercureService = mockk()
        authRepository = mockk()

        coEvery { authRepository.getUserId() } returns "user-1"
        every { authRepository.token } returns flowOf("jwt")
        every { mercureService.subscribe(any()) } returns emptyFlow()
        coEvery { repository.getRecurringItems() } returns Result.success(listOf(soap, milk))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = RecurringGroceryViewModel(repository, mercureService, authRepository)

    @Test
    fun `initial load lists the items sorted by label`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Lait", "Savon"), state.items.map { it.label })
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `nothing is loaded before sign-in`() = runTest {
        every { authRepository.token } returns flowOf(null)

        val viewModel = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getRecurringItems() }
        assertEquals(emptyList<RecurringGroceryItem>(), viewModel.uiState.value.items)
    }

    @Test
    fun `a failed load sets the error and keeps the items`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        coEvery { repository.getRecurringItems() } returns Result.failure(RuntimeException("hors ligne"))

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals("hors ligne", viewModel.uiState.value.error)
        assertEquals(2, viewModel.uiState.value.items.size)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `setting a frequency on a product without one creates it`() = runTest {
        val created = RecurringGroceryItem(
            id = "rec-bread",
            product = "/api/products/p-bread",
            label = "Pain",
            frequency = RecurringFrequency.BIWEEKLY,
        )
        coEvery { repository.createForProduct("p-bread", RecurringFrequency.BIWEEKLY) } returns Result.success(created)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setFrequency("p-bread", RecurringFrequency.BIWEEKLY)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.createForProduct("p-bread", RecurringFrequency.BIWEEKLY) }
        assertEquals(listOf("Lait", "Pain", "Savon"), viewModel.uiState.value.items.map { it.label })
        assertEquals(created, viewModel.uiState.value.forProduct("p-bread"))
    }

    @Test
    fun `setting a frequency on a product that has one patches it and creates nothing`() = runTest {
        val updated = milk.copy(frequency = RecurringFrequency.MONTHLY)
        coEvery { repository.updateFrequency("rec-milk", RecurringFrequency.MONTHLY) } returns Result.success(updated)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setFrequency("p-milk", RecurringFrequency.MONTHLY)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateFrequency("rec-milk", RecurringFrequency.MONTHLY) }
        coVerify(exactly = 0) { repository.createForProduct(any(), any()) }
        assertEquals(RecurringFrequency.MONTHLY, viewModel.uiState.value.forProduct("p-milk")?.frequency)
        assertEquals(2, viewModel.uiState.value.items.size)
    }

    @Test
    fun `setting the frequency it already has calls nothing`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setFrequency("p-milk", RecurringFrequency.WEEKLY)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateFrequency(any(), any()) }
        coVerify(exactly = 0) { repository.createForProduct(any(), any()) }
    }

    @Test
    fun `a stale state never creates a second item for the product`() = runTest {
        // Another device created the item after this one loaded: the lookup before the POST finds it.
        coEvery { repository.getRecurringItems() } returns Result.success(emptyList())
        val viewModel = createViewModel()
        advanceUntilIdle()
        coEvery { repository.getRecurringItems() } returns Result.success(listOf(milk))
        val updated = milk.copy(frequency = RecurringFrequency.BIWEEKLY)
        coEvery { repository.updateFrequency("rec-milk", RecurringFrequency.BIWEEKLY) } returns Result.success(updated)

        viewModel.setFrequency("p-milk", RecurringFrequency.BIWEEKLY)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createForProduct(any(), any()) }
        coVerify(exactly = 1) { repository.updateFrequency("rec-milk", RecurringFrequency.BIWEEKLY) }
        assertEquals(RecurringFrequency.BIWEEKLY, viewModel.uiState.value.forProduct("p-milk")?.frequency)
    }

    @Test
    fun `choosing no frequency deletes the product's item`() = runTest {
        coEvery { repository.delete("rec-milk") } returns Result.success(Unit)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setFrequency("p-milk", null)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.delete("rec-milk") }
        assertNull(viewModel.uiState.value.forProduct("p-milk"))
        assertEquals(listOf("Savon"), viewModel.uiState.value.items.map { it.label })
    }

    @Test
    fun `choosing no frequency on a product without an item calls nothing`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setFrequency("p-other", null)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.delete(any()) }
        coVerify(exactly = 0) { repository.createForProduct(any(), any()) }
    }

    @Test
    fun `changing the frequency from the tab patches the item`() = runTest {
        val updated = soap.copy(frequency = RecurringFrequency.WEEKLY)
        coEvery { repository.updateFrequency("rec-soap", RecurringFrequency.WEEKLY) } returns Result.success(updated)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.changeFrequency(soap, RecurringFrequency.WEEKLY)
        advanceUntilIdle()

        assertEquals(RecurringFrequency.WEEKLY, viewModel.uiState.value.forProduct("p-soap")?.frequency)
    }

    @Test
    fun `removing from the tab deletes the item`() = runTest {
        coEvery { repository.delete("rec-soap") } returns Result.success(Unit)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.remove(soap)
        advanceUntilIdle()

        assertEquals(listOf("Lait"), viewModel.uiState.value.items.map { it.label })
    }

    @Test
    fun `a failed create keeps the items and sets the error`() = runTest {
        coEvery { repository.createForProduct(any(), any()) } returns Result.failure(RuntimeException("refusé"))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setFrequency("p-bread", RecurringFrequency.WEEKLY)
        advanceUntilIdle()

        assertEquals("refusé", viewModel.uiState.value.error)
        assertEquals(2, viewModel.uiState.value.items.size)
    }

    @Test
    fun `a failed patch keeps the previous frequency and sets the error`() = runTest {
        coEvery { repository.updateFrequency(any(), any()) } returns Result.failure(RuntimeException("refusé"))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.changeFrequency(milk, RecurringFrequency.MONTHLY)
        advanceUntilIdle()

        assertEquals("refusé", viewModel.uiState.value.error)
        assertEquals(RecurringFrequency.WEEKLY, viewModel.uiState.value.forProduct("p-milk")?.frequency)
    }

    @Test
    fun `a failed delete keeps the item and sets the error`() = runTest {
        coEvery { repository.delete(any()) } returns Result.failure(RuntimeException("refusé"))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.remove(milk)
        advanceUntilIdle()

        assertEquals("refusé", viewModel.uiState.value.error)
        assertEquals(2, viewModel.uiState.value.items.size)
    }

    @Test
    fun `a failed lookup before the create sets the error and creates nothing`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        coEvery { repository.getRecurringItems() } returns Result.failure(RuntimeException("hors ligne"))

        viewModel.setFrequency("p-bread", RecurringFrequency.WEEKLY)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createForProduct(any(), any()) }
        assertEquals("hors ligne", viewModel.uiState.value.error)
    }

    @Test
    fun `a Mercure event refetches the items`() = runTest {
        val events = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns events
        val viewModel = createViewModel()
        advanceUntilIdle()
        coEvery { repository.getRecurringItems() } returns Result.success(listOf(milk))

        events.emit(MercureEvent(data = """{"@id":"/api/recurring_grocery_items/rec-soap","deleted":true}"""))
        advanceUntilIdle()

        assertEquals(listOf("Lait"), viewModel.uiState.value.items.map { it.label })
    }

    @Test
    fun `it subscribes to the user's recurring items topic`() = runTest {
        createViewModel()
        advanceUntilIdle()

        verify { mercureService.subscribe("/users/user-1/api/recurring_grocery_items/{id}") }
    }

    @Test
    fun `dismissing the error clears it`() = runTest {
        coEvery { repository.delete(any()) } returns Result.failure(RuntimeException("refusé"))
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.remove(milk)
        advanceUntilIdle()

        viewModel.dismissError()

        assertNull(viewModel.uiState.value.error)
    }
}
