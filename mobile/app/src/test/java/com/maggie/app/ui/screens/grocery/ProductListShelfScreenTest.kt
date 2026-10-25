package com.maggie.app.ui.screens.grocery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.AisleFilter
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductAisle
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.ProductQuery
import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.StoreRepository
import com.maggie.app.screentest.FakeRecurring
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.screentest.tap
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The filter bar and the aisles of Courses › Produits (MAG-606), against a fake that filters as the API does. */
@RunWith(AndroidJUnit4::class)
class ProductListShelfScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val superU = Store(id = "s1", name = "Super U")
    private val biocoop = Store(id = "s2", name = "Biocoop")
    private val frais = Aisle("a-frais", "/api/stores/s1", "Frais", 1)
    private val epicerie = Aisle("a-epicerie", "/api/stores/s1", "Épicerie", 2)

    private val yaourt = Product(id = "p1", name = "Yaourt", category = ProductCategory.DAIRY, aisles = listOf(ProductAisle("s1", "a-frais")))
    private val biscottes = Product(id = "p2", name = "Biscottes", category = ProductCategory.GRAIN, aisles = listOf(ProductAisle("s1", "a-epicerie")))
    // In Super U, on no aisle yet.
    private val sel = Product(id = "p3", name = "Sel", category = ProductCategory.OTHER, aisles = listOf(ProductAisle("s1", null)))
    private val thon = Product(id = "p4", name = "Thon", category = ProductCategory.OTHER)

    private val queries = mutableListOf<ProductQuery>()

    private fun show(vararg products: Product) {
        val productRepository = mockk<ProductRepository>()
        val storeRepository = mockk<StoreRepository>()
        coEvery { storeRepository.getStores() } returns Result.success(listOf(superU, biocoop))
        coEvery { storeRepository.getAisles("s1") } returns Result.success(listOf(epicerie, frais))
        coEvery { storeRepository.getAisles("s2") } returns Result.success(emptyList())
        coEvery { productRepository.getProducts(any()) } answers {
            val query = firstArg<ProductQuery>()
            queries += query
            Result.success(products.filter { product -> query.matches(product) })
        }
        val viewModel = ProductViewModel(productRepository, storeRepository)
        compose.setContent {
            ProductListScreen(viewModel = viewModel, recurringViewModel = FakeRecurring().viewModel, onBack = {})
        }
        compose.waitForIdle()
    }

    /** What the API's `store` and `aisle` filters keep. */
    private fun ProductQuery.matches(product: Product): Boolean {
        val store = storeId ?: return true
        val aisle = product.aisles.firstOrNull { it.store == store }?.aisle
        val inStore = product.aisles.any { it.store == store } || product.preferredStore == "/api/stores/$store"
        return when (val filter = this.aisle) {
            AisleFilter.All -> inStore
            AisleFilter.None -> inStore && aisle == null
            is AisleFilter.One -> aisle == filter.id
        }
    }

    // Inside a ModalBottomSheet the touch is swallowed, see ScreenInteractions.tap.
    private fun pick(chip: String, option: String) {
        compose.onNodeWithTag(chip).performClick()
        compose.onNode(hasTestTag(UiTags.PRODUCT_FILTER_OPTION) and hasText(option)).tap()
        compose.waitForIdle()
    }

    @Test
    fun `the list opens flat with the three filters, and Rayon waits for a store`() {
        show(yaourt, biscottes, sel, thon)

        compose.onNodeWithTag(UiTags.PRODUCT_FILTER_STORE).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag(UiTags.PRODUCT_FILTER_SORT).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PRODUCT_FILTER_AISLE).assertIsNotEnabled()
        compose.onNodeWithText("Trier : Nom").assertIsDisplayed()
        compose.onNodeWithText("Thon").assertIsDisplayed()
        compose.onNodeWithText("Sans rayon").assertDoesNotExist()
    }

    @Test
    fun `a store chosen groups the products by aisle in the way through it, Sans rayon last`() {
        show(yaourt, biscottes, sel, thon)

        pick(UiTags.PRODUCT_FILTER_STORE, "Super U")

        compose.onNodeWithText("Magasin : Super U").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PRODUCT_FILTER_AISLE).assertIsEnabled()
        compose.onNodeWithText("Thon").assertDoesNotExist()
        assertTopToBottom(
            "Frais" to compose.onNodeWithText("Frais"),
            "Yaourt" to compose.onNodeWithText("Yaourt"),
            "Épicerie" to compose.onNodeWithText("Épicerie"),
            "Biscottes" to compose.onNodeWithText("Biscottes"),
            "Sans rayon" to compose.onNodeWithText("Sans rayon"),
            "Sel" to compose.onNodeWithText("Sel"),
        )
        assertEquals(ProductQuery(storeId = "s1"), queries.last())
    }

    @Test
    fun `an aisle chosen keeps only its products under its header`() {
        show(yaourt, biscottes, sel)
        pick(UiTags.PRODUCT_FILTER_STORE, "Super U")

        pick(UiTags.PRODUCT_FILTER_AISLE, "Frais")

        compose.onNodeWithText("Rayon : Frais").assertIsDisplayed()
        compose.onNodeWithText("Yaourt").assertIsDisplayed()
        compose.onNodeWithText("Biscottes").assertDoesNotExist()
        compose.onNodeWithText("Sel").assertDoesNotExist()
        assertEquals(ProductQuery("s1", AisleFilter.One("a-frais")), queries.last())
    }

    @Test
    fun `Sans rayon is offered in the aisle sheet and keeps the products with no aisle`() {
        show(yaourt, biscottes, sel)
        pick(UiTags.PRODUCT_FILTER_STORE, "Super U")

        pick(UiTags.PRODUCT_FILTER_AISLE, "Sans rayon")

        compose.onNodeWithText("Rayon : Sans rayon").assertIsDisplayed()
        compose.onNodeWithText("Sel").assertIsDisplayed()
        compose.onNodeWithText("Yaourt").assertDoesNotExist()
        assertEquals(ProductQuery("s1", AisleFilter.None), queries.last())
    }

    @Test
    fun `changing the store puts the aisle back to all`() {
        show(yaourt, biscottes, sel)
        pick(UiTags.PRODUCT_FILTER_STORE, "Super U")
        pick(UiTags.PRODUCT_FILTER_AISLE, "Frais")

        pick(UiTags.PRODUCT_FILTER_STORE, "Biocoop")

        compose.onNodeWithText("Magasin : Biocoop").assertIsDisplayed()
        compose.onNodeWithText("Rayon : Frais").assertDoesNotExist()
        assertEquals(ProductQuery(storeId = "s2"), queries.last())
    }

    @Test
    fun `a store with no product says so and keeps the filters`() {
        show(yaourt)

        pick(UiTags.PRODUCT_FILTER_STORE, "Biocoop")

        compose.onNodeWithText("Aucun produit ne correspond.").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PRODUCT_FILTER_STORE).assertIsDisplayed()
    }

    @Test
    fun `the sort is chosen in its own sheet and passed on`() {
        show(yaourt, biscottes)

        pick(UiTags.PRODUCT_FILTER_SORT, "Stock")

        compose.onNodeWithText("Trier : Stock").assertIsDisplayed()
        assertEquals(com.maggie.app.data.model.ProductSort.STOCK, queries.last().sort)
    }
}
