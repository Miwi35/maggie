package com.maggie.app.ui.screens.grocery

import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.ProductStockState
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.StoreRepository
import io.mockk.coEvery
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
}
