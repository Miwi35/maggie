package com.maggie.app.ui.screens.cookbook.recipes

import com.maggie.app.data.api.PlannedMealRef
import com.maggie.app.data.api.RecipeDeletionImpact
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

    @Test
    fun `the body names the day and slot of each meal that leaves`() {
        val body = recipeDeletionBody(
            RecipeDeletionImpact(2, listOf(PlannedMealRef("2030-01-14", "dinner"), PlannedMealRef("2030-01-17", "lunch"))),
        )

        assertEquals(
            "Vous aviez prévu de cuisiner cette recette :\n• lundi 14 janvier, soir\n• jeudi 17 janvier, midi" +
                "\n\nCes repas seront supprimés avec elle, de l’agenda comme de la liste de courses. Cette action est définitive.",
            body,
        )
    }

    @Test
    fun `the body lists the first meals and counts the rest`() {
        val meals = (14..20).map { PlannedMealRef("2030-01-$it", "lunch") }

        val body = recipeDeletionBody(RecipeDeletionImpact(7, meals))

        assertEquals(true, body.contains("• et 2 autres"))
        assertEquals(false, body.contains("19 janvier"))
    }

    @Test
    fun `a recipe without planned meals keeps the plain body`() {
        assertEquals(
            "Les repas qui n’ont que cette recette disparaissent de l’agenda et de la liste de courses. Cette action est définitive.",
            recipeDeletionBody(RecipeDeletionImpact(0)),
        )
    }

    @Test
    fun `an impact that could not be read still warns about the meals`() {
        assertEquals(
            "Les repas planifiés qui n’ont que cette recette seront supprimés avec elle.",
            recipeDeletionBody(null),
        )
    }
}
