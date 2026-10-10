package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroceryQuantityTest {
    @Test
    fun `every unit has a name that agrees with the quantity`() {
        assertEquals("bocal", unitLabel(CookbookUnit.JAR, 1f))
        assertEquals("bocaux", unitLabel(CookbookUnit.JAR, 3f))
        assertEquals("paquets", unitLabel(CookbookUnit.PACK, 2f))
        assertEquals("", unitLabel(null, 2f))
        CookbookUnit.entries.forEach { unitLabel(it, 1f) }
    }

    private val rice = Product(
        id = "product-riz",
        name = "Riz",
        category = ProductCategory.GRAIN,
        packagingUnit = CookbookUnit.PACK,
        packagingSize = 500f,
        packagingSizeUnit = CookbookUnit.G,
    )
    private val jam = Product(id = "product-confiture", name = "Confiture", category = ProductCategory.CONDIMENT, packagingUnit = CookbookUnit.JAR)

    @Test
    fun `a line with no unit of a packaged product is counted in its packaging`() {
        assertEquals(CookbookUnit.PACK, GroceryItem(label = "Riz", product = rice).countedUnit)
        assertEquals(CookbookUnit.G, GroceryItem(label = "Riz", product = rice, unit = CookbookUnit.G).countedUnit)
        assertNull(GroceryItem(label = "Sel").countedUnit)
    }

    @Test
    fun `what a pack holds is shown after the quantity on a line counted in the packaging`() {
        assertEquals("(500 g)", GroceryItem(label = "Riz", product = rice, unit = CookbookUnit.PACK).packagingContent())
        assertEquals("(500 g)", GroceryItem(label = "Riz", product = rice).packagingContent())
    }

    @Test
    fun `a line in grams of a packaged product shows no pack content`() {
        assertNull(GroceryItem(label = "Riz", product = rice, unit = CookbookUnit.G).packagingContent())
    }

    @Test
    fun `no pack content without a size or without a product`() {
        assertNull(GroceryItem(label = "Confiture", product = jam, unit = CookbookUnit.JAR).packagingContent())
        assertNull(GroceryItem(label = "Sel", unit = CookbookUnit.PACK).packagingContent())
    }
}
