package com.maggie.app.ui.screens.grocery

import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.ProductStockState
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.StoreRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

@OptIn(ExperimentalCoroutinesApi::class)
class ProductViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var productRepository: ProductRepository
    private lateinit var storeRepository: StoreRepository

    private val rice = Product(id = "1", name = "Riz", category = ProductCategory.GRAIN)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        productRepository = mockk()
        storeRepository = mockk()
        coEvery { storeRepository.getStores() } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.load(vararg products: Product): ProductUiState {
        coEvery { productRepository.getProducts() } returns Result.success(products.toList())
        val viewModel = ProductViewModel(productRepository, storeRepository)
        advanceUntilIdle()
        return viewModel.uiState.value
    }

    @Test
    fun `a product with no state said loads as in stock`() = runTest {
        val state = load(rice)

        assertEquals(ProductStockState.IN_STOCK, state.products.single().stockState)
        assertNull(state.products.single().restockQuantity)
        assertFalse(state.products.single().autoRestock)
        assertFalse(state.isLoading)
    }

    @Test
    fun `a product running low loads as low with its restock settings`() = runTest {
        val state = load(rice.copy(stockState = ProductStockState.LOW, restockQuantity = 2, autoRestock = true))

        val product = state.products.single()
        assertEquals(ProductStockState.LOW, product.stockState)
        assertEquals(2, product.restockQuantity)
        assertTrue(product.autoRestock)
    }

    @Test
    fun `a product out of stock loads as out`() = runTest {
        val state = load(rice.copy(stockState = ProductStockState.OUT))

        assertEquals(ProductStockState.OUT, state.products.single().stockState)
    }

    @Test
    fun `a refresh shows the state the product moved to`() = runTest {
        coEvery { productRepository.getProducts() } returns Result.success(listOf(rice))
        val viewModel = ProductViewModel(productRepository, storeRepository)
        advanceUntilIdle()

        coEvery { productRepository.getProducts() } returns
            Result.success(listOf(rice.copy(stockState = ProductStockState.OUT)))
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(ProductStockState.OUT, viewModel.uiState.value.products.single().stockState)
    }

    // MAG-494 — the season months typed on the product sheet.

    private val potiron = Product(id = "2", name = "Potiron", category = ProductCategory.PRODUCE, seasonMonths = listOf(1, 9, 10, 11, 12), inSeason = true)

    @Test
    fun `a product loads with its season months and whether it is in season`() = runTest {
        val state = load(potiron, rice)

        assertEquals(listOf(1, 9, 10, 11, 12), state.products.first().seasonMonths)
        assertEquals(true, state.products.first().inSeason)
        assertNull(state.products.last().seasonMonths)
        assertNull(state.products.last().inSeason)
    }

    @Test
    fun `saving the season months shows the product the API answers`() = runTest {
        coEvery { productRepository.getProducts() } returns Result.success(listOf(potiron, rice))
        coEvery { productRepository.updateSeasonMonths("2", listOf(7, 8)) } returns
            Result.success(potiron.copy(seasonMonths = listOf(7, 8), inSeason = false))
        val viewModel = ProductViewModel(productRepository, storeRepository)
        advanceUntilIdle()

        viewModel.updateSeasonMonths("2", listOf(7, 8))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(7, 8), state.products.first().seasonMonths)
        assertEquals(false, state.products.first().inSeason)
        assertEquals(rice, state.products.last())
        assertNull(state.error)
        coVerify(exactly = 1) { productRepository.updateSeasonMonths("2", listOf(7, 8)) }
    }

    @Test
    fun `a refused save keeps the months and says why`() = runTest {
        coEvery { productRepository.getProducts() } returns Result.success(listOf(potiron))
        coEvery { productRepository.updateSeasonMonths(any(), any()) } returns
            Result.failure(IllegalStateException("seasonMonths takes months from 1 (January) to 12 (December)"))
        val viewModel = ProductViewModel(productRepository, storeRepository)
        advanceUntilIdle()

        viewModel.updateSeasonMonths("2", listOf(13))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(1, 9, 10, 11, 12), state.products.single().seasonMonths)
        assertTrue(state.error!!.contains("seasonMonths"))
    }

    @Test
    fun `no month ticked saves no season`() = runTest {
        coEvery { productRepository.getProducts() } returns Result.success(listOf(potiron))
        coEvery { productRepository.updateSeasonMonths("2", emptyList()) } returns
            Result.success(potiron.copy(seasonMonths = null, inSeason = null))
        val viewModel = ProductViewModel(productRepository, storeRepository)
        advanceUntilIdle()

        viewModel.updateSeasonMonths("2", emptyList())
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.products.single().seasonMonths)
        assertNull(viewModel.uiState.value.products.single().inSeason)
    }
}
