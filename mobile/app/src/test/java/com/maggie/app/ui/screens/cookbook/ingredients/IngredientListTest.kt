package com.maggie.app.ui.screens.cookbook.ingredients

import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.ProductCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IngredientListTest {
    private fun ingredient(name: String, kcal: Float? = null, piece: Float? = null, ciqual: String? = null) =
        Ingredient("id-$name", name, null, ProductCategory.MEAT, kcalPer100g = kcal, pieceWeightGrams = piece, ciqualAlimCode = ciqual)

    private val oignon = ingredient("Oignon jaune", 40f, 100f, "20034")
    private val sel = ingredient("Sel")
    private val maison = ingredient("Sauce maison", 120f)
    private val all = listOf(oignon, sel, maison)

    @Test
    fun `ciqual code means ciqual`() {
        assertEquals(IngredientValuesState.CIQUAL, ingredientValuesState(oignon))
    }

    @Test
    fun `no code and no energy means no values`() {
        assertEquals(IngredientValuesState.NONE, ingredientValuesState(sel))
    }

    @Test
    fun `hand values have no state`() {
        assertNull(ingredientValuesState(maison))
    }

    @Test
    fun `pills count each state`() {
        assertEquals(listOf(3, 1, 1), ingredientPills(all).map { it.count })
        assertEquals(listOf("Tous", "Sans valeurs", "Ciqual"), ingredientPills(all).map { it.label })
    }

    @Test
    fun `filter ignores accents and case`() {
        assertEquals(listOf(oignon), filterIngredients(all, "  ÓIGNON", IngredientPill.ALL))
        assertEquals(listOf(sel), filterIngredients(all, "", IngredientPill.NONE))
    }

    @Test
    fun `facts join energy and piece weight`() {
        assertEquals("40 kcal · 1 pièce ≈ 100 g", ingredientFacts(oignon))
        assertEquals("120 kcal", ingredientFacts(maison))
        assertEquals("1 pièce ≈ 15 g", ingredientFacts(ingredient("Ail", piece = 15f)))
    }

    @Test
    fun `facts null without values`() {
        assertNull(ingredientFacts(sel))
    }
}
