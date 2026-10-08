package com.maggie.app.ui.screens.grocery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.ProductStockState
import com.maggie.app.screentest.ScreenRule
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
}
