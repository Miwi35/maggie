package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.Need
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.PurchasedItem
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
    fun `a line without packaging is counted in its own unit`() {
        assertNull(GroceryItem(label = "Sel").countedUnit)
        assertEquals(CookbookUnit.G, GroceryItem(label = "Sel", unit = CookbookUnit.G).countedUnit)
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
    fun `a loose line steps by what its unit calls for`() {
        assertEquals(100f, stepOf(CookbookUnit.G))
        assertEquals(0.5f, stepOf(CookbookUnit.KG))
        assertEquals(1f, stepOf(CookbookUnit.PIECE))
        assertEquals(1f, stepOf(null))
        assertEquals(1f, GroceryItem(label = "Tomates", quantity = 4f, unit = CookbookUnit.PIECE).quantityStep)
        assertEquals(100f, GroceryItem(label = "Sel", quantity = 300f, unit = CookbookUnit.G).quantityStep)
        assertEquals(1f, GroceryItem(label = "Riz", product = rice, unit = CookbookUnit.G).quantityStep)
    }

    @Test
    fun `plus and minus move a loose quantity by its step, never below one step`() {
        assertEquals(400f, increasedQuantity(300f, 100f))
        assertEquals(200f, decreasedQuantity(300f, 100f))
        assertEquals(100f, decreasedQuantity(100f, 100f))
        assertTrue(canDecreaseQuantity(300f, 100f))
        assertFalse(canDecreaseQuantity(100f, 100f))
        assertFalse(canDecreaseQuantity(null, 100f))
    }

    @Test
    fun `a loose quantity may be typed with decimals`() {
        assertEquals(1.5f, parseQuantity("1,5", whole = false))
        assertEquals(2f, parseQuantity("2", whole = false))
        listOf("", "0", "-3", "abc", "NaN").forEach { assertNull("'$it'", parseQuantity(it, whole = false)) }
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
    fun `a line reminds what the meals need`() {
        val salt = GroceryItem(label = "Sel", quantity = 300f, unit = CookbookUnit.G, needed = listOf(Need(800.0, "g")))
        val mixed = GroceryItem(label = "Citron", needed = listOf(Need(300.0, "g"), Need(2.0, "piece")))

        assertEquals("800 g pour les repas", salt.neededReminder())
        assertEquals("300 g + 2 pièces pour les repas", mixed.neededReminder())
        assertNull(salt.copy(needed = null).neededReminder())
    }

    @Test
    fun `a packaged line reminds what the meals need, and only that`() {
        val packaged = GroceryItem(
            label = "Riz", product = rice, quantity = 2f, unit = CookbookUnit.PACK, needed = listOf(Need(800.0, "g")),
        )

        assertEquals("800 g pour les repas", packaged.neededReminder())
        assertNull(packaged.copy(needed = null).neededReminder())
        assertNull(packaged.copy(needed = emptyList()).neededReminder())
    }

    @Test
    fun `a past errand's packaged line reads in packagings, with what one holds`() {
        val rice = PurchasedItem(
            label = "Riz",
            quantity = 2f,
            unit = CookbookUnit.PACK,
            packagingUnit = CookbookUnit.PACK,
            packagingSize = 500f,
            packagingSizeUnit = CookbookUnit.G,
        )

        assertEquals("2 paquets · paquet de 500 g", rice.quantityLabel())
        assertEquals("1 paquet · paquet de 500 g", rice.copy(quantity = 1f).quantityLabel())
        // No size: « 2 paquets · paquet » would say the same thing twice.
        assertEquals("2 paquets", rice.copy(packagingSize = null).quantityLabel())
    }

    @Test
    fun `a past errand's loose line reads in its own unit, or not at all without a quantity`() {
        assertEquals("3 pièces", PurchasedItem(label = "Citrons", quantity = 3f, unit = CookbookUnit.PIECE).quantityLabel())
        assertEquals("1,5 kg", PurchasedItem(label = "Pommes", quantity = 1.5f, unit = CookbookUnit.KG).quantityLabel())
        assertEquals("2", PurchasedItem(label = "Sacs", quantity = 2f).quantityLabel())
        assertNull(PurchasedItem(label = "Sacs").quantityLabel())
    }
}
