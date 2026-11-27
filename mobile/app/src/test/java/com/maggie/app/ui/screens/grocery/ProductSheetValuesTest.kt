package com.maggie.app.ui.screens.grocery

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/** The values the product sheet's rows read: packaging, season, restock. */
class ProductSheetValuesTest {

    private val blank = ProductDraft.from(Product(id = "p1", name = "Riz", category = ProductCategory.GRAIN))

    @Test
    fun `packaging reads Aucun without a unit`() {
        assertEquals("Aucun", packagingValue(blank))
    }

    @Test
    fun `packaging reads the unit alone`() {
        assertEquals("bocal", packagingValue(blank.copy(packagingUnit = CookbookUnit.JAR)))
    }

    @Test
    fun `packaging reads the unit, the content and its unit`() {
        val draft = blank.copy(packagingUnit = CookbookUnit.PACK, packagingSize = "1", packagingSizeUnit = CookbookUnit.KG)
        assertEquals("paquet · 1 kg", packagingValue(draft))
    }

    @Test
    fun `packaging pluralises the content unit`() {
        val draft = blank.copy(packagingUnit = CookbookUnit.PACK, packagingSize = "6", packagingSizeUnit = CookbookUnit.PIECE)
        assertEquals("paquet · 6 pièces", packagingValue(draft))
    }

    @Test
    fun `season reads Toute l'année without a month`() {
        assertEquals("Toute l'année", seasonValue(emptyList()))
    }

    @Test
    fun `season reads a range for months in a row`() {
        assertEquals("mars – juin", seasonValue(listOf(3, 4, 5, 6)))
    }

    @Test
    fun `season lists months that do not follow each other`() {
        assertEquals("mars, mai, sept.", seasonValue(listOf(9, 3, 5)))
    }

    @Test
    fun `season reads one month alone`() {
        assertEquals("août", seasonValue(listOf(8)))
    }

    @Test
    fun `restock reads the count with the packaging unit, plural included`() {
        val draft = blank.copy(packagingUnit = CookbookUnit.JAR, restockQuantity = "1")
        assertEquals("1 bocal", restockValue(draft))
        assertEquals("2 bocaux", restockValue(draft.copy(restockQuantity = "2")))
    }

    @Test
    fun `restock reads 0 for an empty field and the bare number without a unit`() {
        assertEquals("0", restockValue(blank))
        assertEquals("3", restockValue(blank.copy(restockQuantity = "3")))
    }
}
