package com.maggie.app.ui.screens.grocery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Store
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The product sheet (MAG-605): the fields, the store cascade, the save and its error. */
@RunWith(AndroidJUnit4::class)
class ProductDetailScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val pasta = Product(id = "p1", name = "Pâtes sèches", category = ProductCategory.GRAIN)
    private val superU = Store(id = "s1", name = "Super U")
    private val aisles = mapOf("s1" to listOf(Aisle("a1", "/api/stores/s1", "Épicerie", 1)))

    private fun show(
        editor: ProductEditorState = ProductEditorState(pasta, ProductDraft.from(pasta)),
        onSave: () -> Unit = {},
        onSelectStore: (StoreSlot, String?) -> Unit = { _, _ -> },
        onSelectAisle: (StoreSlot, AisleChoice?) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            ProductDetailScreen(
                editor = editor,
                stores = listOf(superU),
                aislesByStore = aisles,
                onDraftChange = {},
                onSelectStore = onSelectStore,
                onSelectAisle = onSelectAisle,
                onPickCiqual = {},
                onSearchCiqual = { emptyList() },
                recurring = null,
                onRecurringChange = {},
                onDelete = {},
                onSave = onSave,
                onDismiss = {},
            )
        }
    }

    @Test
    fun `the sheet shows the product's name and no aisle field without a store`() {
        show()

        compose.onNodeWithText("Pâtes sèches").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PRODUCT_SHEET_STORE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PRODUCT_SHEET_AISLE).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.PRODUCT_SHEET_FALLBACK_AISLE).assertDoesNotExist()
    }

    @Test
    fun `a store chosen shows its aisle field with the aisle held`() {
        val product = pasta.copy(preferredStore = "/api/stores/s1")
        val draft = ProductDraft.from(product).withAisle(StoreSlot.PREFERRED, AisleChoice("a1"))

        show(ProductEditorState(product, draft))

        compose.onNodeWithTag(UiTags.PRODUCT_SHEET_AISLE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Épicerie").assertIsDisplayed()
    }

    @Test
    fun `choosing a store in the full-screen picker hands its id over`() {
        var chosen: Pair<StoreSlot, String?>? = null
        show(onSelectStore = { slot, id -> chosen = slot to id })

        compose.onNodeWithTag(UiTags.PRODUCT_SHEET_STORE).performScrollTo().performClick()
        compose.onNodeWithText("Choisir un magasin").assertIsDisplayed()
        compose.onNodeWithText("Super U").performClick()

        assertEquals(StoreSlot.PREFERRED to "s1", chosen)
    }

    @Test
    fun `choosing an aisle of the store hands its id over`() {
        val product = pasta.copy(preferredStore = "/api/stores/s1")
        var chosen: AisleChoice? = null
        show(ProductEditorState(product, ProductDraft.from(product)), onSelectAisle = { _, c -> chosen = c })

        compose.onNodeWithTag(UiTags.PRODUCT_SHEET_AISLE).performScrollTo().performClick()
        compose.onNodeWithText("Épicerie").performClick()

        assertEquals(AisleChoice("a1"), chosen)
    }

    @Test
    fun `Enregistrer saves`() {
        var saved = 0
        show(onSave = { saved++ })

        compose.onNodeWithTag(UiTags.PRODUCT_SHEET_SAVE).performClick()

        assertEquals(1, saved)
    }

    @Test
    fun `a refused save shows its reason in the sheet`() {
        show(ProductEditorState(pasta, ProductDraft.from(pasta), error = "Nom déjà pris"))

        compose.onNodeWithTag(UiTags.PRODUCT_SHEET_ERROR).assertIsDisplayed()
        compose.onNodeWithText("Nom déjà pris").assertIsDisplayed()
    }

    @Test
    fun `a product row reads category and unit in French and opens the sheet when touched`() {
        var opened = 0
        compose.setContent { ProductRow(product = pasta.copy(defaultUnit = CookbookUnit.PACK), onOpen = { opened++ }) }

        compose.onNodeWithText("Féculents · paquet").assertIsDisplayed()
        compose.onNodeWithText("Pâtes sèches").performClick()
        assertEquals(1, opened)
    }
}
