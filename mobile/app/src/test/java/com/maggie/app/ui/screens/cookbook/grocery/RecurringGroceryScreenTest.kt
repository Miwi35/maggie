package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.RecurringFrequency
import com.maggie.app.data.model.RecurringGroceryItem
import com.maggie.app.screentest.FakeGrocery
import com.maggie.app.screentest.FakeRecurring
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.tap
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The recurring items on the grocery screen: the sheet behind « + Produit récurrent », the line's indicator and the detail's setting (MAG-369, MAG-627). */
@RunWith(AndroidJUnit4::class)
class RecurringGroceryScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val milk = Product(id = "p-milk", name = "Lait", category = ProductCategory.DAIRY)
    private val rice = Product(id = "p-rice", name = "Riz", category = ProductCategory.GRAIN)

    private val milkRecurring = RecurringGroceryItem(
        id = "rec-milk",
        product = "/api/products/p-milk",
        label = "Lait",
        frequency = RecurringFrequency.WEEKLY,
        nextDueAt = "2026-10-20",
    )

    private val list = GroceryList(
        id = "list-1",
        items = listOf(
            GroceryItem(id = "item-milk", label = "Lait", product = milk, position = 0),
            GroceryItem(id = "item-bags", label = "Sacs", customLabel = "Sacs", position = 1),
        ),
    )

    // Without « Lait »: the sheet opens over the list, and the row of the sheet must be the only « Lait » on screen.
    private val listWithoutMilk = GroceryList(id = "list-2", items = list.items.filter { it.id != "item-milk" })

    private fun show(fake: FakeGrocery) {
        compose.setContent { GroceryScreen(viewModel = fake.viewModel, recurringViewModel = fake.recurring.viewModel) }
    }

    private fun openRecurringSheet() {
        compose.onNodeWithTag(UiTags.GROCERY_RECURRING_OPEN).performClick()
    }

    @Test
    fun `the screen opens on the list`() {
        show(FakeGrocery(list = list, recurring = FakeRecurring(listOf(milkRecurring), listOf(milk))))

        compose.onNodeWithTag(UiTags.GROCERY_RECURRING_OPEN).assertIsDisplayed()
        compose.onNodeWithText("Sacs", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Prochain ajout", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the sheet lists the label, the frequency and the next due date`() {
        show(FakeGrocery(list = listWithoutMilk, recurring = FakeRecurring(listOf(milkRecurring), listOf(milk))))

        openRecurringSheet()

        compose.onNodeWithText("Produits récurrents").assertIsDisplayed()
        compose.onNodeWithText("Lait").assertIsDisplayed()
        compose.onNodeWithText("Toutes les semaines").assertIsDisplayed()
        compose.onNodeWithText("Prochain ajout : 20/10/2026").assertIsDisplayed()
    }

    @Test
    fun `the sheet says so when nothing is recurring`() {
        show(FakeGrocery(list = listWithoutMilk))

        openRecurringSheet()

        compose.onNodeWithText("Aucun article récurrent").assertIsDisplayed()
    }

    @Test
    fun `removing from the sheet deletes the item`() {
        val recurring = FakeRecurring(listOf(milkRecurring), listOf(milk))
        show(FakeGrocery(list = listWithoutMilk, recurring = recurring))
        openRecurringSheet()

        compose.onNodeWithContentDescription("Retirer Lait").tap()

        compose.onNodeWithText("Aucun article récurrent").assertIsDisplayed()
        assertTrue(recurring.items.isEmpty())
    }

    @Test
    fun `adding a product asks for a frequency, weekly by default`() {
        val recurring = FakeRecurring(products = listOf(rice))
        show(FakeGrocery(list = listWithoutMilk, products = listOf(rice), recurring = recurring))
        openRecurringSheet()

        compose.onNodeWithTag(UiTags.RECURRING_ADD).tap()
        // The search offers nothing before two letters.
        compose.onNodeWithTag(UiTags.PRODUCT_SEARCH).performTextInput("Ri")
        compose.onNodeWithText("Riz").performClick()
        compose.onNodeWithTag(UiTags.recurringOption("weekly")).assertIsSelected()
        compose.onNodeWithText("Ajouter").performClick()

        compose.onNodeWithText("Prochain ajout : 20/10/2026").assertIsDisplayed()
        assertEquals(listOf("Riz" to RecurringFrequency.WEEKLY), recurring.items.map { it.label to it.frequency })
    }

    @Test
    fun `changing the frequency of a row patches the item`() {
        val recurring = FakeRecurring(listOf(milkRecurring), listOf(milk))
        show(FakeGrocery(list = listWithoutMilk, recurring = recurring))
        openRecurringSheet()

        compose.onNodeWithText("Lait").tap()
        compose.onNodeWithTag(UiTags.recurringOption("monthly")).performClick()
        compose.onNodeWithText("Enregistrer").performClick()

        compose.onNodeWithText("Tous les mois").assertIsDisplayed()
        assertEquals(RecurringFrequency.MONTHLY, recurring.items.single().frequency)
    }

    @Test
    fun `a line whose product is recurring shows the frequency under it`() {
        show(FakeGrocery(list = list, recurring = FakeRecurring(listOf(milkRecurring), listOf(milk))))

        compose.onNodeWithTag(UiTags.RECURRING_INDICATOR).assertIsDisplayed()
        compose.onNodeWithText("Toutes les semaines").assertIsDisplayed()
    }

    @Test
    fun `a line without a recurring item shows no indicator`() {
        show(FakeGrocery(list = list))

        compose.onNodeWithTag(UiTags.RECURRING_INDICATOR).assertDoesNotExist()
    }

    @Test
    fun `the detail of a line with a product sets, then removes, the recurrence`() {
        val recurring = FakeRecurring(products = listOf(milk))
        show(FakeGrocery(list = list, recurring = recurring))

        compose.onNodeWithText("Lait").performClick()
        compose.onNodeWithTag(UiTags.recurringOption("none")).assertIsSelected()
        // Inside a ModalBottomSheet the touch is swallowed here, see ScreenInteractions.tap.
        compose.onNodeWithTag(UiTags.recurringOption("biweekly")).tap()

        compose.waitForIdle()
        assertEquals(RecurringFrequency.BIWEEKLY, recurring.items.single().frequency)
        compose.onNodeWithTag(UiTags.recurringOption("biweekly")).assertIsSelected()

        compose.onNodeWithTag(UiTags.recurringOption("none")).tap()

        compose.waitForIdle()
        assertTrue(recurring.items.isEmpty())
        compose.onNodeWithTag(UiTags.recurringOption("none")).assertIsSelected()
    }

    @Test
    fun `the detail of a line without a product has no recurrence setting`() {
        show(FakeGrocery(list = list))

        compose.onNodeWithText("Sacs").performClick()

        compose.onNodeWithText("Modifier l'article").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.recurringOption("none")).assertDoesNotExist()
    }
}
