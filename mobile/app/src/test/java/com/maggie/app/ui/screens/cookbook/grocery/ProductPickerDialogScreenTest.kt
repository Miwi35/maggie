package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CiqualFood
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Store
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The full-screen product search like `Produit-Recherche-Tel` (MAG-1055). */
@RunWith(AndroidJUnit4::class)
class ProductPickerDialogScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val milk = Product(id = "p1", name = "Lait", category = ProductCategory.DAIRY, preferredStore = "/api/stores/s1")
    private val stores = listOf(Store(id = "s1", name = "Biocoop"))

    private var dismissed = 0
    private var custom: String? = null

    private fun show(
        withCustom: Boolean = true,
        ciqual: Boolean = false,
    ) {
        compose.setContent {
            ProductPickerDialog(
                products = listOf(milk),
                stores = stores,
                onSelectProduct = {},
                onSelectCustomLabel = if (withCustom) ({ custom = it }) else null,
                onDismiss = { dismissed++ },
                onSearchCiqual = if (ciqual) ({ listOf(CiqualFood(alimCode = "1", alimNameFr = "Tisane", alimGroupNameFr = "Boissons")) }) else null,
                onSelectCiqualFood = if (ciqual) ({}) else null,
            )
        }
    }

    @Test
    fun `no title bar, back reads Fermer la recherche`() {
        show()

        compose.onAllNodesWithText("Rechercher un produit").assertCountEquals(0)
        compose.onNodeWithTag(UiTags.PRODUCT_SEARCH).assertIsDisplayed()
        compose.onNodeWithContentDescription("Fermer la recherche").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `product row reads Produit store category`() {
        show()

        compose.onNodeWithTag(UiTags.PRODUCT_SEARCH).performTextInput("Lai")

        compose.onNodeWithText("Lait").assertIsDisplayed()
        compose.onNodeWithText("Produit · Biocoop · Produits laitiers").assertIsDisplayed()
    }

    @Test
    fun `ciqual row reads Ingrédient Ciqual`() {
        show(ciqual = true)

        compose.onNodeWithTag(UiTags.PRODUCT_SEARCH).performTextInput("Tisa")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Ingrédient Ciqual").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Tisane").assertIsDisplayed()
        compose.onAllNodesWithText("Ciqual · Boissons").assertCountEquals(0)
    }

    @Test
    fun `create row sits under the list and calls onSelectCustomLabel`() {
        show()

        compose.onNodeWithTag(UiTags.PRODUCT_SEARCH).performTextInput("Lait")
        val create = compose.onNodeWithText("+ Créer « Lait »")
        create.assertIsDisplayed()
        val productBottom = compose.onNodeWithText("Produit \u00b7 Biocoop \u00b7 Produits laitiers").getUnclippedBoundsInRoot().bottom
        assertTrue(create.getUnclippedBoundsInRoot().top >= productBottom)
        create.performClick()

        assertEquals("Lait", custom)
    }

    @Test
    fun `result rows sit 16 dp in from the screen edge`() {
        show()

        compose.onNodeWithTag(UiTags.PRODUCT_SEARCH).performTextInput("Lait")

        // 16 dp screen padding + 4 dp row padding
        assertEquals(20f, compose.onNodeWithText("Produit · Biocoop · Produits laitiers", useUnmergedTree = true).getUnclippedBoundsInRoot().left.value, 0.5f)
        assertEquals(20f, compose.onNodeWithText("+ Créer « Lait »", useUnmergedTree = true).getUnclippedBoundsInRoot().left.value, 0.5f)
    }

    @Test
    fun `create row hidden without onSelectCustomLabel`() {
        show(withCustom = false)

        compose.onNodeWithTag(UiTags.PRODUCT_SEARCH).performTextInput("Lait")

        compose.onAllNodesWithText("Créer", substring = true).assertCountEquals(0)
    }
}
