package com.maggie.app.ui.screens.cookbook.recipes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.StepIngredient
import com.maggie.app.data.model.Utensil
import com.maggie.app.screentest.ScreenRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The recipe sheet drawn after its mock-up, `cuisine/Recette-Tel` (MAG-630). */
@RunWith(AndroidJUnit4::class)
class RecipeSheetTest {
    @get:Rule val compose = ScreenRule()

    private fun sheet(recipe: com.maggie.app.data.model.Recipe) {
        compose.setContent {
            RecipeSheetContent(
                state = RecipeDetailUiState(recipe = recipe, isLoading = false),
                recipe = recipe,
                modifier = Modifier.fillMaxSize(),
                onCook = {},
                onStepGuests = {},
                onResetGuests = {},
            )
        }
    }

    @Test
    fun `the body has no Modifier button, editing is in the menu`() {
        sheet(pastaDinner())

        compose.onNodeWithText("Cuisiner").assertIsDisplayed()
        compose.onNodeWithText("Modifier").assertDoesNotExist()
    }

    @Test
    fun `the menu offers Modifier and Supprimer`() {
        compose.setContent { RecipeActionsMenu(expanded = true, onDismiss = {}, onEdit = {}, onDelete = {}) }

        compose.onNodeWithText("Modifier").assertIsDisplayed()
        compose.onNodeWithText("Supprimer").assertIsDisplayed()
    }

    @Test
    fun `the tags come before the line of time and energy`() {
        sheet(pastaDinner().copy(tags = listOf("végétarien")))

        val tags = compose.onNodeWithText("végétarien").getBoundsInRoot().top
        val summary = compose.onNodeWithText("⏱", substring = true).getBoundsInRoot().top

        assertTrue("tags at $tags must sit above the summary at $summary", tags < summary)
    }

    @Test
    fun `a step shows its text, time and waits, without chips for its ingredients and utensils`() {
        val recipe = pastaDinner().copy(
            steps = listOf(
                step(
                    1,
                    "Cuire les pâtes",
                    12,
                    ingredients = listOf(StepIngredient("i-pasta", "Pâtes de blé")),
                    utensils = listOf(Utensil("u-pan", "Sauteuse")),
                ),
            ),
            utensils = emptyList(),
        )
        sheet(recipe)

        compose.onNodeWithText("Cuire les pâtes").assertIsDisplayed()
        compose.onNodeWithText("Pâtes de blé").assertDoesNotExist()
        compose.onNodeWithText("Sauteuse").assertDoesNotExist()
    }
}
