package com.maggie.app.ui.screens.cookbook.recipes

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.RecipeIngredient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeEditScreenTest {
    private fun line(ingredient: JsonElement?) =
        RecipeIngredient(ingredient = ingredient, quantity = 200f, unit = CookbookUnit.G)

    @Test
    fun `the ingredient id is read from the embedded ingredient`() {
        val embedded = JsonObject(mapOf("id" to JsonPrimitive("01ABC"), "name" to JsonPrimitive("Pâtes")))

        assertEquals("01ABC", line(embedded).ingredientId())
    }

    @Test
    fun `the ingredient id is read from an IRI`() {
        assertEquals("01ABC", line(JsonPrimitive("/api/ingredients/01ABC")).ingredientId())
    }

    @Test
    fun `a line without ingredient has no id`() {
        assertEquals("", line(null).ingredientId())
    }
}
