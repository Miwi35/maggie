package com.maggie.app.ui.screens.grocery

import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.CiqualFood
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductAisle
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.ProductStockState
import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.StoreRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProductViewModelEditorTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var productRepository: ProductRepository
    private lateinit var storeRepository: StoreRepository

    private val pasta = Product(id = "p1", name = "Pâtes sèches", category = ProductCategory.GRAIN)
    private val superU = Store(id = "s1", name = "Super U")
    private val biocoop = Store(id = "s2", name = "Biocoop")
    private val epicerie = Aisle("a1", "/api/stores/s1", "Épicerie", 1)
    private val vrac = Aisle("a2", "/api/stores/s2", "Vrac", 1)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        productRepository = mockk()
        storeRepository = mockk()
        coEvery { storeRepository.getStores() } returns Result.success(listOf(superU, biocoop))
        coEvery { storeRepository.getAisles("s1") } returns Result.success(listOf(epicerie))
        coEvery { storeRepository.getAisles("s2") } returns Result.success(listOf(vrac))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(vararg products: Product): ProductViewModel {
        coEvery { productRepository.getProducts() } returns Result.success(products.toList())
        val viewModel = ProductViewModel(productRepository, storeRepository)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `loading brings the aisles of every store`() = runTest {
        val state = viewModel(pasta).uiState.value

        assertEquals(listOf(epicerie), state.aislesByStore["s1"])
        assertEquals(listOf(vrac), state.aislesByStore["s2"])
    }

    @Test
    fun `opening a product shows its draft, closing drops it`() = runTest {
        val viewModel = viewModel(pasta)

        viewModel.openProduct("p1")
        assertEquals("Pâtes sèches", viewModel.uiState.value.editor!!.draft.name)

        viewModel.closeProduct()
        assertNull(viewModel.uiState.value.editor)
    }

    @Test
    fun `opening an unknown product opens nothing`() = runTest {
        val viewModel = viewModel(pasta)

        viewModel.openProduct("nope")

        assertNull(viewModel.uiState.value.editor)
    }

    @Test
    fun `open a catalogue product then save it with a store and an aisle`() = runTest {
        val viewModel = viewModel(pasta)
        val sent = slot<JsonObject>()
        val saved = pasta.copy(preferredStore = "/api/stores/s1", aisles = listOf(ProductAisle("s1", "a1")))
        coEvery { productRepository.updateProduct("p1", capture(sent)) } returns Result.success(saved)

        viewModel.openProduct("p1")
        viewModel.selectStore(StoreSlot.PREFERRED, "s1")
        viewModel.selectAisle(StoreSlot.PREFERRED, AisleChoice("a1"))
        viewModel.saveProduct()
        assertTrue(viewModel.uiState.value.editor!!.isSaving)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.editor)
        assertEquals(saved, state.products.single())
        assertEquals(JsonPrimitive("/api/stores/s1"), sent.captured["preferredStore"])
        assertEquals(setOf("preferredStore", "aisles"), sent.captured.keys)
    }

    @Test
    fun `choosing another store empties the aisle`() = runTest {
        val product = pasta.copy(preferredStore = "/api/stores/s1", aisles = listOf(ProductAisle("s1", "a1")))
        val viewModel = viewModel(product)
        viewModel.openProduct("p1")
        assertEquals(AisleChoice("a1"), viewModel.uiState.value.editor!!.draft.preferredAisle)

        viewModel.selectStore(StoreSlot.PREFERRED, "s2")

        val draft = viewModel.uiState.value.editor!!.draft
        assertEquals("s2", draft.preferredStoreId)
        assertNull(draft.preferredAisle)
    }

    @Test
    fun `the fallback cannot be the usual store`() = runTest {
        val viewModel = viewModel(pasta.copy(preferredStore = "/api/stores/s1"))
        viewModel.openProduct("p1")

        viewModel.selectStore(StoreSlot.FALLBACK, "s1")

        assertNull(viewModel.uiState.value.editor!!.draft.fallbackStoreId)
    }

    @Test
    fun `a store the list did not bring has its aisles loaded when chosen`() = runTest {
        coEvery { storeRepository.getAisles("s3") } returns Result.success(listOf(Aisle("a3", "/api/stores/s3", "Frais", 1)))
        val viewModel = viewModel(pasta)
        viewModel.openProduct("p1")

        viewModel.selectStore(StoreSlot.PREFERRED, "s3")
        advanceUntilIdle()

        assertEquals("Frais", viewModel.uiState.value.aislesByStore["s3"]!!.single().name)
    }

    @Test
    fun `a new aisle is sent by name in the chosen store`() = runTest {
        val viewModel = viewModel(pasta)
        val sent = slot<JsonObject>()
        coEvery { productRepository.updateProduct("p1", capture(sent)) } returns Result.success(pasta)

        viewModel.openProduct("p1")
        viewModel.selectStore(StoreSlot.PREFERRED, "s2")
        viewModel.selectAisle(StoreSlot.PREFERRED, AisleChoice("Bio", isNew = true))
        viewModel.saveProduct()
        advanceUntilIdle()

        assertEquals("""[{"store":"s2","aisle":"Bio"}]""", sent.captured["aisles"].toString())
        // once on load, once more after the save so the new aisle is read back
        coVerify(atLeast = 2) { storeRepository.getAisles("s2") }
    }

    @Test
    fun `each field edited is sent and nothing else`() = runTest {
        val viewModel = viewModel(pasta)
        val sent = slot<JsonObject>()
        coEvery { productRepository.updateProduct("p1", capture(sent)) } returns Result.success(pasta)

        viewModel.openProduct("p1")
        viewModel.editDraft { it.copy(stockState = ProductStockState.LOW, restockQuantity = "2", autoRestock = true, seasonMonths = listOf(7, 6)) }
        viewModel.pickCiqual(CiqualFood("9811", "Pâtes alimentaires"))
        viewModel.saveProduct()
        advanceUntilIdle()

        assertEquals(setOf("stockState", "restockQuantity", "autoRestock", "seasonMonths", "ciqualAlimCode"), sent.captured.keys)
    }

    @Test
    fun `picking a Ciqual food shows its name, picking none clears the link`() = runTest {
        val viewModel = viewModel(pasta.copy(ciqualAlimCode = "1"))
        viewModel.openProduct("p1")

        viewModel.pickCiqual(CiqualFood("9811", "Pâtes alimentaires"))
        assertEquals("9811", viewModel.uiState.value.editor!!.draft.ciqualAlimCode)
        assertEquals("Pâtes alimentaires", viewModel.uiState.value.editor!!.ciqualFoodName)

        viewModel.pickCiqual(null)
        assertNull(viewModel.uiState.value.editor!!.draft.ciqualAlimCode)
    }

    @Test
    fun `a refused save keeps the sheet and says why`() = runTest {
        val viewModel = viewModel(pasta)
        coEvery { productRepository.updateProduct("p1", any()) } returns Result.failure(RuntimeException("Nom déjà pris"))

        viewModel.openProduct("p1")
        viewModel.editDraft { it.copy(name = "Riz") }
        viewModel.saveProduct()
        advanceUntilIdle()

        val editor = viewModel.uiState.value.editor
        assertNotNull(editor)
        assertFalse(editor!!.isSaving)
        assertEquals("Nom déjà pris", editor.error)
        assertEquals("Riz", editor.draft.name)
        assertEquals("Pâtes sèches", viewModel.uiState.value.products.single().name)
    }

    @Test
    fun `editing clears the error`() = runTest {
        val viewModel = viewModel(pasta)
        coEvery { productRepository.updateProduct("p1", any()) } returns Result.failure(RuntimeException("Non"))
        viewModel.openProduct("p1")
        viewModel.editDraft { it.copy(name = "Riz") }
        viewModel.saveProduct()
        advanceUntilIdle()

        viewModel.editDraft { it.copy(name = "Riz basmati") }

        assertNull(viewModel.uiState.value.editor!!.error)
    }

    @Test
    fun `a blank name is not sent`() = runTest {
        val viewModel = viewModel(pasta)
        viewModel.openProduct("p1")
        viewModel.editDraft { it.copy(name = "  ") }

        viewModel.saveProduct()
        advanceUntilIdle()

        assertEquals("Le nom du produit est obligatoire.", viewModel.uiState.value.editor!!.error)
        coVerify(exactly = 0) { productRepository.updateProduct(any(), any()) }
    }

    @Test
    fun `saving without a change just closes the sheet`() = runTest {
        val viewModel = viewModel(pasta)
        viewModel.openProduct("p1")

        viewModel.saveProduct()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.editor)
        coVerify(exactly = 0) { productRepository.updateProduct(any(), any()) }
    }

    @Test
    fun `deleting the product of the open sheet removes it and closes the sheet`() = runTest {
        val viewModel = viewModel(pasta)
        coEvery { productRepository.deleteProduct("p1") } returns Result.success(Unit)
        viewModel.openProduct("p1")

        viewModel.deleteProduct("p1")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.products.isEmpty())
        assertNull(viewModel.uiState.value.editor)
    }

    @Test
    fun `a refused deletion keeps the sheet open`() = runTest {
        val viewModel = viewModel(pasta)
        coEvery { productRepository.deleteProduct("p1") } returns Result.failure(RuntimeException("Refusé"))
        viewModel.openProduct("p1")

        viewModel.deleteProduct("p1")
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.editor)
        assertEquals(listOf(pasta), viewModel.uiState.value.products)
        assertEquals("Refusé", viewModel.uiState.value.error)
    }
}
