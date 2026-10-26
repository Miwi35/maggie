package com.maggie.app.ui.screens.cookbook.cooking

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.data.model.StepIngredient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a step takes (MAG-581), the same as the admin's `cookingSteps.test.ts`. */
class CookingStepsTest {

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
