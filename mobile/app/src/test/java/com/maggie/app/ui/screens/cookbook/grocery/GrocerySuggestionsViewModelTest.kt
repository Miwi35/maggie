package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.PurchaseSuggestion
import com.maggie.app.data.model.SuggestedProduct
import com.maggie.app.data.repository.GroceryListRepository
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.StoreRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** « Souvent pris » on the grocery list (MAG-495): one test per state transition. */
@OptIn(ExperimentalCoroutinesApi::class)
class GrocerySuggestionsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var groceryListRepository: GroceryListRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository

    private val coffee = PurchaseSuggestion(
        product = SuggestedProduct(id = "p-coffee", name = "Café"),
        everyDays = 14,
        lastBoughtOn = "2026-10-01",
        dueOn = "2026-10-15",
        usualQuantity = 2.0,
        usualUnit = "pack",
    )
    private val rice = PurchaseSuggestion(
        product = SuggestedProduct(id = "p-rice", name = "Riz"),
        everyDays = 30,
        lastBoughtOn = "2026-09-10",
        dueOn = "2026-10-10",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        groceryListRepository = mockk()
        mercureService = mockk()
        authRepository = mockk()

        coEvery { authRepository.getUserId() } returns "user-1"
        every { authRepository.token } returns flowOf("jwt")
        every { mercureService.subscribe(any()) } returns emptyFlow()
        coEvery { groceryListRepository.getGroceryList() } returns Result.success(GroceryList(id = "list-1"))
        coEvery { groceryListRepository.getSuggestions() } returns Result.success(listOf(coffee, rice))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): GroceryViewModel {
        val productRepository = mockk<ProductRepository>()
        val storeRepository = mockk<StoreRepository>()
        coEvery { productRepository.getProducts() } returns Result.success(emptyList())
        coEvery { storeRepository.getStores() } returns Result.success(emptyList())
        return GroceryViewModel(groceryListRepository, productRepository, storeRepository, mercureService, authRepository)
    }

    @Test
    fun `the suggestions load with the list`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf(coffee, rice), viewModel.uiState.value.suggestions)
    }

    @Test
    fun `no suggestion leaves the banner out`() = runTest {
        coEvery { groceryListRepository.getSuggestions() } returns Result.success(emptyList())

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.suggestions.isEmpty())
    }

    @Test
    fun `a failed load keeps the list usable and shows no suggestion`() = runTest {
        coEvery { groceryListRepository.getSuggestions() } returns Result.failure(RuntimeException("HTTP 500"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.suggestions.isEmpty())
        assertNull(viewModel.uiState.value.error)
        assertEquals("list-1", viewModel.uiState.value.groceryList?.id)
    }

    @Test
    fun `adding a suggestion puts it on the list with its usual quantity and refetches`() = runTest {
        coEvery { groceryListRepository.addItem(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
        val viewModel = createViewModel()
        advanceUntilIdle()

        coEvery { groceryListRepository.getSuggestions() } returns Result.success(listOf(rice))
        viewModel.addSuggestion(coffee)
        advanceUntilIdle()

        coVerify { groceryListRepository.addItem("Café", 2f, "pack", null, null, null) }
        coVerify(exactly = 2) { groceryListRepository.getGroceryList() }
        assertEquals(listOf(rice), viewModel.uiState.value.suggestions)
        assertNull(viewModel.uiState.value.suggestionMessage)
    }

    @Test
    fun `the added suggestion leaves the banner before the API answers`() = runTest {
        val answer = CompletableDeferred<Result<Unit>>()
        coEvery { groceryListRepository.addItem(any(), any(), any(), any(), any(), any()) } coAnswers { answer.await() }
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addSuggestion(coffee)
        runCurrent()

        assertEquals(listOf(rice), viewModel.uiState.value.suggestions)
        answer.complete(Result.success(Unit))
        advanceUntilIdle()
    }

    @Test
    fun `a refused addition says so and brings the suggestion back`() = runTest {
        coEvery { groceryListRepository.addItem(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("HTTP 500"))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addSuggestion(coffee)
        advanceUntilIdle()

        assertEquals("Erreur : « Café » n'a pas été ajouté", viewModel.uiState.value.suggestionMessage)
        assertEquals(listOf(coffee, rice), viewModel.uiState.value.suggestions)

        viewModel.dismissSuggestionMessage()
        assertNull(viewModel.uiState.value.suggestionMessage)
    }

    @Test
    fun `pas besoin snoozes the product and refetches`() = runTest {
        coEvery { groceryListRepository.snoozeSuggestion(any()) } returns Result.success(Unit)
        val viewModel = createViewModel()
        advanceUntilIdle()

        coEvery { groceryListRepository.getSuggestions() } returns Result.success(listOf(rice))
        viewModel.snoozeSuggestion(coffee)
        advanceUntilIdle()

        coVerify { groceryListRepository.snoozeSuggestion("p-coffee") }
        coVerify(exactly = 0) { groceryListRepository.addItem(any(), any(), any(), any(), any(), any()) }
        assertEquals(listOf(rice), viewModel.uiState.value.suggestions)
        assertNull(viewModel.uiState.value.suggestionMessage)
    }

    @Test
    fun `a refused pas besoin says so and brings the suggestion back`() = runTest {
        coEvery { groceryListRepository.snoozeSuggestion(any()) } returns Result.failure(RuntimeException("HTTP 404"))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.snoozeSuggestion(coffee)
        advanceUntilIdle()

        assertEquals("Erreur : « Café » n'a pas été écarté", viewModel.uiState.value.suggestionMessage)
        assertEquals(listOf(coffee, rice), viewModel.uiState.value.suggestions)
    }

    @Test
    fun `the suggestions follow the products and the list over Mercure, on one stream of their own`() = runTest {
        val topics = listOf("grocery_lists", "products")
        val flows = topics.associateWith { MutableSharedFlow<MercureEvent>() }
        topics.forEach { topic ->
            every { mercureService.subscribe("/users/user-1/api/$topic/{id}") } returns flows.getValue(topic)
        }
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(listOf(coffee, rice), viewModel.uiState.value.suggestions)

        topics.forEachIndexed { index, topic ->
            val next = if (index % 2 == 0) listOf(rice) else listOf(coffee)
            coEvery { groceryListRepository.getSuggestions() } returns Result.success(next)

            flows.getValue(topic).emit(MercureEvent(data = """{"@id":"/api/$topic/x"}"""))
            advanceUntilIdle()

            assertEquals("after an update on $topic", next, viewModel.uiState.value.suggestions)
        }
        topics.forEach { topic -> verify(exactly = 1) { mercureService.subscribe("/users/user-1/api/$topic/{id}") } }
        // Every stream holds one of the app's 32 connections to the hub for good: past them, a recipe
        // sheet's subscription queues and never connects. An errand's end reaches the list's topic.
        listOf("errands", "recurring_grocery_items").forEach { topic ->
            verify(exactly = 0) { mercureService.subscribe("/users/user-1/api/$topic/{id}") }
        }
    }

    @Test
    fun `a burst of Mercure updates is one fetch`() = runTest {
        val products = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe("/users/user-1/api/products/{id}") } returns products
        val viewModel = createViewModel()
        advanceUntilIdle()
        coVerify(exactly = 1) { groceryListRepository.getSuggestions() }

        repeat(5) { products.emit(MercureEvent(data = """{"@id":"/api/products/p-$it"}""")) }
        advanceUntilIdle()

        coVerify(exactly = 2) { groceryListRepository.getSuggestions() }
        assertEquals(listOf(coffee, rice), viewModel.uiState.value.suggestions)
    }
}
