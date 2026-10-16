package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The lines the contract does not record carry `inSeason` under the name the API sends (MAG-494). */
class SeasonDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a grocery line reads inSeason`() {
        val item = json.decodeFromString(GroceryItem.serializer(), """{"id":"1","label":"Potiron","inSeason":true}""")

        assertEquals(true, item.inSeason)
    }

    @Test
    fun `a grocery line without inSeason has no season`() {
        assertNull(json.decodeFromString(GroceryItem.serializer(), """{"id":"1","label":"Bougies"}""").inSeason)
    }

    @Test
    fun `a recipe ingredient reads inSeason`() {
        val line = json.decodeFromString(
            RecipeIngredient.serializer(),
            """{"ingredientName":"Tomate","quantity":3,"unit":"piece","inSeason":false}""",
        )

        assertEquals(false, line.inSeason)
    }
}
