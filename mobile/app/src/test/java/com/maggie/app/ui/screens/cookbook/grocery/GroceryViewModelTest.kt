package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.api.ReorderEntry
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.GroceryListRepository
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.StoreRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
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
    private lateinit var mercureService: MercureService
    private lateinit var viewModel: GroceryViewModel

    private val store = Store(id = "store-1", name = "Carrefour", visitOrder = 1)

    private val sampleItems = listOf(
        GroceryItem(id = "item-1", customLabel = "Lait", checked = false, store = store, position = 0),
        GroceryItem(id = "item-2", customLabel = "Pain", checked = true, store = store, position = 1),
        GroceryItem(id = "item-3", customLabel = "Pommes", checked = false, position = 0),
    )

    private val sampleList = GroceryList(id = "list-1", items = sampleItems)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        groceryListRepository = mockk()
        productRepository = mockk()
        storeRepository = mockk()
        mercureService = mockk()

        every { mercureService.subscribe(any()) } returns emptyFlow()
        coEvery { groceryListRepository.getGroceryList() } returns Result.success(sampleList)
        coEvery { productRepository.getProducts() } returns Result.success(emptyList())
        coEvery { storeRepository.getStores() } returns Result.success(emptyList())
        coEvery { groceryListRepository.checkItem(any(), any()) } returns Result.success(Unit)
        coEvery { groceryListRepository.deleteItem(any()) } returns Result.success(Unit)
        coEvery { groceryListRepository.reorderItems(any()) } returns Result.success(Unit)
    }

    private fun createViewModel(): GroceryViewModel {
        return GroceryViewModel(groceryListRepository, productRepository, storeRepository, mercureService)
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
    fun `addItem passes category to repository`() = runTest {
        coEvery { groceryListRepository.addItem(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addItem("Yaourt", 4f, "piece", null, null, "dairy")
        advanceUntilIdle()

        coVerify {
            groceryListRepository.addItem("Yaourt", 4f, "piece", null, null, "dairy")
        }
        assertFalse(viewModel.uiState.value.showAddSheet)
    }

    @Test
    fun `addItem without category passes null`() = runTest {
        coEvery { groceryListRepository.addItem(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addItem("Lait", 1f, "l", "store-1")
        advanceUntilIdle()

        coVerify {
            groceryListRepository.addItem("Lait", 1f, "l", "store-1", null, null)
        }
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
    fun `startSelection enters selection mode with item selected`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startSelection("item-1")

        val state = viewModel.uiState.value
        assertTrue(state.isSelecting)
        assertEquals(setOf("item-1"), state.selectedIds)
    }

    @Test
    fun `toggleSelection adds and removes items`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startSelection("item-1")
        viewModel.toggleSelection("item-2")

        var state = viewModel.uiState.value
        assertEquals(setOf("item-1", "item-2"), state.selectedIds)

        viewModel.toggleSelection("item-1")
        state = viewModel.uiState.value
        assertEquals(setOf("item-2"), state.selectedIds)
        assertTrue(state.isSelecting)
    }

    @Test
    fun `toggleSelection exits selection mode when last item deselected`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startSelection("item-1")
        viewModel.toggleSelection("item-1")

        val state = viewModel.uiState.value
        assertFalse(state.isSelecting)
        assertTrue(state.selectedIds.isEmpty())
    }

    @Test
    fun `clearSelection resets selection state`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startSelection("item-1")
        viewModel.toggleSelection("item-2")
        viewModel.clearSelection()

        val state = viewModel.uiState.value
        assertFalse(state.isSelecting)
        assertTrue(state.selectedIds.isEmpty())
    }

    @Test
    fun `checkSelectedItems marks items as checked and clears selection`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startSelection("item-1") // Lait, unchecked
        viewModel.toggleSelection("item-3") // Pommes, unchecked
        viewModel.checkSelectedItems()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSelecting)
        assertTrue(state.selectedIds.isEmpty())
        assertTrue(state.groceryList!!.items.find { it.id == "item-1" }!!.checked)
        assertTrue(state.groceryList!!.items.find { it.id == "item-3" }!!.checked)
        coVerify { groceryListRepository.checkItem("item-1", true) }
        coVerify { groceryListRepository.checkItem("item-3", true) }
    }

    @Test
    fun `checkSelectedItems server error triggers refresh`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        coEvery { groceryListRepository.checkItem("item-1", true) } throws RuntimeException("Network error")

        viewModel.startSelection("item-1")
        viewModel.checkSelectedItems()
        advanceUntilIdle()

        coVerify(atLeast = 2) { groceryListRepository.getGroceryList() }
        val restoredItem = viewModel.uiState.value.groceryList!!.items.find { it.id == "item-1" }
        assertFalse(restoredItem!!.checked)
    }

    @Test
    fun `deleteSelectedItems removes items and clears selection`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startSelection("item-1")
        viewModel.toggleSelection("item-3")
        viewModel.deleteSelectedItems()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSelecting)
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(1, state.groceryList!!.items.size)
        assertEquals("item-2", state.groceryList!!.items[0].id)
        coVerify { groceryListRepository.deleteItem("item-1") }
        coVerify { groceryListRepository.deleteItem("item-3") }
    }

    @Test
    fun `deleteSelectedItems server error triggers refresh`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        coEvery { groceryListRepository.deleteItem("item-1") } returns Result.failure(RuntimeException("Server error"))

        viewModel.startSelection("item-1")
        viewModel.deleteSelectedItems()
        advanceUntilIdle()

        coVerify(atLeast = 2) { groceryListRepository.getGroceryList() }
        assertEquals(3, viewModel.uiState.value.groceryList!!.items.size)
    }

    @Test
    fun `mercure event with items updates state directly`() = runTest {
        val mercureFlow = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns mercureFlow

        viewModel = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 1) { groceryListRepository.getGroceryList() }

        // Emit Mercure event with full items payload
        val payload = """{"@id":"/api/grocery_lists/list-1","items":[
            {"id":"item-1","label":"Lait","checked":true,"source":"manual","position":0},
            {"id":"item-2","label":"Pain","checked":true,"source":"manual","position":1},
            {"id":"item-3","label":"Pommes","checked":false,"source":"manual","position":0}
        ]}"""
        mercureFlow.emit(MercureEvent(data = payload))
        advanceUntilIdle()

        // State updated directly — no refresh call
        coVerify(exactly = 1) { groceryListRepository.getGroceryList() }
        val item1 = viewModel.uiState.value.groceryList!!.items.find { it.id == "item-1" }
        assertTrue(item1!!.checked)
    }

    @Test
    fun `mercure event without items falls back to refresh`() = runTest {
        val mercureFlow = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns mercureFlow

        viewModel = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 1) { groceryListRepository.getGroceryList() }

        // Emit Mercure event without items (e.g. delete event)
        mercureFlow.emit(MercureEvent(data = """{"@id":"/api/grocery_lists/list-1","deleted":true}"""))
        advanceUntilIdle()

        // Falls back to refresh
        coVerify(exactly = 2) { groceryListRepository.getGroceryList() }
    }

    @Test
    fun `mercure reorder patch updates positions`() = runTest {
        val mercureFlow = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns mercureFlow

        viewModel = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 1) { groceryListRepository.getGroceryList() }

        // Emit reorder patch — swap item-1 and item-2 positions
        val payload = """{"@id":"/api/grocery_lists/list-1","action":"reorder","items":[
            {"id":"item-1","position":1},
            {"id":"item-2","position":0}
        ]}"""
        mercureFlow.emit(MercureEvent(data = payload))
        advanceUntilIdle()

        // No refresh — positions updated inline
        coVerify(exactly = 1) { groceryListRepository.getGroceryList() }
        val items = viewModel.uiState.value.groceryList!!.items
        assertEquals(1, items.find { it.id == "item-1" }!!.position)
        assertEquals(0, items.find { it.id == "item-2" }!!.position)
    }

    @Test
    fun `mercure check patch updates item checked state`() = runTest {
        val mercureFlow = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns mercureFlow

        viewModel = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 1) { groceryListRepository.getGroceryList() }

        // Emit check patch — check item-1
        val payload = """{"@id":"/api/grocery_lists/list-1","action":"check","itemId":"item-1","checked":true}"""
        mercureFlow.emit(MercureEvent(data = payload))
        advanceUntilIdle()

        // No refresh — checked state updated inline
        coVerify(exactly = 1) { groceryListRepository.getGroceryList() }
        assertTrue(viewModel.uiState.value.groceryList!!.items.find { it.id == "item-1" }!!.checked)
    }

    @Test
    fun `mercure remove patch removes item`() = runTest {
        val mercureFlow = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns mercureFlow

        viewModel = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 1) { groceryListRepository.getGroceryList() }
        assertEquals(3, viewModel.uiState.value.groceryList!!.items.size)

        // Emit remove patch — remove item-1
        val payload = """{"@id":"/api/grocery_lists/list-1","action":"remove","itemId":"item-1"}"""
        mercureFlow.emit(MercureEvent(data = payload))
        advanceUntilIdle()

        // No refresh — item removed inline
        coVerify(exactly = 1) { groceryListRepository.getGroceryList() }
        assertEquals(2, viewModel.uiState.value.groceryList!!.items.size)
        assertNull(viewModel.uiState.value.groceryList!!.items.find { it.id == "item-1" })
    }

    @Test
    fun `mercure subscription uses correct topic`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        verify { mercureService.subscribe("/users/{userId}/api/grocery_lists/{id}") }
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

    @Test
    fun `moveItem reorders items within store group`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        // Carrefour group has item-1 (pos 0) and item-2 (pos 1)
        viewModel.moveItem("store-1", 0, 1)
        advanceUntilIdle()

        val carrefourGroup = viewModel.uiState.value.storeGroups.find { it.store?.id == "store-1" }
        assertNotNull(carrefourGroup)
        // After move: item-2 should be first (position 0), item-1 second (position 1)
        assertEquals("item-2", carrefourGroup!!.items[0].id)
        assertEquals("item-1", carrefourGroup.items[1].id)
    }

    @Test
    fun `moveItem calls repository reorderItems`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.moveItem("store-1", 0, 1)
        advanceUntilIdle()

        coVerify { groceryListRepository.reorderItems(any()) }
    }

    @Test
    fun `moveItem server error triggers refresh`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        coEvery { groceryListRepository.reorderItems(any()) } returns Result.failure(RuntimeException("Network error"))

        viewModel.moveItem("store-1", 0, 1)
        advanceUntilIdle()

        // Refresh was triggered after error
        coVerify(atLeast = 2) { groceryListRepository.getGroceryList() }
    }

    @Test
    fun `buildStoreGroups sorts items by position`() = runTest {
        val itemsWithPositions = listOf(
            GroceryItem(id = "item-a", label = "Beurre", customLabel = "Beurre", store = store, position = 2),
            GroceryItem(id = "item-b", label = "Fromage", customLabel = "Fromage", store = store, position = 0),
            GroceryItem(id = "item-c", label = "Yaourt", customLabel = "Yaourt", store = store, position = 1),
        )
        val listWithPositions = GroceryList(id = "list-1", items = itemsWithPositions)
        coEvery { groceryListRepository.getGroceryList() } returns Result.success(listWithPositions)

        viewModel = createViewModel()
        advanceUntilIdle()

        val carrefourGroup = viewModel.uiState.value.storeGroups.find { it.store?.id == "store-1" }
        assertNotNull(carrefourGroup)
        assertEquals("Fromage", carrefourGroup!!.items[0].label)
        assertEquals("Yaourt", carrefourGroup.items[1].label)
        assertEquals("Beurre", carrefourGroup.items[2].label)
    }
}
