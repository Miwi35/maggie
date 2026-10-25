package com.maggie.app.ui.screens.cookbook.meals

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.model.RecipeStep
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** The words of Cuisine › Repas (MAG-628). */
class MealsWeekLabelsTest {

    @Test
    fun `a week inside a month is « 20 → 26 octobre »`() {
        assertEquals("20 → 26 octobre", weekRangeLabel(LocalDate.of(2025, 10, 20)))
    }

    @Test
    fun `a week across two months names both`() {
        assertEquals("27 octobre → 2 novembre", weekRangeLabel(LocalDate.of(2025, 10, 27)))
    }

    @Test
    fun `a day is its weekday and its number`() {
        assertEquals("Lundi 20", dayLabel(LocalDate.of(2025, 10, 20)))
        assertEquals("Dimanche 1", dayLabel(LocalDate.of(2026, 11, 1)))
    }

    private val rice = Ingredient("rice", "Riz", null, ProductCategory.MEAT, 250f)

    private fun recipe(quantity: Float, minutes: Int?) = Recipe(
        id = "r",
        name = "Riz",
        ingredients = listOf(
            RecipeIngredient(ingredient = JsonObject(mapOf("id" to JsonPrimitive("rice"))), quantity = quantity, unit = CookbookUnit.G),
        ),
        steps = listOfNotNull(minutes?.let { RecipeStep(position = 1, text = "Cuire", durationMinutes = it) }),
    )

    @Test
    fun `what a person brings is the scaled quantities over the people they were scaled to`() {
        // 800 g for four people at 250 kcal / 100 g: 2 000 kcal, 500 each.
        assertEquals("≈ 500 kcal par personne · 35 min", portionFacts(recipe(800f, 35), 4, mapOf("rice" to rice)))
        // Two people eat half the rice: the same 500 each, which is the point of « par personne ».
        assertEquals("≈ 500 kcal par personne · 35 min", portionFacts(recipe(400f, 35), 2, mapOf("rice" to rice)))
    }

    @Test
    fun `only the parts that are known are written`() {
        assertEquals("≈ 500 kcal par personne", portionFacts(recipe(800f, null), 4, mapOf("rice" to rice)))
        assertEquals("35 min", portionFacts(recipe(800f, 35), 4, emptyMap()))
        assertNull(portionFacts(recipe(800f, null), 4, emptyMap()))
    }
}
