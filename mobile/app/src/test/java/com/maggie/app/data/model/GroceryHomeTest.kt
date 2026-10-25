package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** The Courses card as the Accueil reads it, composed from the list and what the meals need (MAG-563, MAG-590). */
class GroceryHomeTest {
    private val today = LocalDate.of(2026, 10, 23)

    private val pasta = Product(id = "p1", name = "Pâtes", category = ProductCategory.GRAIN, packagingUnit = CookbookUnit.PACK)

    @Test
    fun `the lines of the list make the card, a packaged line having no need`() {
        val state = GroceryHomeState.from(
            items = listOf(
                GroceryItem(id = "1", label = "Pâtes", product = pasta, quantity = 2f, unit = CookbookUnit.PACK, needed = listOf(Need(400.0, "g")), position = 1),
                GroceryItem(id = "2", label = "Farine", quantity = 1000f, unit = CookbookUnit.G, needed = listOf(Need(800.0, "g")), position = 2),
                GroceryItem(id = "3", label = "Fait", checked = true),
            ),
            needs = listOf(GroceryRecipeNeed(productId = "p9", name = "Parmesan", date = "2026-10-23", slot = "dinner")),
            today = today,
        )

        assertEquals(2, state.count)
        assertEquals(0, state.stores.single().more)
        assertNull(state.stores.single().name)
        assertNull(state.stores.single().items[0].needed)
        assertEquals("pack", state.stores.single().items[0].unit)
        assertEquals(800.0, state.stores.single().items[1].needed?.single()?.quantity ?: 0.0, 0.0)
        assertEquals(listOf(GroceryHomeRecipeNeed("Parmesan", "today", "dinner")), state.forRecipes)
    }

    @Test
    fun `nothing open and nothing the meals lack is an empty card`() {
        assertEquals(true, GroceryHomeState.from(emptyList(), emptyList(), today).isEmpty)
        assertEquals(false, GroceryHomeState.from(emptyList(), listOf(GroceryRecipeNeed("p", "Lait", "2026-10-24", "lunch")), today).isEmpty)
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
