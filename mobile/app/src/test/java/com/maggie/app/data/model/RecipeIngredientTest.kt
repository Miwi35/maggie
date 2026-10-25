package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** A recipe line reads the same on the sheet and in the Cuisine activity (MAG-630). */
class RecipeIngredientTest {
    private fun line(quantity: Float, unit: CookbookUnit) = RecipeIngredient(quantity = quantity, unit = unit)

    @Test
    fun `a whole weight has no decimal point`() {
        assertEquals("1 kg", line(1f, CookbookUnit.KG).quantityLabel())
    }

    @Test
    fun `a count agrees with its quantity`() {
        assertEquals("2 pièces", line(2f, CookbookUnit.PIECE).quantityLabel())
        assertEquals("1 pièce", line(1f, CookbookUnit.PIECE).quantityLabel())
    }

    @Test
    fun `a volume keeps its unit`() {
        assertEquals("75 cl", line(75f, CookbookUnit.CL).quantityLabel())
    }

    @Test
    fun `a fraction uses the French decimal comma`() {
        assertEquals("1,5 kg", line(1.5f, CookbookUnit.KG).quantityLabel())
    }
}
