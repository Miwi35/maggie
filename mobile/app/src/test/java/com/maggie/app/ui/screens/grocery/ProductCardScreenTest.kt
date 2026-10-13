package com.maggie.app.ui.screens.grocery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.ProductStockState
import com.maggie.app.data.model.RecurringFrequency
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The product screen shows what a product is bought in (MAG-292). */
@RunWith(AndroidJUnit4::class)
class ProductCardScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val rice = Product(id = "1", name = "Riz", category = ProductCategory.GRAIN)

    @Test
    fun `a product bought in packs shows its packaging`() {
        compose.setContent {
            ProductCard(
                product = rice.copy(packagingUnit = CookbookUnit.PACK, packagingSize = 500f, packagingSizeUnit = CookbookUnit.G),
                onDelete = {},
            )
        }

        compose.onNodeWithText("S'achète en : paquet de 500 g").assertIsDisplayed()
    }

    @Test
    fun `a jar of unknown content shows the jar alone`() {
        compose.setContent { ProductCard(product = rice.copy(packagingUnit = CookbookUnit.JAR), onDelete = {}) }

        compose.onNodeWithText("S'achète en : bocal").assertIsDisplayed()
    }

    @Test
    fun `a product without packaging shows no packaging line`() {
        compose.setContent { ProductCard(product = rice, onDelete = {}) }

        compose.onNodeWithText("Riz").assertIsDisplayed()
        compose.onNodeWithText("S'achète en", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a product with no state said shows En stock`() {
        compose.setContent { ProductCard(product = rice, onDelete = {}) }

        compose.onNodeWithText("En stock").assertIsDisplayed()
    }

    @Test
    fun `a product running low shows Stock faible`() {
        compose.setContent { ProductCard(product = rice.copy(stockState = ProductStockState.LOW), onDelete = {}) }

        compose.onNodeWithText("Stock faible").assertIsDisplayed()
        compose.onNodeWithText("En stock").assertDoesNotExist()
    }

    @Test
    fun `a product out of stock shows Rupture`() {
        compose.setContent { ProductCard(product = rice.copy(stockState = ProductStockState.OUT), onDelete = {}) }

        compose.onNodeWithText("Rupture").assertIsDisplayed()
        compose.onNodeWithText("En stock").assertDoesNotExist()
    }

    @Test
    fun `the card offers the recurring setting with the current choice selected`() {
        compose.setContent {
            ProductCard(product = rice, onDelete = {}, recurring = RecurringFrequency.MONTHLY, onRecurringChange = {})
        }

        compose.onNodeWithText("Récurrent").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.recurringOption("monthly")).assertIsSelected()
        compose.onNodeWithTag(UiTags.recurringOption("none")).assertIsNotSelected()
    }

    @Test
    fun `a product with no recurring item has Non selected`() {
        compose.setContent { ProductCard(product = rice, onDelete = {}, recurring = null, onRecurringChange = {}) }

        compose.onNodeWithTag(UiTags.recurringOption("none")).assertIsSelected()
    }

    @Test
    fun `tapping a choice reports it, and Non reports none`() {
        val chosen = mutableListOf<RecurringFrequency?>()
        compose.setContent {
            ProductCard(product = rice, onDelete = {}, recurring = RecurringFrequency.WEEKLY, onRecurringChange = { chosen += it })
        }

        compose.onNodeWithText("Toutes les 2 semaines").performClick()
        compose.onNodeWithText("Non").performClick()

        assertEquals(listOf(RecurringFrequency.BIWEEKLY, null), chosen)
    }

    @Test
    fun `a card without the callback shows no recurring setting`() {
        compose.setContent { ProductCard(product = rice, onDelete = {}) }

        compose.onNodeWithText("Récurrent").assertDoesNotExist()
    }
}
