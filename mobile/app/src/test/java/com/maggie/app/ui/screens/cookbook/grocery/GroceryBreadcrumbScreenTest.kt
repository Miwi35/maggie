package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.RecurringFrequency
import com.maggie.app.data.model.RecurringGroceryItem
import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.StoreRepository
import com.maggie.app.screentest.FakeGrocery
import com.maggie.app.screentest.FakeRecurring
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.GroceryNav
import com.maggie.app.ui.navigation.GrocerySection
import com.maggie.app.ui.navigation.LocalGroceryNav
import com.maggie.app.ui.screens.grocery.ProductListScreen
import com.maggie.app.ui.screens.grocery.ProductViewModel
import com.maggie.app.ui.screens.grocery.StoreListScreen
import com.maggie.app.ui.screens.grocery.StoreViewModel
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * « Courses › Liste ▾ » (MAG-627): the breadcrumb the three pages of the module wear, the menu of its sections,
 * the « ⋮ » that now holds « Courses passées », and « + Produit récurrent » in the list. What a tap on a section does
 * to the back stack is `GroceryNavigationTest`'s and the Maestro journeys'.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class GroceryBreadcrumbScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val sections = mutableListOf<GrocerySection>()
    private var drawerOpened = 0

    private val nav = GroceryNav(onSelectSection = { sections += it }, onOpenDrawer = { drawerOpened++ })

    private val milk = Product(id = "p-milk", name = "Lait", category = ProductCategory.DAIRY)
    private val milkRecurring = RecurringGroceryItem(
        id = "rec-milk",
        product = "/api/products/p-milk",
        label = "Lait",
        frequency = RecurringFrequency.WEEKLY,
        nextDueAt = "2026-10-20",
    )

    private fun showList(fake: FakeGrocery = FakeGrocery(), onPastErrands: () -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalGroceryNav provides nav) {
                GroceryScreen(
                    viewModel = fake.viewModel,
                    recurringViewModel = fake.recurring.viewModel,
                    onNavigateToPastErrands = onPastErrands,
                )
            }
        }
    }

    @Test
    fun `the list reads Courses, a chevron and Liste`() {
        showList()

        compose.onNodeWithTag(UiTags.BREADCRUMB_MODULE).assertTextEquals("Courses")
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION)
            .assertContentDescriptionEquals("Courses, section Liste : changer de section")
        compose.onNodeWithText("Liste de courses").assertDoesNotExist()
    }

    @Test
    fun `the menu lists Liste, Produits and Magasins with the list checked`() {
        showList()

        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()

        GrocerySection.entries.forEach { compose.onNodeWithTag(UiTags.breadcrumbMenu(it.key)).assertExists() }
        compose.onNodeWithTag(UiTags.breadcrumbMenu("list")).assertIsSelected()
        compose.onNodeWithText("Produits").assertIsDisplayed()
        compose.onNodeWithText("Magasins").assertIsDisplayed()
    }

    @Test
    fun `choosing Produits then Magasins asks the graph for each`() {
        showList()

        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()
        compose.onNodeWithTag(UiTags.breadcrumbMenu("products")).performClick()
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()
        compose.onNodeWithTag(UiTags.breadcrumbMenu("stores")).performClick()

        assertEquals(listOf(GrocerySection.PRODUCTS, GrocerySection.STORES), sections)
    }

    @Test
    fun `the burger opens the drawer`() {
        showList()

        compose.onNodeWithTag(UiTags.NAV_MENU).performClick()

        assertEquals(1, drawerOpened)
    }

    @Test
    fun `there is no Récurrents tab any more`() {
        showList()

        compose.onNodeWithText("Récurrents").assertDoesNotExist()
        compose.onNode(hasTestTag(UiTags.BREADCRUMB_SECTION).and(hasText("Liste"))).assertExists()
    }

    @Test
    fun `the overflow menu offers Courses passées and neither Produits nor Magasins`() {
        var opened = 0
        showList(onPastErrands = { opened++ })

        compose.onNodeWithTag(UiTags.GROCERY_MENU).performClick()
        compose.onNodeWithTag(UiTags.PAST_ERRANDS_OPEN).assertIsDisplayed()
        compose.onNodeWithText("Produits").assertDoesNotExist()
        compose.onNodeWithText("Magasins").assertDoesNotExist()
        compose.onNodeWithTag(UiTags.PAST_ERRANDS_OPEN).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `the list ends on Produit récurrent, which opens the recurring items`() {
        val fake = FakeGrocery(recurring = FakeRecurring(listOf(milkRecurring), listOf(milk)))
        showList(fake)

        compose.onNodeWithTag(UiTags.GROCERY_RECURRING_OPEN).performClick()

        compose.onNodeWithText("Prochain ajout : 20/10/2026").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.RECURRING_ADD).assertIsDisplayed()
    }

    @Test
    fun `an empty list still offers Produit récurrent`() {
        showList(FakeGrocery(list = null))

        compose.onNodeWithText("Aucun article dans la liste").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.GROCERY_RECURRING_OPEN).assertIsDisplayed()
    }

    @Test
    fun `Produits wears the breadcrumb with Produits checked, and Magasins from its menu`() {
        val productRepository = mockk<ProductRepository>()
        val storeRepository = mockk<StoreRepository>()
        coEvery { storeRepository.getStores() } returns Result.success(emptyList())
        coEvery { productRepository.getProducts(any()) } returns Result.success(emptyList())
        val viewModel = ProductViewModel(productRepository, storeRepository)
        compose.setContent {
            CompositionLocalProvider(LocalGroceryNav provides nav) {
                ProductListScreen(viewModel = viewModel, recurringViewModel = FakeRecurring().viewModel)
            }
        }

        compose.onNodeWithTag(UiTags.BREADCRUMB_MODULE).assertTextEquals("Courses")
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION)
            .assertContentDescriptionEquals("Courses, section Produits : changer de section")
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()
        compose.onNodeWithTag(UiTags.breadcrumbMenu("products")).assertIsSelected()
        compose.onNodeWithTag(UiTags.breadcrumbMenu("stores")).performClick()

        assertEquals(listOf(GrocerySection.STORES), sections)
    }

    @Test
    fun `Magasins wears the breadcrumb with Magasins checked, and Liste from its menu`() {
        val storeRepository = mockk<StoreRepository>()
        coEvery { storeRepository.getStores() } returns Result.success(listOf(Store(id = "s1", name = "Super U")))
        val viewModel = StoreViewModel(storeRepository)
        compose.setContent {
            CompositionLocalProvider(LocalGroceryNav provides nav) {
                StoreListScreen(viewModel = viewModel)
            }
        }

        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION)
            .assertContentDescriptionEquals("Courses, section Magasins : changer de section")
        compose.onNodeWithText("Super U").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()
        compose.onNodeWithTag(UiTags.breadcrumbMenu("stores")).assertIsSelected()
        compose.onNodeWithTag(UiTags.breadcrumbMenu("list")).performClick()

        assertTrue(sections == listOf(GrocerySection.LIST))
    }
}
