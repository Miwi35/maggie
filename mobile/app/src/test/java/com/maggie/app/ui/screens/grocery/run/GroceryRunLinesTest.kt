package com.maggie.app.ui.screens.grocery.run

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryRecipeNeed
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class GroceryRunLinesTest {

    private val today = LocalDate.of(2026, 11, 8)

    private val rice = Product(
        id = "p", name = "Riz", category = ProductCategory.GRAIN,
        packagingUnit = CookbookUnit.PACK, packagingSize = 500f, packagingSizeUnit = CookbookUnit.G,
    )

    @Test
    fun `a packaged line reads its count and its packaging`() {
        assertEquals("2 paquets de 500 g", GroceryItem(label = "Riz", product = rice, quantity = 2f).runQuantity())
        assertEquals("1 paquet de 500 g", GroceryItem(label = "Riz", product = rice, quantity = 1f).runQuantity())
    }

    @Test
    fun `a line without packaging reads its quantity and unit, and none when nothing was asked`() {
        assertEquals("3 pièces", GroceryItem(label = "Œufs", quantity = 3f, unit = CookbookUnit.PIECE).runQuantity())
        assertEquals("800 g", GroceryItem(label = "Farine", quantity = 800f, unit = CookbookUnit.G).runQuantity())
        assertNull(GroceryItem(label = "Pain").runQuantity())
    }

    @Test
    fun `a meal tag says when and which meal`() {
        fun need(date: String, slot: String) = GroceryRecipeNeed("p", "Lait", date, slot, "m", "Bœuf bourguignon")
        assertEquals("ce soir · bœuf bourguignon", need("2026-11-08", "dinner").runTag(today))
        assertEquals("ce midi · bœuf bourguignon", need("2026-11-08", "lunch").runTag(today))
        assertEquals("demain soir · bœuf bourguignon", need("2026-11-09", "dinner").runTag(today))
        assertEquals("samedi midi · bœuf bourguignon", need("2026-11-14", "lunch").runTag(today))
    }
}
