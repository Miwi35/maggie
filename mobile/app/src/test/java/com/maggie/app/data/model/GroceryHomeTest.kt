package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** The `/api/home/grocery` answer as the Courses card reads it (MAG-563). */
class GroceryHomeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the answer decodes, a packaged line having no need`() {
        val state = json.decodeFromString(
            GroceryHomeState.serializer(),
            """{"count":6,"forRecipes":[{"name":"Parmesan","day":"today","slot":"dinner"}],
               "stores":[{"id":null,"name":null,"count":6,"more":1,"items":[
                 {"id":"1","name":"Pâtes","quantity":2,"unit":"pack","needed":null},
                 {"id":"2","name":"Farine","quantity":1000,"unit":"g","needed":[{"quantity":800,"unit":"g"}]}]}]}""",
        )

        assertEquals(6, state.count)
        assertEquals(1, state.stores.single().more)
        assertEquals(null, state.stores.single().name)
        assertEquals(null, state.stores.single().items[0].needed)
        assertEquals(800.0, state.stores.single().items[1].needed?.single()?.quantity ?: 0.0, 0.0)
    }

    @Test
    fun `a meal's need says the moment, today at noon or at night, tomorrow whatever the slot`() {
        assertEquals("parmesan (ce soir)", GroceryHomeRecipeNeed("Parmesan", "today", "dinner").label())
        assertEquals("citron (ce midi)", GroceryHomeRecipeNeed("Citron", "today", "lunch").label())
        assertEquals("crème (demain)", GroceryHomeRecipeNeed("Crème", "tomorrow", "dinner").label())
    }

    @Test
    fun `the needs are joined in one line`() {
        val needs = listOf(GroceryHomeRecipeNeed("Parmesan", "today", "dinner"), GroceryHomeRecipeNeed("Crème", "tomorrow", "lunch"))

        assertEquals("parmesan (ce soir), crème (demain)", needs.recipeNeedsLabel())
    }
}
