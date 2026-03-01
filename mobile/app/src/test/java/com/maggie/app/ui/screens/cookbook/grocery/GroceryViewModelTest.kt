package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.GroceryListRepository
import com.maggie.app.data.repository.ProductRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroceryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var groceryListRepository: GroceryListRepository
    private lateinit var productRepository: ProductRepository
    private lateinit var storeRepository: StoreRepository
    private lateinit var viewModel: GroceryViewModel

    private val store = Store(id = "store-1", name = "Carrefour", visitOrder = 1)

    private val sampleItems = listOf(
        GroceryItem(id = "item-1", customLabel = "Lait", checked = false, store = store),
        GroceryItem(id = "item-2", customLabel = "Pain", checked = true, store = store),
        GroceryItem(id = "item-3", customLabel = "Pommes", checked = false),
    )

    private val sampleList = GroceryList(id = "list-1", items = sampleItems)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        groceryListRepository = mockk()
        productRepository = mockk()
        storeRepository = mockk()

        coEvery { groceryListRepository.getGroceryList() } returns Result.success(sampleList)
        coEvery { productRepository.getProducts() } returns Result.success(emptyList())
        coEvery { storeRepository.getStores() } returns Result.success(emptyList())
        coEvery { groceryListRepository.checkItem(any(), any()) } returns Result.success(Unit)
        coEvery { groceryListRepository.deleteItem(any()) } returns Result.success(Unit)
    }

    private fun createViewModel(): GroceryViewModel {
        return GroceryViewModel(groceryListRepository, productRepository, storeRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load populates store groups`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.groceryList)
        assertEquals(3, state.groceryList!!.items.size)
        assertTrue(state.storeGroups.isNotEmpty())
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `initial load groups items by store`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Two groups: Carrefour (2 items) and unassigned (1 item)
        assertEquals(2, state.storeGroups.size)
        val carrefourGroup = state.storeGroups.find { it.store?.id == "store-1" }
        assertNotNull(carrefourGroup)
        assertEquals(2, carrefourGroup!!.items.size)
        val unassignedGroup = state.storeGroups.find { it.store == null }
        assertNotNull(unassignedGroup)
        assertEquals(1, unassignedGroup!!.items.size)
    }

    @Test
    fun `toggleItemChecked unchecked to checked`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val uncheckedItem = sampleItems[0] // Lait, unchecked
        viewModel.toggleItemChecked(uncheckedItem)
        advanceUntilIdle()

        val updatedItem = viewModel.uiState.value.groceryList!!.items.find { it.id == "item-1" }
        assertTrue(updatedItem!!.checked)
        coVerify { groceryListRepository.checkItem("item-1", true) }
    }

    @Test
    fun `toggleItemChecked checked to unchecked`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val checkedItem = sampleItems[1] // Pain, checked
        viewModel.toggleItemChecked(checkedItem)
        advanceUntilIdle()

        val updatedItem = viewModel.uiState.value.groceryList!!.items.find { it.id == "item-2" }
        assertFalse(updatedItem!!.checked)
        coVerify { groceryListRepository.checkItem("item-2", false) }
    }

    @Test
    fun `toggleItemChecked with null id does nothing`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val nullIdItem = GroceryItem(id = null, customLabel = "Test")
        val stateBefore = viewModel.uiState.value
        viewModel.toggleItemChecked(nullIdItem)
        advanceUntilIdle()

        assertEquals(stateBefore.groceryList, viewModel.uiState.value.groceryList)
        coVerify(exactly = 0) { groceryListRepository.checkItem(any(), any()) }
    }

    @Test
    fun `toggleItemChecked server error triggers refresh`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        coEvery { groceryListRepository.checkItem("item-1", true) } throws RuntimeException("Network error")

        viewModel.toggleItemChecked(sampleItems[0])
        advanceUntilIdle()

        // Refresh was triggered (getGroceryList called again after error)
        coVerify(atLeast = 2) { groceryListRepository.getGroceryList() }
        // After refresh, original state is restored (item-1 unchecked)
        val restoredItem = viewModel.uiState.value.groceryList!!.items.find { it.id == "item-1" }
        assertFalse(restoredItem!!.checked)
    }

    @Test
    fun `deleteItem removes item from state and calls repo`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val itemToDelete = sampleItems[0] // Lait
        viewModel.deleteItem(itemToDelete)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.groceryList!!.items.size)
        assertNull(state.groceryList!!.items.find { it.id == "item-1" })
        coVerify { groceryListRepository.deleteItem("item-1") }
    }

    @Test
    fun `deleteItem with null id does nothing`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val nullIdItem = GroceryItem(id = null, customLabel = "Test")
        val itemCountBefore = viewModel.uiState.value.groceryList!!.items.size
        viewModel.deleteItem(nullIdItem)
        advanceUntilIdle()

        assertEquals(itemCountBefore, viewModel.uiState.value.groceryList!!.items.size)
        coVerify(exactly = 0) { groceryListRepository.deleteItem(any()) }
    }

    @Test
    fun `deleteItem server error sets error and refreshes`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        coEvery { groceryListRepository.deleteItem("item-1") } returns Result.failure(RuntimeException("Server error"))

        viewModel.deleteItem(sampleItems[0])
        advanceUntilIdle()

        // Refresh was triggered (getGroceryList called again after error)
        coVerify(atLeast = 2) { groceryListRepository.getGroceryList() }
        // After refresh, original items are restored
        assertEquals(3, viewModel.uiState.value.groceryList!!.items.size)
    }

    @Test
    fun `refresh after error recovers state`() = runTest {
        // First call fails
        coEvery { groceryListRepository.getGroceryList() } returns Result.failure(RuntimeException("Connection error"))

        viewModel = createViewModel()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.storeGroups.isEmpty())

        // Fix the mock, then refresh
        coEvery { groceryListRepository.getGroceryList() } returns Result.success(sampleList)
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertEquals(3, viewModel.uiState.value.groceryList!!.items.size)
        assertTrue(viewModel.uiState.value.storeGroups.isNotEmpty())
    }
}
