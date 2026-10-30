package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.Micronutrient
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
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

    private fun product(id: String, kcal: Float?, protein: Float? = null, carbs: Float? = null, fat: Float? = null, pieceWeight: Float? = null) =
        Ingredient(id, id, null, ProductCategory.MEAT, kcal, protein, carbs, fat, pieceWeight)

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
    fun `a piece counts for the weight of its ingredient, a centilitre for ten grams`() {
        val onion = product("onion", kcal = 100f, pieceWeight = 100f)
        val wine = product("wine", kcal = 100f)

        assertEquals(200, nutritionPerServing(listOf(line("onion", 2f, CookbookUnit.PIECE)), 1, mapOf("onion" to onion))!!.kcal)
        assertEquals(750, nutritionPerServing(listOf(line("wine", 75f, CookbookUnit.CL)), 1, mapOf("wine" to wine))!!.kcal)
    }

    @Test
    fun `a line counted in pieces with no weight or without values is left out and counted`() {
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
    fun `the share names the day's need it is taken from, as the mockup writes it (MAG-707)`() {
        compose.setContent { RecipeNutritionBlock(RecipeNutrition(515, 40, 90, 25, 0), dailyKcal = 1980) }

        compose.onNodeWithText("26 %").assertIsDisplayed()
        compose.onNodeWithText("26 % du besoin du jour · 1 980 kcal").assertIsDisplayed()
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

    private val micronutrients = listOf(
        Micronutrient("vitaminC", "Vitamine C", "vitamins", 75f, "mg", 94),
        Micronutrient("iron", "Fer", "minerals", 3.45f, "mg", 25),
        Micronutrient("omega3Ala", "Oméga-3 ALA", "fattyAcids", 2.35f, "g", 106),
        Micronutrient("omega6", "Oméga-6", "fattyAcids", 0.048f, "g", null),
    )

    @Test
    fun `the micronutrients fold under the macros and count those known (MAG-648)`() {
        compose.setContent { RecipeNutritionBlock(RecipeNutrition(800, 40, 90, 25, 0), dailyKcal = null, micronutrients = micronutrients) }

        compose.onNodeWithText("Micronutriments · 4").assertIsDisplayed()
        compose.onNodeWithText("Vitamine C").assertDoesNotExist()
    }

    @Test
    fun `opened, they show the three groups with each share of its reference`() {
        compose.setContent { RecipeNutritionBlock(RecipeNutrition(800, 40, 90, 25, 0), dailyKcal = null, micronutrients = micronutrients) }

        compose.onNodeWithTag(UiTags.RECIPE_MICRONUTRIENTS_TOGGLE).performClick()

        listOf("Vitamines", "Minéraux", "Acides gras", "Vitamine C", "75 mg", "94 %", "Fer", "3,5 mg", "25 %", "Oméga-3 ALA", "106 %", "Oméga-6", "0,05 g")
            .forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `a group with nothing known is left out`() {
        compose.setContent { RecipeNutritionBlock(null, dailyKcal = null, micronutrients = micronutrients.take(1)) }

        compose.onNodeWithTag(UiTags.RECIPE_MICRONUTRIENTS_TOGGLE).performClick()

        compose.onNodeWithText("Vitamines").assertIsDisplayed()
        compose.onNodeWithText("Minéraux").assertDoesNotExist()
        compose.onNodeWithText("≈", substring = true).assertDoesNotExist()
    }

    @Test
    fun `without a micronutrient known there is no line`() {
        compose.setContent { RecipeNutritionBlock(RecipeNutrition(800, 40, 90, 25, 0), dailyKcal = null) }

        compose.onNodeWithText("Micronutriments", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an amount takes the decimals a small one needs`() {
        assertEquals("80 mg", micronutrientAmount(Micronutrient("c", "C", "vitamins", 80f, "mg")))
        assertEquals("1,4 mg", micronutrientAmount(Micronutrient("fe", "Fer", "minerals", 1.4f, "mg")))
        assertEquals("0,25 g", micronutrientAmount(Micronutrient("dha", "DHA", "fattyAcids", 0.25f, "g")))
        assertEquals("0 µg", micronutrientAmount(Micronutrient("d", "D", "vitamins", 0.001f, "µg")))
    }
}
