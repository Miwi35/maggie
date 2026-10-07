package com.maggie.app.ui.screens.cookbook.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeLiveUpdateTest {

    private fun apply(data: String) =
        (parseRecipeMessage(data, "recipe-1") as RecipeMessage.Changed).patch.applyTo(carbonara())

    @Test
    fun `a new quantity replaces the line and leaves the rest of the recipe`() {
        val recipe = apply(published(publishedLine(350)))

        assertEquals(350f, recipe.ingredients.single().quantity)
        assertEquals("Pâtes", recipe.ingredients.single().ingredientName)
        assertEquals("Sans crème", recipe.notes)
        assertEquals(listOf("pâtes"), recipe.tags)
    }

    @Test
    fun `notes, tags, name and servings are replaced`() {
        val recipe = apply(published(""""name":"Carbo","servings":2,"tags":["rapide","italien"],"notes":"Avec du poivre""""))

        assertEquals("Carbo", recipe.name)
        assertEquals(2, recipe.servings)
        assertEquals(listOf("rapide", "italien"), recipe.tags)
        assertEquals("Avec du poivre", recipe.notes)
    }

    @Test
    fun `notes published as null are cleared, notes not published are kept`() {
        assertNull(apply(published(""""notes":null""")).notes)
        assertEquals("Sans crème", apply(published(""""servings":2""")).notes)
    }

    @Test
    fun `an emptied ingredient list empties the recipe`() {
        assertEquals(emptyList<Any>(), apply(published(""""ingredients":[]""")).ingredients)
    }

    @Test
    fun `a message about another recipe is not for this sheet`() {
        assertEquals(RecipeMessage.Elsewhere, parseRecipeMessage(published(publishedLine(350), id = "recipe-2"), "recipe-1"))
    }

    @Test
    fun `a deletion is told apart`() {
        assertEquals(RecipeMessage.Deleted, parseRecipeMessage("""{"@id":"/api/recipes/recipe-1","deleted":true}""", "recipe-1"))
    }

    @Test
    fun `a message that is not JSON, or whose lines cannot be read, asks for a fetch`() {
        assertEquals(RecipeMessage.Unreadable, parseRecipeMessage("not json", "recipe-1"))
        assertEquals(RecipeMessage.Unreadable, parseRecipeMessage(published(""""ingredients":[{"quantity":"x"}]"""), "recipe-1"))
        assertTrue(parseRecipeMessage(published(publishedLine(1)), "recipe-1") is RecipeMessage.Changed)
    }
}
