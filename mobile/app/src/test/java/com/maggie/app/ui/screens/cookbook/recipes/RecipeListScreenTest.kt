package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.data.repository.IngredientRepository
import com.maggie.app.data.repository.RecipeRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cuisine › Recettes as the mock-up draws it (MAG-628): search, pills, an initial on a disc, « 500 kcal · 35 min ». */
@RunWith(AndroidJUnit4::class)
class RecipeListScreenTest {
    @get:Rule val compose = ScreenRule()

    private val tarte = Recipe(
        id = "r-tarte",
        name = "Tarte aux pommes",
        servings = 2,
        tags = listOf("végétarien"),
        ingredients = listOf(
            RecipeIngredient(ingredient = JsonObject(mapOf("id" to JsonPrimitive("rice"))), quantity = 400f, unit = CookbookUnit.G),
        ),
        steps = listOf(RecipeStep(position = 1, text = "Cuire", durationMinutes = 35)),
    )
    private val curry = Recipe(id = "r-curry", name = "Curry de pois chiches", tags = listOf("Plat"))

    private fun show(onRecipeClick: (String) -> Unit = {}) {
        val recipes = mockk<RecipeRepository>()
        every { recipes.observeRecipes() } returns flowOf(listOf(tarte, curry))
        coEvery { recipes.refreshRecipes() } returns Result.success(listOf(tarte, curry))
        val ingredients = mockk<IngredientRepository>()
        coEvery { ingredients.getIngredients() } returns
            Result.success(listOf(Ingredient("rice", "Riz", null, ProductCategory.MEAT, 250f)))
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        val auth = mockk<AuthRepository>()
        coEvery { auth.getUserId() } returns "user-1"

        compose.setContent {
            RecipeListScreen(RecipeListViewModel(recipes, ingredients, mercure, auth), onRecipeClick)
        }
        compose.waitForIdle()
    }

    @Test
    fun `a row is an initial, a name and what the recipe brings, only the parts that exist`() {
        show()

        compose.onNodeWithText("Tarte aux pommes").assertExists()
        compose.onNodeWithText("500 kcal · 35 min").assertExists()
        compose.onNodeWithText("Curry de pois chiches").assertExists()
        assertEquals(2, compose.onAllNodesWithTag(UiTags.RECIPE_ROW_AVATAR, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("T").assertExists()
        compose.onNodeWithText("C").assertExists()
        // The curry is not described by the server: no subtitle, not an empty line.
        assertEquals(1, compose.onAllNodesWithTag(UiTags.RECIPE_ROW_FACTS, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `the pills sit above the search field`() {
        show()

        assertTopToBottom(
            "pills" to compose.onNodeWithTag(UiTags.subview("all")),
            "search" to compose.onNodeWithTag(UiTags.RECIPE_SEARCH_FIELD),
        )
    }

    @Test
    fun `the pills are Toutes and the tags the recipes carry`() {
        show()

        compose.onNodeWithTag(UiTags.subview("all")).assertExists().assertIsSelected()
        assertEquals(
            Role.Tab,
            compose.onNodeWithTag(UiTags.subview("all")).fetchSemanticsNode().config[SemanticsProperties.Role],
        )
        compose.onNodeWithTag(UiTags.subview("tag:Plat")).assertExists()
        compose.onNodeWithTag(UiTags.subview("tag:végétarien")).assertExists()
        compose.onNodeWithText("Végétarien").assertExists()
        compose.onNodeWithText("Rapide").assertDoesNotExist()
    }

    @Test
    fun `a pill narrows the list and Toutes brings it back`() {
        show()

        compose.onNodeWithTag(UiTags.subview("tag:Plat")).performClick()
        compose.onNodeWithText("Tarte aux pommes").assertDoesNotExist()
        compose.onNodeWithText("Curry de pois chiches").assertExists()

        compose.onNodeWithTag(UiTags.subview("all")).performClick()
        compose.onNodeWithText("Tarte aux pommes").assertExists()

        compose.onNodeWithTag(UiTags.subview("tag:Plat")).performClick()
        compose.onNodeWithTag(UiTags.subview("tag:Plat")).performClick()
        compose.onNodeWithText("Tarte aux pommes").assertExists()
    }

    @Test
    fun `a row shows its first tag in lower case, a recipe without tag has none`() {
        show()

        compose.onNodeWithText("végétarien").assertExists()
        assertNull(recipeChip(Recipe(id = "r-none", name = "Nu", tags = emptyList())))
    }

    @Test
    fun `recipePills capitalises each tag and starts with Toutes`() {
        val pills = recipePills(listOf("végétarien", "Plat"))

        assertEquals(listOf("all", "tag:végétarien", "tag:Plat"), pills.map { it.key })
        assertEquals(listOf("Toutes", "Végétarien", "Plat"), pills.map { it.label })
    }

    @Test
    fun `recipeChip takes the first non-blank tag in lower case`() {
        assertEquals("four", recipeChip(Recipe(id = "r", name = "R", tags = listOf(" ", " Four "))))
    }

    @Test
    fun `typing in the search field narrows the list, and says so when nothing matches`() {
        show()

        compose.onNodeWithTag(UiTags.RECIPE_SEARCH_FIELD).performTextInput("TARTE")
        compose.onNodeWithText("Curry de pois chiches").assertDoesNotExist()
        compose.onNodeWithText("Tarte aux pommes").assertExists()

        compose.onNodeWithTag(UiTags.RECIPE_SEARCH_FIELD).performTextInput("zzz")
        compose.onNodeWithText("Aucune recette ne correspond.").assertExists()
    }

    @Test
    fun `a tap on a row opens the recipe`() {
        val opened = mutableListOf<String>()
        show { opened += it }

        compose.onNodeWithText("Curry de pois chiches").performClick()

        assertEquals(listOf("r-curry"), opened)
    }
}
