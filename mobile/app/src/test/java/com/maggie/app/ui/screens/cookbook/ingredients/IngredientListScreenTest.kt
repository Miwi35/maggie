package com.maggie.app.ui.screens.cookbook.ingredients

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.repository.IngredientRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cuisine › Ingrédients as the mock-up draws it (MAG-1057). */
@RunWith(AndroidJUnit4::class)
class IngredientListScreenTest {
    @get:Rule val compose = ScreenRule()

    private val oignon = Ingredient("1", "Oignon jaune", null, ProductCategory.MEAT, kcalPer100g = 40f, pieceWeightGrams = 100f, ciqualAlimCode = "20034")
    private val sel = Ingredient("2", "Sel", null, ProductCategory.MEAT)

    private fun show() {
        val repository = mockk<IngredientRepository>()
        coEvery { repository.getIngredients() } returns Result.success(listOf(oignon, sel))
        compose.setContent { IngredientListScreen(IngredientListViewModel(repository), onIngredientClick = {}) }
        compose.waitForIdle()
    }

    @Test
    fun `draws a card per ingredient with its initial and state`() {
        show()

        assertEquals(2, compose.onAllNodesWithTag(UiTags.INGREDIENT_ROW, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("O").assertExists()
        compose.onNodeWithText("40 kcal · 1 pièce ≈ 100 g").assertExists()
        compose.onNodeWithText("Ciqual").assertExists()
        compose.onNodeWithText("Sans valeurs").assertExists()
    }

    @Test
    fun `the Sans valeurs pill narrows the list`() {
        show()

        compose.onNodeWithTag(UiTags.subview("none")).performClick()

        compose.onNodeWithText("Oignon jaune").assertDoesNotExist()
        compose.onNodeWithText("Sel").assertExists()
    }
}
