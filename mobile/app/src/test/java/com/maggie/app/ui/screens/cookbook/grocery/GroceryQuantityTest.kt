package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `a product with a packaging is counted in it, whatever the unit of the line`() {
        assertEquals(CookbookUnit.PACK, GroceryItem(label = "Riz", product = rice).countedUnit)
        assertEquals(CookbookUnit.PACK, GroceryItem(label = "Riz", product = rice, unit = CookbookUnit.G).countedUnit)
        assertTrue(GroceryItem(label = "Riz", product = rice).isPackaged)
    }

    @Test
    fun `a product without packaging has no quantity to manage`() {
        assertNull(GroceryItem(label = "Sel").countedUnit)
        assertFalse(GroceryItem(label = "Sel").isPackaged)
        assertFalse(GroceryItem(label = "Sel", product = Product(id = "p", name = "Sel", category = ProductCategory.CONDIMENT)).isPackaged)
    }

    @Test
    fun `plus and minus move a count of packagings by one, never below one`() {
        assertEquals(1f, increasedQuantity(null))
        assertEquals(3f, increasedQuantity(2f))
        assertEquals(1f, decreasedQuantity(2f))
        assertEquals(1f, decreasedQuantity(1f))
        assertTrue(canDecreaseQuantity(2f))
        assertFalse(canDecreaseQuantity(1f))
        assertFalse(canDecreaseQuantity(null))
    }

    @Test
    fun `a typed count is a whole number above zero`() {
        assertEquals(4f, parseQuantity(" 4 "))
        listOf("", "0", "-3", "2,5", "2.5", "abc").forEach { assertNull("'$it'", parseQuantity(it)) }
    }

    @Test
    fun `what a pack holds is shown after the count of packagings`() {
        assertEquals("(500 g)", GroceryItem(label = "Riz", product = rice, unit = CookbookUnit.PACK).packagingContent())
        assertEquals("(500 g)", GroceryItem(label = "Riz", product = rice).packagingContent())
    }

    @Test
    fun `no pack content without a size or without a product`() {
        assertNull(GroceryItem(label = "Confiture", product = jam, unit = CookbookUnit.JAR).packagingContent())
        assertNull(GroceryItem(label = "Sel", unit = CookbookUnit.PACK).packagingContent())
    }

    @Test
    fun `the quantity of a recipe is an indication written with its unit`() {
        assertEquals("300 g", GroceryItem(label = "Sel", quantity = 300f, unit = CookbookUnit.G).quantityHint())
        assertEquals("2 pièces", GroceryItem(label = "Citron", quantity = 2f, unit = CookbookUnit.PIECE).quantityHint())
        assertNull(GroceryItem(label = "Sel").quantityHint())
    }
}
