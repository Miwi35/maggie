package com.maggie.app.ui.screens.grocery

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.RecurringFrequency
import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.StoreRepository
import com.maggie.app.screentest.FakeRecurring
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Courses › Produits as the Produits-Tel mockup draws it (MAG-671): French labels, one line a product, the detail on touch. */
@RunWith(AndroidJUnit4::class)
class ProductListMockupScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val ail = Product(id = "p1", name = "Ail", category = ProductCategory.PRODUCE, defaultUnit = CookbookUnit.PIECE)
    private val beurre = Product(id = "p2", name = "Beurre à 80 % MG", category = ProductCategory.OTHER, defaultUnit = CookbookUnit.G)

    private val recurring = FakeRecurring(products = listOf(ail, beurre))

    private fun show(vararg products: Product, deleted: MutableList<String> = mutableListOf()) {
        val productRepository = mockk<ProductRepository>()
        val storeRepository = mockk<StoreRepository>()
        coEvery { storeRepository.getStores() } returns Result.success(listOf(Store(id = "s1", name = "Super U")))
        coEvery { storeRepository.getAisles(any()) } returns Result.success(emptyList())
        coEvery { productRepository.getProducts(any()) } returns Result.success(products.toList())
        coEvery { productRepository.deleteProduct(any()) } answers {
            deleted += firstArg<String>()
            Result.success(Unit)
        }
        val viewModel = ProductViewModel(productRepository, storeRepository)
        compose.setContent {
            ProductListScreen(viewModel = viewModel, recurringViewModel = recurring.viewModel)
        }
        compose.waitForIdle()
    }

    private val rawWords = Regex(
        "\\b(produce|dairy|meat|fish|grain|spice|condiment|frozen|beverage|household|hygiene|cleaning|other|" +
            "piece|bunch|can|bottle|pack|jar)\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun assertNoRawValue() {
        val raw = SemanticsMatcher("shows an API value as is") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { rawWords.containsMatchIn(it.text) } == true
        }
        compose.onAllNodes(raw).assertCountEquals(0)
    }

    @Test
    fun `a product of every category and unit reads in French, never as the API spells it`() {
        val units = CookbookUnit.entries
        val products = ProductCategory.entries.mapIndexed { index, category ->
            Product(id = "p-$index", name = "Produit $index", category = category, defaultUnit = units[index % units.size])
        }
        show(*products.toTypedArray())

        assertNoRawValue()
        compose.onNodeWithText("Fruits et légumes").assertIsDisplayed()
        compose.onNodeWithText("Fruits et légumes · g").assertIsDisplayed()
    }

    @Test
    fun `Ail reads under Fruits et légumes with its unit in French`() {
        show(ail, beurre)

        compose.onNodeWithText("Ail").assertIsDisplayed()
        compose.onNodeWithText("Fruits et légumes · pièce").assertIsDisplayed()
        compose.onNodeWithText("Autre · g").assertIsDisplayed()
        assertNoRawValue()
    }

    @Test
    fun `the products are grouped under the French name of their category`() {
        show(beurre, ail)

        assertTopToBottom(
            "Fruits et légumes" to compose.onNodeWithText("Fruits et légumes"),
            "Ail" to compose.onNodeWithText("Ail"),
            "Autre" to compose.onNodeWithText("Autre"),
            "Beurre" to compose.onNodeWithText("Beurre à 80 % MG"),
        )
    }

    @Test
    fun `a line holds the product alone, with no recurrence, season or deletion`() {
        show(ail.copy(seasonMonths = listOf(10, 11), inSeason = true), beurre)

        compose.onNodeWithText("Récurrent").assertDoesNotExist()
        compose.onNodeWithText("Toutes les semaines").assertDoesNotExist()
        compose.onNodeWithText("Mois de saison", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.SEASON_MONTHS).assertDoesNotExist()
        compose.onAllNodes(hasContentDescription("Supprimer")).assertCountEquals(0)
    }

    @Test
    fun `touching a product opens its detail with the recurrence, the season and the deletion`() {
        show(ail.copy(seasonMonths = listOf(10, 11), inSeason = true), beurre)

        compose.onNodeWithText("Ail").performClick()

        compose.onNodeWithTag(UiTags.PRODUCT_SHEET).assertIsDisplayed()
        compose.onNodeWithText("Récurrent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SEASON_MONTHS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PRODUCT_SHEET_DELETE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the recurrence is changed from the detail`() {
        show(ail, beurre)
        compose.onNodeWithText("Ail").performClick()

        compose.onNodeWithTag(UiTags.recurringOption("weekly")).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf(RecurringFrequency.WEEKLY), recurring.items.map { it.frequency })
    }

    @Test
    fun `deleting from the detail asks first, then removes the product and closes the sheet`() {
        val deleted = mutableListOf<String>()
        show(ail, beurre, deleted = deleted)
        compose.onNodeWithText("Ail").performClick()

        compose.onNodeWithTag(UiTags.PRODUCT_SHEET_DELETE).performScrollTo().performClick()
        assertEquals(emptyList<String>(), deleted)
        compose.onNodeWithTag(UiTags.PRODUCT_SHEET_DELETE_CONFIRM).performClick()
        compose.waitForIdle()

        assertEquals(listOf("p1"), deleted)
        compose.onNodeWithTag(UiTags.PRODUCT_SHEET).assertDoesNotExist()
        compose.onNodeWithText("Ail").assertDoesNotExist()
    }
}
