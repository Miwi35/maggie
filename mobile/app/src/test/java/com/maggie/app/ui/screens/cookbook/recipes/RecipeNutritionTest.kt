package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.screentest.ScreenRule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** « Par personne » at the end of the sheet (MAG-630). */
@RunWith(AndroidJUnit4::class)
class RecipeNutritionTest {
    @get:Rule val compose = ScreenRule()

    private fun product(id: String, kcal: Float?, protein: Float? = null, carbs: Float? = null, fat: Float? = null) =
        Ingredient(id, id, null, ProductCategory.MEAT, kcal, protein, carbs, fat)

    private fun line(product: String, quantity: Float, unit: CookbookUnit) = RecipeIngredient(
        ingredient = JsonObject(mapOf("id" to JsonPrimitive(product))),
        quantity = quantity,
        unit = unit,
    )

    private val beef = product("beef", kcal = 200f, protein = 20f, carbs = 0f, fat = 12f)
    private val salt = product("salt", kcal = null)

    @Test
    fun `the weights add up and split between the servings`() {
        val nutrition = nutritionPerServing(listOf(line("beef", 1f, CookbookUnit.KG)), 4, mapOf("beef" to beef))

        assertEquals(RecipeNutrition(kcal = 500, protein = 50, carbs = 0, fat = 30, skipped = 0), nutrition)
    }

    @Test
    fun `a line counted in pieces or without values is left out and counted`() {
        val lines = listOf(line("beef", 500f, CookbookUnit.G), line("beef", 2f, CookbookUnit.PIECE), line("salt", 5f, CookbookUnit.G))

        val nutrition = nutritionPerServing(lines, 2, mapOf("beef" to beef, "salt" to salt))

        assertEquals(500, nutrition!!.kcal)
        assertEquals(2, nutrition.skipped)
    }

    @Test
    fun `a recipe with nothing to count has no figures`() {
        assertNull(nutritionPerServing(listOf(line("salt", 5f, CookbookUnit.G)), 2, mapOf("salt" to salt)))
        assertNull(nutritionPerServing(emptyList(), 2, emptyMap()))
    }

    @Test
    fun `the block shows the energy and the macros`() {
        compose.setContent { RecipeNutritionBlock(RecipeNutrition(800, 40, 90, 25, 0), dailyKcal = null) }

        compose.onNodeWithText("Par personne").assertIsDisplayed()
        compose.onNodeWithText("≈ 800 kcal").assertIsDisplayed()
        compose.onNodeWithText("Protéines 40 g").assertIsDisplayed()
        compose.onNodeWithText("Lipides 25 g").assertIsDisplayed()
        compose.onNodeWithText("Glucides 90 g").assertIsDisplayed()
    }

    @Test
    fun `without a reference from Santé there is no share of the day's need`() {
        compose.setContent { RecipeNutritionBlock(RecipeNutrition(800, 40, 90, 25, 0), dailyKcal = null) }

        compose.onNodeWithText("du besoin du jour", substring = true).assertDoesNotExist()
    }

    @Test
    fun `with a reference the share of the day's need shows`() {
        compose.setContent { RecipeNutritionBlock(RecipeNutrition(800, 40, 90, 25, 0), dailyKcal = 2000) }

        compose.onNodeWithText("≈ 800 kcal").assertIsDisplayed()
        compose.onNodeWithText("40 %").assertIsDisplayed()
        compose.onNodeWithText("du besoin", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the screen does not explain the lines left out`() {
        compose.setContent { RecipeNutritionBlock(RecipeNutrition(800, 40, 90, 25, 2), dailyKcal = null) }

        compose.onNodeWithText("ne sont pas inclus", substring = true).assertDoesNotExist()
        compose.onNodeWithText("sans valeurs", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the head of the sheet gives the time and the energy`() {
        val steps = listOf(step(1, "Mijoter", minutes = 150, background = true), step(2, "Saisir", minutes = 15))

        assertEquals("⏱ 2 h 45 · ≈ 800 kcal · 4 portions", recipeSummary(steps, RecipeNutrition(800, 0, 0, 0, 0), 4))
        assertEquals("1 portion", recipeSummary(emptyList(), null, 1))
    }
}
