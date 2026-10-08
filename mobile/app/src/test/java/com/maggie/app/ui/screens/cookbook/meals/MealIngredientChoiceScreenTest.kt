package com.maggie.app.ui.screens.cookbook.meals

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.FakeMealIngredientChoice
import com.maggie.app.screentest.ScreenRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The full-screen choice of the ingredients of a meal (MAG-297): which boxes the
 * stock ticks, what each line says, and what « Ajouter aux courses » sends.
 */
@RunWith(AndroidJUnit4::class)
class MealIngredientChoiceScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun show(server: FakeMealIngredientChoice, onLater: () -> Unit = {}, onDone: () -> Unit = {}) {
        compose.setContent {
            MealIngredientChoiceScreen(viewModel = server.viewModel, onLater = onLater, onDone = onDone)
        }
        compose.waitForIdle()
    }

    @Test
    fun `only the ingredients out or low on stock are ticked`() {
        show(FakeMealIngredientChoice())

        compose.onNodeWithText("Riz").assertIsOn()
        compose.onNodeWithText("Farine").assertIsOn()
        compose.onNodeWithText("Légumes pour couscous").assertIsOff()
    }

    @Test
    fun `each line says what it buys, in packagings, and the state of its stock`() {
        show(FakeMealIngredientChoice())

        compose.onNodeWithText("1 paquet (500 g)").assertIsDisplayed()
        compose.onNodeWithText("2 paquets (500 g)").assertIsDisplayed()
        compose.onNodeWithText("1 bocal").assertIsDisplayed()
        compose.onNodeWithText("Rupture").assertIsDisplayed()
        compose.onNodeWithText("Stock faible").assertIsDisplayed()
        compose.onNodeWithText("En stock").assertIsDisplayed()
    }

    @Test
    fun `the counter follows the selection`() {
        show(FakeMealIngredientChoice())
        compose.onNodeWithText("2 articles à ajouter").assertIsDisplayed()

        compose.onNodeWithText("Légumes pour couscous").performClick()
        compose.onNodeWithText("3 articles à ajouter").assertIsDisplayed()

        compose.onNodeWithText("Riz").performClick()
        compose.onNodeWithText("Farine").performClick()
        compose.onNodeWithText("1 article à ajouter").assertIsDisplayed()
    }

    @Test
    fun `select all ticks everything`() {
        show(FakeMealIngredientChoice())

        compose.onNodeWithText("Tout cocher").performClick()

        compose.onNodeWithText("Légumes pour couscous").assertIsOn()
        compose.onNodeWithText("3 articles à ajouter").assertIsDisplayed()
    }

    @Test
    fun `the button is off while nothing is ticked`() {
        show(FakeMealIngredientChoice())
        compose.onNodeWithText("Ajouter aux courses").assertIsEnabled()

        compose.onNodeWithText("Riz").performClick()
        compose.onNodeWithText("Farine").performClick()

        compose.onNodeWithText("0 article à ajouter").assertIsDisplayed()
        compose.onNodeWithText("Ajouter aux courses").assertIsNotEnabled()
    }

    @Test
    fun `adding sends only the ticked ingredients, then the screen is done`() {
        val server = FakeMealIngredientChoice()
        var done = false
        show(server, onDone = { done = true })

        compose.onNodeWithText("Riz").performClick()
        compose.onNodeWithText("Légumes pour couscous").performClick()
        compose.onNodeWithText("Ajouter aux courses").performClick()
        compose.waitForIdle()

        assertEquals(listOf(listOf("flour", "vegetables")), server.sent)
        assertTrue(done)
    }

    @Test
    fun `later leaves without adding anything, from the button and from the arrow`() {
        val server = FakeMealIngredientChoice()
        var left = 0
        show(server, onLater = { left++ })

        compose.onNodeWithText("Plus tard").performClick()
        compose.onNodeWithContentDescription("Retour").performClick()

        assertEquals(2, left)
        assertTrue(server.sent.isEmpty())
    }

    @Test
    fun `a refusal shows a message and keeps the ticks`() {
        val server = FakeMealIngredientChoice(refuse = true)
        var done = false
        show(server, onDone = { done = true })
        compose.onNodeWithText("Légumes pour couscous").performClick()

        compose.onNodeWithText("Ajouter aux courses").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(ADD_ERROR).assertIsDisplayed()
        compose.onNodeWithText("Riz").assertIsOn()
        compose.onNodeWithText("Légumes pour couscous").assertIsOn()
        compose.onNodeWithText("3 articles à ajouter").assertIsDisplayed()
        compose.onNodeWithText("Ajouter aux courses").assertIsEnabled()
        assertTrue("the screen stays open", !done)
    }
}
