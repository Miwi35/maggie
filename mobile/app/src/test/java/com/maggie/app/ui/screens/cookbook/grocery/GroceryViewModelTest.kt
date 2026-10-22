package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.api.EndErrandRemainingItem
import com.maggie.app.data.api.EndErrandResponse
import com.maggie.app.data.api.EndErrandRestockedProduct
import com.maggie.app.data.api.ReorderEntry
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.Need
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.RecipeNeedMeal
import com.maggie.app.data.model.RecipeNeedResponse
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
    private lateinit var authRepository: AuthRepository
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
        authRepository = mockk()

        coEvery { authRepository.getUserId() } returns "user-1"
        every { authRepository.token } returns flowOf("jwt")
        every { mercureService.subscribe(any()) } returns emptyFlow()
        coEvery { groceryListRepository.getGroceryList() } returns Result.success(sampleList)
        coEvery { productRepository.getProducts() } returns Result.success(emptyList())
        coEvery { storeRepository.getStores() } returns Result.success(emptyList())
        coEvery { groceryListRepository.checkItem(any(), any()) } returns Result.success(Unit)
        coEvery { groceryListRepository.deleteItem(any()) } returns Result.success(Unit)
        coEvery { groceryListRepository.reorderItems(any()) } returns Result.success(Unit)
        coEvery { groceryListRepository.getSuggestions() } returns Result.success(emptyList())
    }

    private fun createViewModel(): GroceryViewModel {
        return GroceryViewModel(groceryListRepository, productRepository, storeRepository, mercureService, authRepository)
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
    fun `items deferred to a later day are hidden whatever the date format`() = runTest {
        val today = java.time.LocalDate.now()
        val tomorrow = today.plusDays(1)
        val items = listOf(
            GroceryItem(id = "due-rest", customLabel = "Du", store = store, buyAfter = "${today.minusDays(1)}T00:00:00+00:00"),
            GroceryItem(id = "today-rest", customLabel = "Aujourd'hui", store = store, buyAfter = "${today}T00:00:00+00:00"),
            GroceryItem(id = "later-rest", customLabel = "Reporte REST", store = store, buyAfter = "${tomorrow}T00:00:00+00:00"),
            GroceryItem(id = "later-plain", customLabel = "Reporte Mercure", store = store, buyAfter = tomorrow.toString()),
            GroceryItem(id = "no-date", customLabel = "Sans date", store = store),
        )
        coEvery { groceryListRepository.getGroceryList() } returns Result.success(GroceryList(id = "list-1", items = items))

        viewModel = createViewModel()
        advanceUntilIdle()

        val shown = viewModel.uiState.value.storeGroups.flatMap { it.items }.map { it.id }
        assertEquals(listOf("due-rest", "today-rest", "no-date"), shown)
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
    fun `addItem with packagings sends them and no quantity`() = runTest {
        coEvery { groceryListRepository.addItem(any(), any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addItem("Pâtes sèches", null, null, null, packagings = 2)
        advanceUntilIdle()

        coVerify { groceryListRepository.addItem("Pâtes sèches", null, null, null, null, null, 2) }
        assertFalse(viewModel.uiState.value.showAddSheet)
    }

    private val riceNeed = RecipeNeedResponse(
        productId = "product-riz",
        recipeNeed = Need(800.0, "g"),
        needs = listOf(Need(800.0, "g")),
        meals = listOf(RecipeNeedMeal(mealId = "m1", name = "Riz au curry", date = "2026-10-24", quantity = 800.0, unit = "g")),
    )

    @Test
    fun `choosing a product reads the week's recipe need for it`() = runTest {
        coEvery { groceryListRepository.getRecipeNeed("product-riz") } returns Result.success(riceNeed)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadRecipeNeed("product-riz")
        advanceUntilIdle()

        assertEquals(riceNeed, viewModel.uiState.value.recipeNeed)
    }

    @Test
    fun `a product no meal needs leaves no need to show`() = runTest {
        coEvery { groceryListRepository.getRecipeNeed("product-riz") } returns Result.success(RecipeNeedResponse(productId = "product-riz"))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadRecipeNeed("product-riz")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.recipeNeed)
    }

    @Test
    fun `a failed read leaves the sheet without the box`() = runTest {
        coEvery { groceryListRepository.getRecipeNeed(any()) } returns Result.failure(RuntimeException("500"))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadRecipeNeed("product-riz")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.recipeNeed)
        assertNull(viewModel.uiState.value.addError)
    }

    @Test
    fun `choosing another product or a free label drops the need of the previous one`() = runTest {
        coEvery { groceryListRepository.getRecipeNeed("product-riz") } returns Result.success(riceNeed)
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.loadRecipeNeed("product-riz")
        advanceUntilIdle()

        viewModel.loadRecipeNeed(null)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.recipeNeed)
    }

    @Test
    fun `closing the sheet forgets the need and the error`() = runTest {
        coEvery { groceryListRepository.getRecipeNeed("product-riz") } returns Result.success(riceNeed)
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.showAddSheet()
        viewModel.loadRecipeNeed("product-riz")
        advanceUntilIdle()

        viewModel.hideAddSheet()

        assertNull(viewModel.uiState.value.recipeNeed)
        assertNull(viewModel.uiState.value.addError)
        assertFalse(viewModel.uiState.value.showAddSheet)
    }

    @Test
    fun `adding a line closes the sheet and forgets the need`() = runTest {
        coEvery { groceryListRepository.getRecipeNeed("product-riz") } returns Result.success(riceNeed)
        coEvery { groceryListRepository.addItem(any(), any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.showAddSheet()
        viewModel.loadRecipeNeed("product-riz")
        advanceUntilIdle()

        viewModel.addItem("Riz basmati", null, null, null)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showAddSheet)
        assertNull(viewModel.uiState.value.recipeNeed)
        assertNull(viewModel.uiState.value.addError)
    }

    @Test
    fun `a line the API refuses keeps the sheet open with the reason`() = runTest {
        coEvery { groceryListRepository.addItem(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("Quantité et conditionnements ensemble"))
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.showAddSheet()

        viewModel.addItem("Riz basmati", 1f, "g", null, packagings = 2)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showAddSheet)
        assertEquals("Quantité et conditionnements ensemble", viewModel.uiState.value.addError)
    }

    @Test
    fun `opening the sheet again clears the previous refusal`() = runTest {
        coEvery { groceryListRepository.addItem(any(), any(), any(), any(), any(), any(), any()) } returns Result.failure(RuntimeException("400"))
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.addItem("Riz basmati", 1f, "g", null, packagings = 2)
        advanceUntilIdle()

        viewModel.showAddSheet()

        assertNull(viewModel.uiState.value.addError)
    }

    @Test
    fun `updateItem with packagings sends them and no quantity`() = runTest {
        coEvery { groceryListRepository.editItem(any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateItem("item-1", "Pâtes sèches", null, null, null, null, null, packagings = 3)
        advanceUntilIdle()

        coVerify { groceryListRepository.editItem("item-1", "Pâtes sèches", null, null, null, null, null, 3) }
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
    fun `finishing a store with leftovers announces the restocked products in the sheet`() = runTest {
        coEvery { groceryListRepository.endErrand("store-1") } returns Result.success(
            EndErrandResponse(
                success = true,
                remainingItems = listOf(EndErrandRemainingItem(id = "item-1", label = "Lait")),
                remainingCount = 1,
                restockedProducts = listOf(EndErrandRestockedProduct(id = "p1", name = "Riz", stockState = "in_stock")),
            ),
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.finishStore("store-1", "Carrefour")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Carrefour", state.pendingFinishStoreName)
        assertEquals(1, state.pendingFinishRestockedCount)
        assertEquals("1 produit repassé en stock", restockedProductsMessage(state.pendingFinishRestockedCount))
        assertNull(state.restockedToast)
    }

    @Test
    fun `finishing a store without leftovers announces the restocked products once`() = runTest {
        coEvery { groceryListRepository.endErrand("store-1") } returns Result.success(
            EndErrandResponse(
                success = true,
                restockedProducts = listOf(
                    EndErrandRestockedProduct(id = "p1", name = "Riz"),
                    EndErrandRestockedProduct(id = "p2", name = "Pâtes"),
                ),
            ),
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.finishStore("store-1", "Carrefour")
        advanceUntilIdle()

        assertEquals("2 produits repassés en stock", viewModel.uiState.value.restockedToast)
        assertNull(viewModel.uiState.value.pendingFinishStoreName)

        viewModel.consumeRestockedToast()
        assertNull(viewModel.uiState.value.restockedToast)
    }

    @Test
    fun `finishing a store that restocked nothing announces nothing`() = runTest {
        coEvery { groceryListRepository.endErrand("store-1") } returns Result.success(EndErrandResponse(success = true))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.finishStore("store-1", "Carrefour")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.restockedToast)
        assertEquals(0, viewModel.uiState.value.pendingFinishRestockedCount)
        assertNull(restockedProductsMessage(0))
    }

    @Test
    fun `dismissing the leftovers sheet clears the restocked count`() = runTest {
        coEvery { groceryListRepository.endErrand("store-1") } returns Result.success(
            EndErrandResponse(
                success = true,
                remainingItems = listOf(EndErrandRemainingItem(id = "item-1", label = "Lait")),
                restockedProducts = listOf(EndErrandRestockedProduct(id = "p1", name = "Riz")),
            ),
        )
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.finishStore("store-1", "Carrefour")
        advanceUntilIdle()

        viewModel.dismissPendingFinish()

        assertEquals(0, viewModel.uiState.value.pendingFinishRestockedCount)
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

        // The user id is substituted; only {id} stays a placeholder, because
        // it is a URI-template wildcard in the topic selector. The literal
        // "{userId}" that used to be here matched nothing the API publishes.
        verify { mercureService.subscribe("/users/user-1/api/grocery_lists/{id}") }
    }

    @Test
    fun `nothing is loaded nor subscribed before the user signs in`() = runTest {
        every { authRepository.token } returns MutableStateFlow<String?>(null)

        viewModel = createViewModel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.groceryList)
        coVerify(exactly = 0) { groceryListRepository.getGroceryList() }
        verify(exactly = 0) { mercureService.subscribe(any()) }
    }

    @Test
    fun `the list loads and the subscription opens once the user signs in`() = runTest {
        val token = MutableStateFlow<String?>(null)
        every { authRepository.token } returns token

        viewModel = createViewModel()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.groceryList)

        token.value = "jwt"
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.groceryList!!.items.size)
        verify(exactly = 1) { mercureService.subscribe("/users/user-1/api/grocery_lists/{id}") }
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

    private fun stubDeferred(vararg items: GroceryItem) {
        coEvery { groceryListRepository.getGroceryList() } returns
            Result.success(GroceryList(id = "list-1", items = items.toList()))
    }

    @Test
    fun `deferred items are exposed as later items with their date`() = runTest {
        val today = java.time.LocalDate.now()
        stubDeferred(
            GroceryItem(id = "now", label = "Lait", store = store),
            GroceryItem(id = "rest", label = "Oeufs", store = store, buyAfter = "${today.plusDays(3)}T00:00:00+00:00"),
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        val later = viewModel.uiState.value.laterItems
        assertEquals(listOf("rest"), later.map { it.id })
        assertEquals("${today.plusDays(3)}T00:00:00+00:00", later[0].buyAfter)
        assertEquals(listOf("now"), viewModel.uiState.value.storeGroups.flatMap { it.items }.map { it.id })
    }

    @Test
    fun `later items are sorted by date then label`() = runTest {
        val today = java.time.LocalDate.now()
        stubDeferred(
            GroceryItem(id = "c", label = "Citrons", buyAfter = today.plusDays(5).toString()),
            GroceryItem(id = "b", label = "Beurre", buyAfter = "${today.plusDays(2)}T00:00:00+00:00"),
            GroceryItem(id = "a", label = "Ail", buyAfter = today.plusDays(5).toString()),
            GroceryItem(id = "z", label = "Zeste", buyAfter = today.plusDays(2).toString()),
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf("b", "z", "a", "c"), viewModel.uiState.value.laterItems.map { it.id })
    }

    @Test
    fun `later items are empty when nothing is deferred`() = runTest {
        val today = java.time.LocalDate.now()
        stubDeferred(
            GroceryItem(id = "now", label = "Lait", store = store),
            GroceryItem(id = "due", label = "Pain", store = store, buyAfter = today.minusDays(1).toString()),
            GroceryItem(id = "today", label = "Riz", store = store, buyAfter = "${today}T00:00:00+00:00"),
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.laterItems.isEmpty())
    }

    @Test
    fun `a list holding only deferred items has no store groups but has later items`() = runTest {
        stubDeferred(GroceryItem(id = "x", label = "Sel", buyAfter = java.time.LocalDate.now().plusDays(1).toString()))

        viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.storeGroups.isEmpty())
        assertEquals(1, viewModel.uiState.value.laterItems.size)
    }

    @Test
    fun `counters ignore deferred items even when checked`() = runTest {
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        stubDeferred(
            GroceryItem(id = "a", label = "Lait", store = store, checked = true),
            GroceryItem(id = "b", label = "Pain", store = store),
            GroceryItem(id = "c", label = "Oeufs", store = store, checked = true, buyAfter = tomorrow.toString()),
            GroceryItem(id = "d", label = "Sel", store = store, buyAfter = tomorrow.toString()),
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.checkedCount)
        assertEquals(2, viewModel.uiState.value.totalCount)
    }

    @Test
    fun `later items follow a Mercure update that defers an item`() = runTest {
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        stubDeferred(GroceryItem(id = "a", label = "Lait", store = store))
        val updates = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns updates

        viewModel = createViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.laterItems.isEmpty())

        updates.emit(
            MercureEvent(
                data = """{"items":[{"id":"a","label":"Lait","buyAfter":"$tomorrow"}]}""",
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("a"), viewModel.uiState.value.laterItems.map { it.id })
        assertTrue(viewModel.uiState.value.storeGroups.isEmpty())
    }

    @Test
    fun `buy after date is formatted day month year for a date and a date-time`() {
        assertEquals("07/10/2026", formatBuyAfter("2026-10-07"))
        assertEquals("07/10/2026", formatBuyAfter("2026-10-07T00:00:00+00:00"))
        assertNull(formatBuyAfter("not a date"))
    }

    // --- MAG-291 / MAG-299: − / + and typed count of packagings on the line ---

    private val rice = Product(
        id = "product-riz",
        name = "Riz",
        category = ProductCategory.GRAIN,
        packagingUnit = CookbookUnit.PACK,
        packagingSize = 500f,
        packagingSizeUnit = CookbookUnit.G,
    )

    private val quantityItems = listOf(
        GroceryItem(id = "item-riz", label = "Riz", product = rice, quantity = 1f, unit = CookbookUnit.PACK, store = store, position = 0),
        GroceryItem(id = "item-semoule", label = "Semoule", product = rice, quantity = 2f, unit = CookbookUnit.PACK, store = store, position = 1),
        GroceryItem(id = "item-farine", label = "Farine", quantity = 500f, unit = CookbookUnit.G, store = store, position = 2),
        GroceryItem(id = "item-sel", label = "Sel", store = store, position = 3),
    )

    private fun stubQuantities(editResult: Result<Unit> = Result.success(Unit)) {
        coEvery { groceryListRepository.getGroceryList() } returns
            Result.success(GroceryList(id = "list-1", items = quantityItems))
        coEvery { groceryListRepository.editItem(any(), any(), any(), any(), any(), any(), any(), any()) } returns editResult
    }

    private fun quantityOf(id: String): Float? =
        viewModel.uiState.value.groceryList!!.items.first { it.id == id }.quantity

    private fun item(id: String): GroceryItem = viewModel.uiState.value.groceryList!!.items.first { it.id == id }

    // The packagings the stepper sent for [id]: it sends a count of packagings, never a quantity.
    private fun sentQuantities(id: String): List<Int?> {
        val sent = mutableListOf<Int?>()
        coVerify(atLeast = 0) {
            groceryListRepository.editItem(eq(id), any(), isNull(), isNull(), any(), any(), any(), captureNullable(sent))
        }
        return sent
    }

    @Test
    fun `plus shows the next count at once and saves it after the delay`() = runTest {
        stubQuantities()
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.incrementQuantity(item("item-riz"))
        runCurrent()

        assertEquals(2f, quantityOf("item-riz"))
        assertEquals(emptyList<Int?>(), sentQuantities("item-riz"))

        advanceTimeBy(QUANTITY_SAVE_DELAY_MS + 1)
        runCurrent()

        assertEquals(listOf<Int?>(2), sentQuantities("item-riz"))
        assertEquals(2f, quantityOf("item-riz"))
        assertNull(viewModel.uiState.value.quantityMessage)
    }

    private val wantedItems = listOf(
        GroceryItem(
            id = "item-riz", label = "Riz", product = rice, quantity = 3f, unit = CookbookUnit.PACK, store = store, position = 0,
            desiredQuantity = 1400.0, desiredUnit = CookbookUnit.G,
        ),
    )

    private fun stubWanted(editResult: Result<Unit> = Result.success(Unit)) {
        coEvery { groceryListRepository.getGroceryList() } returns Result.success(GroceryList(id = "list-1", items = wantedItems))
        coEvery { groceryListRepository.editItem(any(), any(), any(), any(), any(), any(), any(), any()) } returns editResult
    }

    @Test
    fun `plus on a line with a wanted quantity sends packagings and keeps what was wanted`() = runTest {
        stubWanted()
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.incrementQuantity(item("item-riz"))
        runCurrent()

        assertEquals(4f, quantityOf("item-riz"))
        assertEquals(1400.0, item("item-riz").desiredQuantity)

        advanceUntilIdle()

        assertEquals(listOf<Int?>(4), sentQuantities("item-riz"))
        coVerify { groceryListRepository.editItem("item-riz", null, null, null, null, null, null, 4) }
    }

    @Test
    fun `minus below what the wanted quantity needs lowers it at once, as the API does`() = runTest {
        stubWanted()
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.decrementQuantity(item("item-riz"))
        viewModel.decrementQuantity(item("item-riz"))
        runCurrent()

        assertEquals(1f, quantityOf("item-riz"))
        assertEquals(500.0, item("item-riz").desiredQuantity)
        advanceUntilIdle()
        assertEquals(listOf<Int?>(1), sentQuantities("item-riz"))
    }

    @Test
    fun `an API refusal puts back the count and the wanted quantity`() = runTest {
        stubWanted(editResult = Result.failure(RuntimeException("400")))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.decrementQuantity(item("item-riz"))
        viewModel.decrementQuantity(item("item-riz"))
        runCurrent()
        assertEquals(500.0, item("item-riz").desiredQuantity)

        advanceUntilIdle()

        assertEquals(3f, quantityOf("item-riz"))
        assertEquals(1400.0, item("item-riz").desiredQuantity)
        assertEquals(CookbookUnit.G, item("item-riz").desiredUnit)
    }

    @Test
    fun `two taps on plus are one request carrying 3 packs`() = runTest {
        stubQuantities()
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.incrementQuantity(item("item-riz"))
        runCurrent()
        viewModel.incrementQuantity(item("item-riz"))
        runCurrent()
        assertEquals(3f, quantityOf("item-riz"))

        advanceUntilIdle()

        assertEquals(listOf<Int?>(3), sentQuantities("item-riz"))
    }

    @Test
    fun `plus plus minus is one request carrying the net count`() = runTest {
        stubQuantities()
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.incrementQuantity(item("item-riz"))
        viewModel.incrementQuantity(item("item-riz"))
        viewModel.decrementQuantity(item("item-riz"))
        advanceUntilIdle()

        assertEquals(listOf<Int?>(2), sentQuantities("item-riz"))
    }

    @Test
    fun `minus takes one pack off, never below one, and sends nothing at the minimum`() = runTest {
        stubQuantities()
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.decrementQuantity(item("item-riz"))
        viewModel.decrementQuantity(item("item-semoule"))
        viewModel.decrementQuantity(item("item-semoule"))
        advanceUntilIdle()

        assertEquals(1f, quantityOf("item-riz"))
        assertEquals(1f, quantityOf("item-semoule"))
        assertEquals(emptyList<Int?>(), sentQuantities("item-riz"))
        assertEquals(listOf<Int?>(1), sentQuantities("item-semoule"))
        assertEquals(4, viewModel.uiState.value.groceryList!!.items.size)
        coVerify(exactly = 0) { groceryListRepository.deleteItem(any()) }
    }

    @Test
    fun `a product without packaging has no quantity to change from the line`() = runTest {
        stubQuantities()
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.incrementQuantity(item("item-farine"))
        viewModel.incrementQuantity(item("item-sel"))
        viewModel.decrementQuantity(item("item-farine"))
        viewModel.setQuantity(item("item-farine"), "3")
        advanceUntilIdle()

        assertEquals(500f, quantityOf("item-farine"))
        assertNull(quantityOf("item-sel"))
        coVerify(exactly = 0) { groceryListRepository.editItem(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a typed count is saved`() = runTest {
        stubQuantities()
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setQuantity(item("item-riz"), "4")
        runCurrent()
        assertEquals(4f, quantityOf("item-riz"))
        advanceUntilIdle()

        assertEquals(listOf<Int?>(4), sentQuantities("item-riz"))
    }

    @Test
    fun `an empty, zero, negative, decimal or non numeric count is refused and nothing is sent`() = runTest {
        stubQuantities()
        viewModel = createViewModel()
        advanceUntilIdle()

        for (typed in listOf("", "0", "-3", "2,5", "abc")) {
            viewModel.dismissQuantityMessage()
            viewModel.setQuantity(item("item-riz"), typed)
            assertNotNull("'$typed' should be refused", viewModel.uiState.value.quantityMessage)
        }
        advanceUntilIdle()

        assertEquals(1f, quantityOf("item-riz"))
        coVerify(exactly = 0) { groceryListRepository.editItem(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an API refusal puts the previous count back with a message`() = runTest {
        stubQuantities(editResult = Result.failure(RuntimeException("400")))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.incrementQuantity(item("item-riz"))
        runCurrent()
        assertEquals(2f, quantityOf("item-riz"))

        advanceUntilIdle()

        assertEquals(1f, quantityOf("item-riz"))
        assertTrue(viewModel.uiState.value.quantityMessage!!.contains("Riz"))

        viewModel.dismissQuantityMessage()
        assertNull(viewModel.uiState.value.quantityMessage)
    }

    @Test
    fun `a Mercure update during a burst of taps does not bring the old quantity back`() = runTest {
        stubQuantities()
        val updates = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns updates
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.incrementQuantity(item("item-riz"))
        viewModel.incrementQuantity(item("item-riz"))
        runCurrent()
        updates.emit(MercureEvent(data = """{"items":[{"id":"item-riz","label":"Riz","quantity":2,"unit":"pack"}]}"""))
        runCurrent()

        assertEquals(3f, quantityOf("item-riz"))
    }

    @Test
    fun `a quantity changed on another screen arrives through Mercure`() = runTest {
        stubQuantities()
        val updates = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns updates
        viewModel = createViewModel()
        advanceUntilIdle()

        updates.emit(MercureEvent(data = """{"items":[{"id":"item-riz","label":"Riz","quantity":4,"unit":"pack"}]}"""))
        advanceUntilIdle()

        assertEquals(4f, quantityOf("item-riz"))
    }
}
