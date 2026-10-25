package com.maggie.app.ui.screens.cookbook.cooking

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.data.model.StepIngredient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a step waits for and takes (MAG-581), the same as the admin's `cookingSteps.test.ts`. */
class CookingStepsTest {

    /** The reference example: water, cut, pasta (← 1), vegetables, drain (← 3 and 4). */
    private val reference = listOf(
        RecipeStep(position = 1, text = "Mettre l'eau à bouillir", durationMinutes = 10, background = true),
        RecipeStep(position = 2, text = "Couper les légumes"),
        RecipeStep(position = 3, text = "Cuire les pâtes", durationMinutes = 12, background = true, waitsFor = listOf(1)),
        RecipeStep(position = 4, text = "Faire revenir les légumes", durationMinutes = 8),
        RecipeStep(position = 5, text = "Égoutter, mélanger", waitsFor = listOf(3, 4)),
    )

    @Test
    fun `a timed step is awaited until Cest pret, an untimed one is finished once passed`() {
        assertEquals(listOf(1), pendingWaits(reference, 3, ready = emptyList()))
        assertEquals(emptyList<Int>(), pendingWaits(reference, 3, ready = listOf(1)))
        assertEquals(listOf(3, 4), pendingWaits(reference, 5, ready = emptyList()))
        assertEquals(listOf(4), pendingWaits(reference, 5, ready = listOf(3)))
        assertEquals(emptyList<Int>(), pendingWaits(reference, 2, ready = emptyList()))
    }

    @Test
    fun `a step that waits for an untimed step or a missing one is not held`() {
        val steps = listOf(
            RecipeStep(position = 1, text = "Couper"),
            RecipeStep(position = 2, text = "Cuire", waitsFor = listOf(1, 9)),
        )

        assertEquals(emptyList<Int>(), pendingWaits(steps, 2, ready = emptyList()))
        assertEquals(emptyList<Int>(), pendingWaits(steps, 7, ready = emptyList()))
    }

    @Test
    fun `the awaited step is named from its first article`() {
        assertEquals("l'eau à bouillir", awaitedName("Mettre l'eau à bouillir"))
        assertEquals("les pâtes", awaitedName("Cuire les pâtes"))
        assertEquals("les légumes", awaitedName("Faire revenir les légumes"))
        assertEquals("mijoter", awaitedName("Mijoter"))
        assertEquals("les pommes de terre en", awaitedName("Éplucher les pommes de terre en petits cubes"))
    }

    @Test
    fun `the wait names what is awaited and its steps`() {
        assertEquals("Attend l'eau à bouillir · étape 1", waitLabel(reference, listOf(1)))
        assertEquals("Attend les pâtes et les légumes · étapes 3 et 4", waitLabel(reference, listOf(3, 4)))
    }

    @Test
    fun `the ingredients of a step carry the scaled line of the recipe`() {
        val flour = RecipeIngredient(id = "l1", ingredient = JsonObject(mapOf("id" to JsonPrimitive("i-flour"))), ingredientName = "Farine", quantity = 300f, unit = CookbookUnit.G)
        val milk = RecipeIngredient(id = "l2", ingredient = JsonPrimitive("/api/ingredients/i-milk"), ingredientName = "Lait", quantity = 2f, unit = CookbookUnit.L)
        val step = RecipeStep(
            position = 1,
            text = "Mélanger",
            ingredients = listOf(StepIngredient("i-flour", "Farine"), StepIngredient("i-milk", "Lait"), StepIngredient("i-egg", "Oeuf")),
        )

        val needs = stepIngredients(step, listOf(flour, milk))

        assertEquals(listOf("Farine", "Lait", "Oeuf"), needs.map { it.name })
        assertEquals(listOf(flour, milk, null), needs.map { it.line })
    }
}
