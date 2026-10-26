package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Cuisine card's state, composed from the day's meals and what their recipes lack (MAG-591). */
class CuisineHomeTest {
    private fun meal(id: String, slot: MealSlot, summary: String = "Repas $id", recipeNames: List<String> = emptyList(), guests: Int = 1) =
        Meal(id = id, summary = summary, date = "2026-10-23", slot = slot, recipeNames = recipeNames, guests = guests)

    private fun need(mealId: String, productId: String, name: String) =
        GroceryRecipeNeed(productId = productId, name = name, date = "2026-10-23", slot = "dinner", mealId = mealId)

    @Test
    fun `both slots are there, lunch first, an empty one having no meal`() {
        val state = CuisineHomeState.from(listOf(meal("m1", MealSlot.DINNER)), emptyList())

        assertEquals(listOf("lunch", "dinner"), state.slots.map { it.slot })
        assertEquals(emptyList<CuisineHomeMeal>(), state.slots[0].meals)
        assertEquals(listOf("m1"), state.slots[1].meals.map { it.id })
        assertTrue(!state.isEmpty)
    }

    @Test
    fun `no meal at all is an empty state`() {
        assertTrue(CuisineHomeState.from(emptyList(), emptyList()).isEmpty)
    }

    @Test
    fun `a meal carries its title, its guests and the names of its recipes`() {
        val meals = CuisineHomeState.from(
            listOf(meal("m1", MealSlot.DINNER, summary = "Salade verte", recipeNames = listOf("Salade verte", "Tarte", "Fruits"), guests = 2)),
            emptyList(),
        ).slots[1].meals

        assertEquals("Salade verte", meals[0].title)
        assertEquals(2, meals[0].guests)
        assertEquals(listOf("Salade verte", "Tarte", "Fruits"), meals[0].recipes)
    }

    @Test
    fun `what is missing goes to its meal, each product once`() {
        val state = CuisineHomeState.from(
            listOf(meal("a", MealSlot.LUNCH), meal("b", MealSlot.DINNER)),
            listOf(need("a", "p1", "Parmesan"), need("a", "p2", "Basilic"), need("a", "p1", "Parmesan"), need("b", "p1", "Parmesan")),
        )

        assertEquals(listOf("Parmesan", "Basilic"), state.slots[0].meals.single().missing)
        assertEquals(listOf("Parmesan"), state.slots[1].meals.single().missing)
    }

    @Test
    fun `a need of a meal that is not there is ignored`() {
        val state = CuisineHomeState.from(listOf(meal("a", MealSlot.LUNCH)), listOf(need("gone", "p1", "Lait")))

        assertEquals(emptyList<String>(), state.slots[0].meals.single().missing)
    }

    @Test
    fun `all the recipes of a meal are on one line, the title when it has none`() {
        val withRecipes = CuisineHomeMeal(id = "a", title = "Salade verte", recipes = listOf("Salade verte", "Tarte", "Fruits"))
        val without = CuisineHomeMeal(id = "b", title = "Restes")

        assertEquals("Salade verte · Tarte · Fruits", withRecipes.recipesLabel())
        assertEquals("Restes", without.recipesLabel())
    }

    @Test
    fun `what is missing reads in lower case, and is null when nothing is`() {
        assertEquals("manque : parmesan, basilic", CuisineHomeMeal(id = "a", missing = listOf("Parmesan", "Basilic")).missingLabel())
        assertNull(CuisineHomeMeal(id = "b").missingLabel())
    }

    @Test
    fun `the slots are named in French`() {
        assertEquals("Déjeuner", CuisineHomeSlot("lunch").label())
        assertEquals("Dîner", CuisineHomeSlot("dinner").label())
    }
}
