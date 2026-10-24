package com.maggie.app.ui.screens.grocery

import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.AisleFilter
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductAisle
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.ProductQuery
import com.maggie.app.data.model.ProductSort
import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.StoreRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The filters, the sort and the grouping by aisle of Courses › Produits (MAG-606). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductViewModelShelfTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var productRepository: ProductRepository
    private lateinit var storeRepository: StoreRepository

    private val superU = Store(id = "s1", name = "Super U")
    private val biocoop = Store(id = "s2", name = "Biocoop")

    // Listed out of order on purpose: the way through the store is the `position`, not the list order.
    private val epicerie = Aisle("a-epicerie", "/api/stores/s1", "Épicerie", 2)
    private val frais = Aisle("a-frais", "/api/stores/s1", "Frais", 1)
    private val vrac = Aisle("a-vrac", "/api/stores/s2", "Vrac", 1)

    private val yaourt = Product(
        id = "p1",
        name = "Yaourt",
        category = ProductCategory.DAIRY,
        aisles = listOf(ProductAisle("s1", "a-frais")),
    )
    private val biscottes = Product(
        id = "p2",
        name = "Biscottes",
        category = ProductCategory.GRAIN,
        aisles = listOf(ProductAisle("s1", "a-epicerie")),
    )
    private val sel = Product(id = "p3", name = "Sel", category = ProductCategory.OTHER)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        productRepository = mockk()
        storeRepository = mockk()
        coEvery { storeRepository.getStores() } returns Result.success(listOf(superU, biocoop))
        coEvery { storeRepository.getAisles("s1") } returns Result.success(listOf(epicerie, frais))
        coEvery { storeRepository.getAisles("s2") } returns Result.success(listOf(vrac))
        coEvery { productRepository.getProducts(any()) } returns Result.success(listOf(sel, yaourt, biscottes))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.open(): ProductViewModel {
        val viewModel = ProductViewModel(productRepository, storeRepository)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `the list opens unfiltered, sorted by name, with every store and its aisles loaded`() = runTest {
        val viewModel = open()

        val state = viewModel.uiState.value
        assertEquals(ProductQuery(), state.query)
        assertEquals(listOf(superU, biocoop), state.stores)
        assertNull(state.sections)
        assertFalse(state.isLoading)
        coVerify(exactly = 1) { productRepository.getProducts(ProductQuery()) }
    }

    @Test
    fun `choosing a store asks the API for it and groups the products by aisle`() = runTest {
        val viewModel = open()

        viewModel.selectFilterStore("s1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ProductQuery(storeId = "s1"), state.query)
        assertEquals(listOf("Frais", "Épicerie", "Sans rayon"), state.sections!!.map { it.title })
        coVerify { productRepository.getProducts(ProductQuery(storeId = "s1")) }
        coVerify(exactly = 1) { storeRepository.getStores() }
    }

    @Test
    fun `choosing another store puts the aisle back to all`() = runTest {
        val viewModel = open()
        viewModel.selectFilterStore("s1")
        viewModel.selectFilterAisle(AisleFilter.One("a-frais"))
        advanceUntilIdle()

        viewModel.selectFilterStore("s2")
        advanceUntilIdle()

        assertEquals(ProductQuery(storeId = "s2", aisle = AisleFilter.All), viewModel.uiState.value.query)
        coVerify { productRepository.getProducts(ProductQuery(storeId = "s2")) }
    }

    @Test
    fun `choosing an aisle asks the API for that aisle of the store`() = runTest {
        val viewModel = open()
        viewModel.selectFilterStore("s1")
        advanceUntilIdle()
        coEvery { productRepository.getProducts(any()) } returns Result.success(listOf(yaourt))

        viewModel.selectFilterAisle(AisleFilter.One("a-frais"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ProductQuery("s1", AisleFilter.One("a-frais")), state.query)
        assertEquals(listOf(yaourt), state.products)
        assertEquals(listOf("Frais"), state.sections!!.map { it.title })
        coVerify { productRepository.getProducts(ProductQuery("s1", AisleFilter.One("a-frais"))) }
    }

    @Test
    fun `Sans rayon asks the API for the products with no aisle in the store`() = runTest {
        val viewModel = open()
        viewModel.selectFilterStore("s1")
        advanceUntilIdle()
        coEvery { productRepository.getProducts(any()) } returns Result.success(listOf(sel))

        viewModel.selectFilterAisle(AisleFilter.None)
        advanceUntilIdle()

        assertEquals(AisleFilter.None, viewModel.uiState.value.query.aisle)
        assertEquals(listOf("Sans rayon"), viewModel.uiState.value.sections!!.map { it.title })
        coVerify { productRepository.getProducts(ProductQuery("s1", AisleFilter.None)) }
    }

    @Test
    fun `an aisle cannot be chosen without a store`() = runTest {
        val viewModel = open()

        viewModel.selectFilterAisle(AisleFilter.One("a-frais"))
        advanceUntilIdle()

        assertEquals(ProductQuery(), viewModel.uiState.value.query)
        coVerify(exactly = 1) { productRepository.getProducts(any()) }
    }

    @Test
    fun `the sort is passed to the API`() = runTest {
        val viewModel = open()

        viewModel.selectSort(ProductSort.STOCK)
        advanceUntilIdle()

        assertEquals(ProductSort.STOCK, viewModel.uiState.value.query.sort)
        coVerify { productRepository.getProducts(ProductQuery(sort = ProductSort.STOCK)) }
    }

    @Test
    fun `the sort by aisle needs a store`() = runTest {
        val viewModel = open()
        viewModel.selectSort(ProductSort.AISLE)
        advanceUntilIdle()
        assertEquals(ProductSort.NAME, viewModel.uiState.value.query.sort)

        viewModel.selectFilterStore("s1")
        viewModel.selectSort(ProductSort.AISLE)
        advanceUntilIdle()
        assertEquals(ProductSort.AISLE, viewModel.uiState.value.query.sort)
    }

    @Test
    fun `clearing the store falls back from the aisle sort to the name`() = runTest {
        val viewModel = open()
        viewModel.selectFilterStore("s1")
        viewModel.selectSort(ProductSort.AISLE)
        advanceUntilIdle()

        viewModel.selectFilterStore(null)
        advanceUntilIdle()

        assertEquals(ProductQuery(), viewModel.uiState.value.query)
        assertNull(viewModel.uiState.value.sections)
    }

    @Test
    fun `clearing the store keeps a stock sort`() = runTest {
        val viewModel = open()
        viewModel.selectFilterStore("s1")
        viewModel.selectSort(ProductSort.STOCK)
        advanceUntilIdle()

        viewModel.selectFilterStore(null)
        advanceUntilIdle()

        assertEquals(ProductQuery(sort = ProductSort.STOCK), viewModel.uiState.value.query)
    }

    @Test
    fun `choosing what is already chosen asks nothing`() = runTest {
        val viewModel = open()
        viewModel.selectFilterStore("s1")
        advanceUntilIdle()

        viewModel.selectFilterStore("s1")
        viewModel.selectSort(ProductSort.NAME)
        advanceUntilIdle()

        coVerify(exactly = 1) { productRepository.getProducts(ProductQuery(storeId = "s1")) }
    }

    @Test
    fun `the list is loading, and empty, while the API answers a new filter`() = runTest {
        val viewModel = open()
        val answer = CompletableDeferred<Result<List<Product>>>()
        coEvery { productRepository.getProducts(ProductQuery(storeId = "s1")) } coAnswers { answer.await() }

        viewModel.selectFilterStore("s1")
        advanceUntilIdle()

        // The old list belongs to the old filter: it is not shown grouped under the new store.
        assertTrue(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.products.isEmpty())

        answer.complete(Result.success(listOf(yaourt)))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(listOf(yaourt), viewModel.uiState.value.products)
    }

    @Test
    fun `a refused filter shows the error and keeps the filter`() = runTest {
        val viewModel = open()
        coEvery { productRepository.getProducts(ProductQuery(storeId = "s1")) } returns
            Result.failure(IllegalStateException("Le serveur ne répond pas."))

        viewModel.selectFilterStore("s1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Le serveur ne répond pas.", state.error)
        assertEquals("s1", state.query.storeId)
        assertFalse(state.isLoading)
    }

    @Test
    fun `retrying after an error clears it and reloads with the filter`() = runTest {
        val viewModel = open()
        coEvery { productRepository.getProducts(ProductQuery(storeId = "s1")) } returns
            Result.failure(IllegalStateException("Le serveur ne répond pas."))
        viewModel.selectFilterStore("s1")
        advanceUntilIdle()
        coEvery { productRepository.getProducts(ProductQuery(storeId = "s1")) } returns Result.success(listOf(yaourt))

        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertEquals(listOf(yaourt), viewModel.uiState.value.products)
    }

    @Test
    fun `a filter that matches no product leaves an empty list under the filter`() = runTest {
        val viewModel = open()
        coEvery { productRepository.getProducts(any()) } returns Result.success(emptyList())

        viewModel.selectFilterStore("s2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.products.isEmpty())
        assertTrue(state.query.isFiltered)
        assertTrue(state.sections!!.isEmpty())
        assertNull(state.error)
    }

    @Test
    fun `the filter bar still has its stores when the products fail to load`() = runTest {
        coEvery { productRepository.getProducts(any()) } returns Result.failure(IllegalStateException("hors ligne"))

        val viewModel = open()

        assertEquals(listOf(superU, biocoop), viewModel.uiState.value.stores)
        assertEquals("hors ligne", viewModel.uiState.value.error)
    }

    @Test
    fun `the aisles of the chosen store come in the order of the way through it`() = runTest {
        val viewModel = open()

        viewModel.selectFilterStore("s1")
        advanceUntilIdle()

        assertEquals(listOf("Frais", "Épicerie"), viewModel.uiState.value.storeAisles.map { it.name })
    }
}
