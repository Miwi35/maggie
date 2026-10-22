package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Adding or editing a line in quantity or in packagings (MAG-573), on the sheets themselves: one test
 * per transition of the entry — prefill, switch of mode, stepper — and what each one sends.
 */
@RunWith(AndroidJUnit4::class)
class QuantityEntryScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val pasta = Product(
        id = "p1",
        name = "Pâtes sèches",
        category = ProductCategory.GRAIN,
        defaultUnit = CookbookUnit.G,
        packagingUnit = CookbookUnit.PACK,
        packagingSize = 500f,
        packagingSizeUnit = CookbookUnit.G,
        restockQuantity = 2,
    )
    private val courgettes = Product(id = "p2", name = "Courgettes", category = ProductCategory.PRODUCE, defaultUnit = CookbookUnit.PIECE)

    private var added: QuantityPayload? = null
    private var saved: QuantityPayload? = null
    private var saveCalls = 0

    private fun openAddSheetOn(product: Product) {
        compose.setContent {
            AddItemSheet(
                products = listOf(pasta, courgettes),
                stores = emptyList(),
                onAddItem = { _, quantity, _, _, _ -> added = quantity },
                onDismiss = {},
            )
        }
        compose.onNodeWithText("Nom du produit").performClick()
        compose.onNodeWithText(product.name).performClick()
    }

    private fun openEditSheetOn(item: GroceryItem) {
        compose.setContent {
            ItemDetailContent(
                item = item,
                products = listOf(pasta, courgettes),
                stores = emptyList(),
                onSave = { _, quantity, _, _, _ ->
                    saved = quantity
                    saveCalls++
                },
            )
        }
    }

    @Test
    fun `a packaged product opens in packagings, prefilled with its automatic restock`() {
        openAddSheetOn(pasta)

        compose.onNodeWithTag(UiTags.GROCERY_PACKAGINGS_FIELD).assertTextContains("2")
        compose.onNodeWithTag(UiTags.GROCERY_PACKAGING_CAPTION).assertTextEquals("paquets de 500 g")
        compose.onNodeWithTag(UiTags.GROCERY_PREFILLED_CHIP).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_CONVERSION).assertTextEquals("= 1 kg")

        compose.onNodeWithText("Ajouter").performClick()

        assertEquals(QuantityPayload(packagings = 2), added)
    }

    @Test
    fun `the plus button changes the count and drops the prefilled badge`() {
        openAddSheetOn(pasta)

        compose.onNodeWithContentDescription("Augmenter le nombre de conditionnements").performClick()

        compose.onNodeWithTag(UiTags.GROCERY_PACKAGINGS_FIELD).assertTextContains("3")
        compose.onNodeWithTag(UiTags.GROCERY_PREFILLED_CHIP).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_CONVERSION).assertTextEquals("= 1,5 kg")
    }

    @Test
    fun `switching to quantity turns 300 g into one packaging to buy and sends the quantity alone`() {
        openAddSheetOn(pasta)

        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_MODE_QUANTITY).performClick()
        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_FIELD).performTextInput("300")

        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_CONVERSION).assertTextEquals("= 1 paquet de 500 g à acheter")

        compose.onNodeWithText("Ajouter").performClick()

        assertEquals(QuantityPayload(quantity = 300f, unit = "g"), added)
    }

    @Test
    fun `a product without a packaging has the quantity mode only`() {
        openAddSheetOn(courgettes)

        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_MODE_PACKAGINGS).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_FIELD).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_CONVERSION).assertDoesNotExist()

        compose.onNodeWithTag(UiTags.GROCERY_QUANTITY_FIELD).performTextInput("4")
        compose.onNodeWithText("Ajouter").performClick()

        assertEquals(QuantityPayload(quantity = 4f, unit = "piece"), added)
    }

    @Test
    fun `editing a packaged line steps its packagings and sends them`() {
        openEditSheetOn(GroceryItem(id = "i1", label = "Pâtes sèches", product = pasta, quantity = 3f, unit = CookbookUnit.PACK))

        compose.onNodeWithTag(UiTags.GROCERY_PACKAGINGS_FIELD).assertTextContains("3")
        compose.onNodeWithContentDescription("Diminuer le nombre de conditionnements").performClick()
        compose.onNodeWithText("Enregistrer").performClick()

        assertEquals(QuantityPayload(packagings = 2), saved)
    }

    @Test
    fun `saving a packaged line without touching its quantity sends none`() {
        openEditSheetOn(GroceryItem(id = "i1", label = "Pâtes sèches", product = pasta, quantity = 3f, unit = CookbookUnit.PACK))

        compose.onNodeWithText("Enregistrer").performClick()

        assertEquals(1, saveCalls)
        assertNull(saved)
    }
}
