package com.maggie.app.ui.screens.cookbook.recipes

import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeDetailScreenTest {
    @Test
    fun `the confirmation announces how many planned meals leave with the recipe`() {
        assertEquals("Supprimer « Couscous » et ses 3 repas planifiés ?", recipeDeletionTitle("Couscous", 3))
    }

    @Test
    fun `a single meal is announced in the singular`() {
        assertEquals("Supprimer « Couscous » et son repas planifié ?", recipeDeletionTitle("Couscous", 1))
    }

    @Test
    fun `a recipe without planned meals asks the plain question`() {
        assertEquals("Supprimer « Couscous » ?", recipeDeletionTitle("Couscous", 0))
    }

    @Test
    fun `a count that could not be read asks the plain question`() {
        assertEquals("Supprimer « Couscous » ?", recipeDeletionTitle("Couscous", null))
    }
}
