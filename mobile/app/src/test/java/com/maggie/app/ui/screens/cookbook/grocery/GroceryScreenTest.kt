package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.screentest.FakeGrocery
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.Seed
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.screentest.tap
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The errand on the phone, without a phone (MAG-242).
 *
 * What `07-grocery-errand` and `09-grocery-deferred` asserted on an emulator: the
 * list read by shop in visiting order, a tick, « Terminé » on a shop, what is
 * left offered back, a removal behind a confirmation, and a line deferred to a
 * later day missing from today's list. None of it needs a real Android — it is
 * what the screen makes of what the server sent — so it runs here in seconds.
 * `agent-os/standards/mobile/screen-tests.md` says where the line is.
 *
 * What stays on the emulator is `08-grocery-realtime`: a change made elsewhere
 * reaching this screen over Mercure, with the app never relaunched.
 *
 * Each step is still read twice, on the screen and in the list the fake server
 * keeps ([FakeGrocery.items]) — a screen that updates optimistically proves
 * nothing about the request it made, which is why the journey ran a script beside
 * itself.
 */
@RunWith(AndroidJUnit4::class)
class GroceryScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `the list is grouped by shop in visiting order, unassigned last`() {
        val fake = FakeGrocery()
        compose.setContent { GroceryScreen(viewModel = fake.viewModel) }

        assertTopToBottom(
            "Halles du voisin" to compose.onNodeWithText("Halles du voisin"),
            "Poireau du voisin" to compose.onNodeWithText("Poireau du voisin", substring = true),
            "Câpres" to compose.onNodeWithText("Câpres", substring = true),
            "Épicerie du coin" to compose.onNodeWithText("Épicerie du coin"),
            "Timbres du voisin" to compose.onNodeWithText("Timbres du voisin", substring = true),
            "Non assigné" to compose.onNodeWithText("Non assigné"),
            "Sacs du voisin" to compose.onNodeWithText("Sacs du voisin", substring = true),
        )
    }

    /**
     * `09-grocery-deferred`, both halves: the line is in what the server sent and
     * not on the screen. The absence alone would also pass on a screen that failed
     * to load, so a due line of the same shop is required first.
     */
    @Test
    fun `a line to buy after a later day is served but not drawn`() {
        val fake = FakeGrocery()
        compose.setContent { GroceryScreen(viewModel = fake.viewModel) }

        assertTrue(
            "the fixture no longer defers anything",
            fake.items.any { it.label == "Liquide vaisselle" },
        )
        compose.onNodeWithText("Timbres du voisin", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Liquide vaisselle", substring = true).assertDoesNotExist()
    }

    /**
     * A long press selects the line — a plain tap opens its detail sheet — and the
     * bar that appears has the tick. « Terminé » is only offered on a shop that has
     * a ticked line, so it showing is the screen's own proof that the tick
     * registered, with no marker to read.
     */
    @Test
    fun `ticking a line offers to end the errand at its shop`() {
        val fake = FakeGrocery()
        compose.setContent { GroceryScreen(viewModel = fake.viewModel) }

        compose.onNodeWithText("Câpres", substring = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Cocher les articles").performClick()

        compose.onNodeWithText("Terminé").assertIsDisplayed()
        assertEquals(
            listOf(true),
            fake.items.filter { it.label == "Câpres" }.map { it.checked },
        )
    }

    /**
     * The end of the errand at one shop: what was ticked is carried home, what was
     * not is offered back with the choice of moving it or keeping it. « Garder »
     * once per line; the last one closes the sheet.
     */
    @Test
    fun `ending the errand carries the bought lines home and offers the rest back`() {
        val fake = FakeGrocery()
        compose.setContent { GroceryScreen(viewModel = fake.viewModel) }

        compose.onNodeWithText("Câpres", substring = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Cocher les articles").performClick()
        compose.onNodeWithText("Terminé").performClick()

        // Both unbought lines of the shop are offered — the seeded leek and the pickles.
        compose.onNodeWithText("Articles non achetés à Halles du voisin").assertIsDisplayed()
        compose.onAllNodesWithText("Transférer").assertCountEquals(2)
        compose.onAllNodesWithText("Garder").assertCountEquals(2)

        // « Garder » once per line. `tap()` and not `performClick()`: the sheet is a
        // window of its own and takes no injected touch.
        compose.onAllNodesWithText("Garder")[0].tap()
        compose.onAllNodesWithText("Garder")[0].tap()
        compose.onNodeWithText("Articles non achetés à Halles du voisin").assertDoesNotExist()

        // The bought line is gone from the screen and from the list the server holds;
        // the unbought ones are still there, unticked.
        compose.onNodeWithText("Câpres", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Cornichons", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Poireau du voisin", substring = true).assertIsDisplayed()
        assertEquals(
            emptyList<String>(),
            fake.items.filter { it.label == "Câpres" }.map { it.label },
        )
        assertEquals(
            listOf(false, false),
            fake.items.filter { it.label in setOf("Cornichons", "Poireau du voisin") }.map { it.checked },
        )
    }

    /** The app has no « Retirer » button: a line leaves by being selected and deleted, behind a confirmation. */
    @Test
    fun `removing a selected line asks for a confirmation first`() {
        val fake = FakeGrocery()
        compose.setContent { GroceryScreen(viewModel = fake.viewModel) }

        compose.onNodeWithText("Cornichons", substring = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Supprimer les articles").performClick()

        compose.onNodeWithText("Cette action est irréversible.").assertIsDisplayed()
        // Still on the list, and still on the server, until the confirmation.
        compose.onNodeWithText("Cornichons", substring = true).assertIsDisplayed()
        assertTrue(fake.items.any { it.label == "Cornichons" })

        compose.onNodeWithText("Supprimer").performClick()

        compose.onNodeWithText("Cornichons", substring = true).assertDoesNotExist()
        assertTrue(fake.items.none { it.label == "Cornichons" })
        // The seed's leek stays.
        compose.onNodeWithText("Poireau du voisin", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an empty list says so instead of drawing nothing`() {
        val fake = FakeGrocery(list = Seed.groceryList.copy(items = emptyList()))
        compose.setContent { GroceryScreen(viewModel = fake.viewModel) }

        compose.onNodeWithText("Aucun article dans la liste").assertIsDisplayed()
    }

    /**
     * A load that failed leaves the same screen as an empty list, which is the
     * known weakness of this screen and not something this test invents: it is
     * written down so that a screen that starts telling them apart fails here and
     * gets its message asserted.
     */
    @Test
    fun `a failed load leaves the screen readable`() {
        val fake = FakeGrocery(loadFailure = IllegalStateException("502"))
        compose.setContent { GroceryScreen(viewModel = fake.viewModel) }

        compose.onNodeWithText("Aucun article dans la liste").assertIsDisplayed()
        compose.onNodeWithText("Liste de courses").assertIsDisplayed()
    }

    // --- MAG-291: − quantity + on the line ---

    private val riceList = GroceryList(
        id = "list-rice",
        items = listOf(
            GroceryItem(id = "item-riz", label = "Riz", customLabel = "Riz", quantity = 1f, unit = CookbookUnit.PACK, position = 0),
            GroceryItem(id = "item-sel", label = "Sel", customLabel = "Sel", position = 1),
        ),
    )

    // The save waits for the taps to stop; the main looper is paused here, so the delay is lived through explicitly.
    private fun afterSaveDelay() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(QUANTITY_SAVE_DELAY_MS + 100))
        compose.waitForIdle()
    }

    private fun quantityOfRice(fake: FakeGrocery) = fake.items.first { it.id == "item-riz" }.quantity

    /** The journey of the ticket: Riz at 1 paquet, two taps on +, 3 paquets — on the screen and on the server. */
    @Test
    fun `two taps on plus show 3 paquets and save 3`() {
        val fake = FakeGrocery(list = riceList)
        compose.setContent { GroceryScreen(viewModel = fake.viewModel) }

        compose.onNodeWithText("1 paquet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Augmenter la quantité de Riz").performClick()
        compose.onNodeWithContentDescription("Augmenter la quantité de Riz").performClick()

        compose.onNodeWithText("3 paquets").assertIsDisplayed()
        afterSaveDelay()
        assertEquals(3f, quantityOfRice(fake))
    }

    @Test
    fun `minus is disabled at the minimum and the line stays`() {
        val fake = FakeGrocery(list = riceList)
        compose.setContent { GroceryScreen(viewModel = fake.viewModel) }

        compose.onNodeWithContentDescription("Diminuer la quantité de Riz").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Diminuer la quantité de Riz").performClick()

        compose.onNodeWithText("1 paquet").assertIsDisplayed()
        assertEquals(1f, quantityOfRice(fake))
        assertEquals(2, fake.items.size)
    }

    @Test
    fun `a quantity typed on the line is saved`() {
        val fake = FakeGrocery(list = riceList)
        compose.setContent { GroceryScreen(viewModel = fake.viewModel) }

        compose.onNodeWithContentDescription("Modifier la quantité de Riz").performClick()
        compose.onNodeWithContentDescription("Quantité de Riz").performTextReplacement("7")
        compose.onNodeWithContentDescription("Quantité de Riz").performImeAction()

        compose.onNodeWithText("7 paquets").assertIsDisplayed()
        afterSaveDelay()
        assertEquals(7f, quantityOfRice(fake))
    }

    @Test
    fun `an invalid typed quantity is refused with a message and nothing changes`() {
        val fake = FakeGrocery(list = riceList)
        compose.setContent { GroceryScreen(viewModel = fake.viewModel) }

        compose.onNodeWithContentDescription("Modifier la quantité de Riz").performClick()
        compose.onNodeWithContentDescription("Quantité de Riz").performTextReplacement("0")
        compose.onNodeWithContentDescription("Quantité de Riz").performImeAction()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Quantité invalide", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("1 paquet").assertIsDisplayed()
        assertEquals(1f, quantityOfRice(fake))
    }
}
