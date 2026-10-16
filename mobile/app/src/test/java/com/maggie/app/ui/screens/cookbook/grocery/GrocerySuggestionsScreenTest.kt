package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.PurchaseSuggestion
import com.maggie.app.data.model.SuggestedProduct
import com.maggie.app.screentest.FakeGrocery
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** « Souvent pris » above the grocery list (MAG-495): one gesture to add, one to set aside. */
@RunWith(AndroidJUnit4::class)
class GrocerySuggestionsScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val coffee = PurchaseSuggestion(
        product = SuggestedProduct(id = "p-coffee", name = "Café"),
        everyDays = 14,
        lastBoughtOn = "2026-10-01",
        dueOn = "2026-10-15",
        usualQuantity = 2.0,
        usualUnit = "pack",
    )
    private val noItems = GroceryList(id = "list-1")

    private fun show(fake: FakeGrocery) {
        compose.setContent { GroceryScreen(viewModel = fake.viewModel, recurringViewModel = fake.recurring.viewModel) }
    }

    @Test
    fun `a product due again is offered with its rhythm, even on an empty list`() {
        show(FakeGrocery(list = noItems, suggestions = listOf(coffee)))

        compose.onNodeWithTag(UiTags.GROCERY_SUGGESTIONS).assertIsDisplayed()
        compose.onNodeWithText("Café · toutes les 2 sem.").assertIsDisplayed()
    }

    @Test
    fun `no suggestion, no banner`() {
        show(FakeGrocery(list = noItems))

        compose.onNodeWithText("Aucun article dans la liste").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.GROCERY_SUGGESTIONS).assertDoesNotExist()
    }

    @Test
    fun `one tap adds the product to the list and the banner goes`() {
        val fake = FakeGrocery(list = noItems, suggestions = listOf(coffee))
        show(fake)

        compose.onNodeWithContentDescription("Ajouter Café").performClick()
        compose.waitForIdle()

        assertEquals(listOf("Café" to 2f), fake.items.map { it.label to it.quantity })
        compose.onNodeWithTag(UiTags.GROCERY_SUGGESTIONS).assertDoesNotExist()
        compose.onNodeWithText("Café", substring = true).assertIsDisplayed()
    }

    @Test
    fun `pas besoin sets the product aside without adding it`() {
        val fake = FakeGrocery(list = noItems, suggestions = listOf(coffee))
        show(fake)

        compose.onNodeWithContentDescription("Pas besoin de Café").performClick()
        compose.waitForIdle()

        assertEquals(listOf("p-coffee"), fake.snoozed)
        assertEquals(emptyList<String>(), fake.items.map { it.label })
        compose.onNodeWithTag(UiTags.GROCERY_SUGGESTIONS).assertDoesNotExist()
    }

    @Test
    fun `the banner folds away and back`() {
        show(FakeGrocery(list = noItems, suggestions = listOf(coffee)))

        compose.onNodeWithTag(UiTags.GROCERY_SUGGESTIONS_TOGGLE).performClick()
        compose.onNodeWithText("Café · toutes les 2 sem.").assertDoesNotExist()

        compose.onNodeWithTag(UiTags.GROCERY_SUGGESTIONS_TOGGLE).performClick()
        compose.onNodeWithText("Café · toutes les 2 sem.").assertIsDisplayed()
    }
}
