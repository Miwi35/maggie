package com.maggie.app.ui.screens.grocery.run

import androidx.compose.ui.test.assertIsDisplayed
import com.maggie.app.data.model.Store
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.AisleSummary
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityImmersion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Courses activity's « Tournée » (MAG-762). */
@RunWith(AndroidJUnit4::class)
class GroceryRunScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private object NoImmersion : ActivityImmersion {
        override fun enter() = Unit

        override fun exit() = Unit
    }

    private val log = mutableListOf<String>()
    private var quits = 0

    private val actions = GroceryRunActions(
        check = { log += "check:$it" },
        markMissing = { log += "missing:$it" },
        setVisited = { id, visited -> log += "visit:$id:$visited" },
        start = { log += "start" },
        retry = { log += "retry" },
        dismissQuit = { log += "stay" },
        openRecap = { log += "recap" },
        closeRecap = { log += "back" },
        setPaidAmount = { log += "paid:$it" },
        finishStore = { log += "finish" },
        keepForNextTime = { log += "next:$it" },
        remove = { log += "remove:$it" },
    )

    private val stores = listOf(RunStore("s-u", "Super U", 2), RunStore("s-b", "Biocoop", 1), RunStore("s-m", "Marché", 1))

    private fun state(
        run: GroceryRunState = GroceryRunState(tour = listOf("s-u", "s-b", "s-m")),
        load: GroceryRunLoad = GroceryRunLoad.READY,
        quitAsked: Boolean = false,
    ) = GroceryRunUiState(load = load, stores = stores, run = run, quitAsked = quitAsked)

    private fun draw(state: GroceryRunUiState) = compose.setContent {
        GroceryRunContent(state = state, actions = actions, onQuit = { quits++ }, onOpenMaggie = {}, immersion = NoImmersion)
    }

    private fun assertHabitualOrder() = assertTopToBottom(
        "Super U" to compose.onNodeWithText("Super U"),
        "Biocoop" to compose.onNodeWithText("Biocoop"),
        "Marché" to compose.onNodeWithText("Marché"),
    )

    @Test
    fun `the stores are listed in the order of the tour with their count`() {
        draw(state())

        compose.onNodeWithTag(UiTags.GROCERY_RUN_TOUR).assertIsDisplayed()
        assertHabitualOrder()
        compose.onNodeWithText("2 articles").assertIsDisplayed()
        compose.onNodeWithText("Commencer par Super U").assertIsDisplayed()
    }

    @Test
    fun `the tour reads as the mockup, title, header and position of each store`() {
        draw(state())

        compose.onNodeWithText("Votre tournée").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.GROCERY_RUN_HEADER).assertTextEquals("Courses · 3 magasins")
        compose.onNodeWithTag(UiTags.groceryRunPosition("s-m")).assertTextEquals("3")
        assertTopToBottom(
            "Votre tournée" to compose.onNodeWithText("Votre tournée"),
            "Super U" to compose.onNodeWithText("Super U"),
        )
    }

    @Test
    fun `the button names the first store of the session order`() {
        draw(state(GroceryRunState(tour = listOf("s-m", "s-u", "s-b"))))

        compose.onNodeWithText("Commencer par Marché").assertIsDisplayed()
    }

    @Test
    fun `a skipped store has an empty box that brings it back`() {
        draw(state(GroceryRunState(tour = listOf("s-u"), skipped = listOf("s-b"))))

        compose.onNodeWithTag(UiTags.groceryRunVisit("s-b")).performClick()

        assertEquals(listOf("visit:s-b:true"), log)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `a tablet lists them the same way, with a 64 dp button`() {
        draw(state())

        assertHabitualOrder()
        compose.onNodeWithTag(UiTags.GROCERY_RUN_GO).assertHeightIsAtLeast(64.dp)
    }

    @Test
    fun `the session order is the one drawn`() {
        draw(state(GroceryRunState(tour = listOf("s-m", "s-u", "s-b"))))

        assertTopToBottom(
            "Marché" to compose.onNodeWithText("Marché"),
            "Super U" to compose.onNodeWithText("Super U"),
            "Biocoop" to compose.onNodeWithText("Biocoop"),
        )
    }

    @Test
    fun `a store left out comes after the tour`() {
        draw(state(GroceryRunState(tour = listOf("s-u", "s-m"), skipped = listOf("s-b"))))

        assertTopToBottom(
            "Super U" to compose.onNodeWithText("Super U"),
            "Marché" to compose.onNodeWithText("Marché"),
            "Biocoop" to compose.onNodeWithText("Biocoop"),
        )
        compose.onNodeWithContentDescription("Déplacer Super U").assertExists()
    }

    @Test
    fun `the store boxes and the start button act`() {
        draw(state())

        compose.onNodeWithTag(UiTags.groceryRunSkip("s-b")).performClick()
        compose.onNodeWithTag(UiTags.GROCERY_RUN_GO).performClick()

        assertEquals(listOf("visit:s-b:false", "start"), log)
    }

    @Test
    fun `an empty tour cannot start`() {
        draw(state(GroceryRunState(skipped = listOf("s-u", "s-b", "s-m"))))

        compose.onNodeWithTag(UiTags.GROCERY_RUN_GO).assertIsNotEnabled()
    }

    @Test
    fun `the first store of the tour is opened once it has started`() {
        draw(state(GroceryRunState(tour = listOf("s-b", "s-u"), current = "s-b", skipped = listOf("s-m"))))

        compose.onNodeWithTag(UiTags.GROCERY_RUN_CURRENT).assertIsDisplayed()
        compose.onNodeWithText("Biocoop").assertIsDisplayed()
    }

    @Test
    fun `the quit dialog asks, and Rester dismisses it`() {
        draw(state(quitAsked = true))

        compose.onNodeWithText("Quitter les courses ?").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.GROCERY_RUN_QUIT_STAY).performClick()
        compose.onNodeWithTag(UiTags.GROCERY_RUN_QUIT_CONFIRM).performClick()

        assertEquals(listOf("stay"), log.take(1))
        assertEquals(1, quits)
    }

    @Test
    fun `the error offers to try again`() {
        draw(state(load = GroceryRunLoad.ERROR))

        compose.onNodeWithTag(UiTags.GROCERY_RUN_ERROR).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.GROCERY_RUN_RETRY).performClick()

        assertEquals(listOf("retry"), log)
    }

    @Test
    fun `nothing to take shows the empty state`() {
        draw(state(load = GroceryRunLoad.EMPTY))

        compose.onNodeWithTag(UiTags.GROCERY_RUN_EMPTY).assertIsDisplayed()
    }

    private val milk = Product(id = "p-milk", name = "Lait", category = ProductCategory.DAIRY)
    private val rice = Product(
        id = "p-rice", name = "Riz", category = ProductCategory.GRAIN,
        packagingUnit = CookbookUnit.PACK, packagingSize = 500f, packagingSizeUnit = CookbookUnit.G,
    )
    private val storeItems = listOf(
        GroceryItem(id = "i-1", label = "Pain", store = stores[0].let { Store(it.id, it.name) }, position = 1),
        GroceryItem(id = "i-2", label = "Riz", store = Store("s-u", "Super U"), product = rice, quantity = 2f, aisle = AisleSummary("a-2", "Épicerie", 2), position = 2),
        GroceryItem(id = "i-3", label = "Lait", store = Store("s-u", "Super U"), product = milk, aisle = AisleSummary("a-1", "Frais", 1), position = 3),
        GroceryItem(id = "i-4", label = "Beurre", store = Store("s-u", "Super U"), aisle = AisleSummary("a-1", "Frais", 1), position = 4, checked = true),
    )

    private fun storeState(
        tags: Map<String, String> = emptyMap(),
        pending: Map<String, Boolean> = emptyMap(),
        pendingMissing: Map<String, Boolean> = emptyMap(),
    ) = GroceryRunUiState(
        load = GroceryRunLoad.READY,
        stores = stores,
        run = GroceryRunState(tour = listOf("s-u", "s-b", "s-m"), current = "s-u"),
        items = storeItems,
        pending = pending,
        pendingMissing = pendingMissing,
        mealTags = tags,
    )

    @Test
    fun `the store lists its aisles in the store's order, Sans rayon last`() {
        draw(storeState())

        assertTopToBottom(
            "Frais" to compose.onNodeWithText("Frais"),
            "Épicerie" to compose.onNodeWithText("Épicerie"),
            "Sans rayon" to compose.onNodeWithText("Sans rayon"),
        )
        assertTopToBottom(
            "Lait" to compose.onNodeWithText("Lait"),
            "Beurre" to compose.onNodeWithText("Beurre"),
        )
    }

    @Test
    fun `a line shows its quantity with its packaging, and a box of at least 64 dp`() {
        draw(storeState())

        compose.onNodeWithText("2 paquets de 500 g").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.groceryRunLine("i-2")).assertHeightIsAtLeast(64.dp)
    }

    @Test
    fun `the pour pill shows only on an article a meal uses`() {
        draw(storeState(tags = mapOf("p-milk" to "ce soir · bœuf bourguignon")))

        compose.onNodeWithText("pour : ce soir · bœuf bourguignon").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.groceryRunFor("i-2")).assertDoesNotExist()
    }

    @Test
    fun `the progress shows the store and the tour`() {
        draw(storeState())

        compose.onNodeWithTag(UiTags.GROCERY_RUN_STORE_PROGRESS).assertTextEquals("1 / 4")
        compose.onNodeWithTag(UiTags.GROCERY_RUN_TOUR_PROGRESS).assertTextEquals("Magasin 1 sur 3")
    }

    @Test
    fun `a pending tick shows the article ticked in the progress`() {
        draw(storeState(pending = mapOf("i-3" to true)))

        compose.onNodeWithTag(UiTags.GROCERY_RUN_STORE_PROGRESS).assertTextEquals("2 / 4")
    }

    @Test
    fun `tapping a line asks to tick it`() {
        draw(storeState())

        compose.onNodeWithTag(UiTags.groceryRunLine("i-3")).performClick()

        assertEquals(listOf("check:i-3"), log)
    }

    @Test
    fun `Manquant on a line asks to mark it, and a ticked line has none`() {
        draw(storeState())

        compose.onNodeWithTag(UiTags.groceryRunMissing("i-3")).performClick()

        assertEquals(listOf("missing:i-3"), log)
        compose.onNodeWithTag(UiTags.groceryRunMissing("i-4")).assertDoesNotExist()
    }

    @Test
    fun `an article moved from an earlier store shows manquant chez that store, with Annuler`() {
        val moved = GroceryItem(
            id = "i-7", label = "Crème", store = Store("s-b", "Biocoop"), missingFromStore = Store("s-u", "Super U"),
            missingAt = "2026-11-08T10:00:00+00:00", position = 1,
        )
        draw(storeState().let { it.copy(items = it.items + moved, run = it.run.copy(current = "s-b")) })

        compose.onNodeWithTag(UiTags.groceryRunMissingTag("i-7"), useUnmergedTree = true).assertTextEquals("manquant chez Super U")
        compose.onNodeWithTag(UiTags.groceryRunMissing("i-7")).assertTextEquals("Annuler").performClick()
        assertEquals(listOf("missing:i-7"), log)
    }

    @Test
    fun `a missing article without a fallback stays, marked pas de repli and counted as done`() {
        draw(storeState(pendingMissing = mapOf("i-1" to true)))

        compose.onNodeWithTag(UiTags.groceryRunMissingTag("i-1"), useUnmergedTree = true).assertTextEquals("manquant · pas de repli")
        compose.onNodeWithTag(UiTags.GROCERY_RUN_STORE_PROGRESS).assertTextEquals("2 / 4")
    }

    @Test
    fun `Terminer le magasin asks for the summary`() {
        draw(storeState())

        compose.onNodeWithTag(UiTags.GROCERY_RUN_FINISH).assertTextEquals("Terminer le magasin").performClick()

        assertEquals(listOf("recap"), log)
    }

    @Test
    fun `the summary lists what was taken, the missing articles and where they go, then Montant payé`() {
        val moved = storeItems[2].copy(store = Store("s-b", "Biocoop"), missingFromStore = Store("s-u", "Super U"), missingAt = "2026-11-08T10:00:00+00:00")
        draw(storeState(pendingMissing = mapOf("i-1" to true)).let { it.copy(recap = true, items = storeItems.map { item -> if (item.id == "i-3") moved else item }) })

        compose.onNodeWithTag(UiTags.GROCERY_RUN_RECAP).assertIsDisplayed()
        compose.onNodeWithText("Pris · 1 article").assertIsDisplayed()
        compose.onNodeWithText("Beurre").assertIsDisplayed()
        compose.onNodeWithText("Manquants · 2 articles").assertIsDisplayed()
        compose.onNodeWithText("pas de repli · à décider à la fin").assertIsDisplayed()
        compose.onNodeWithText("à prendre chez Biocoop").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.GROCERY_RUN_PAID).performTextInput("42,80")
        compose.onNodeWithTag(UiTags.GROCERY_RUN_RECAP_CONFIRM).performClick()
        compose.onNodeWithTag(UiTags.GROCERY_RUN_RECAP_BACK).performClick()
        assertEquals(listOf("paid:42,80", "finish", "back"), log)
    }

    @Test
    fun `an amount that is not in euros says so`() {
        draw(storeState().copy(recap = true, paidAmount = "quarante", amountInvalid = true))

        compose.onNodeWithText("Un montant en euros, par exemple 42,80").assertIsDisplayed()
    }

    private fun leftoverState() = storeState(pendingMissing = mapOf("i-1" to true)).let {
        it.copy(run = it.run.copy(current = null, done = listOf("s-u", "s-b", "s-m"), finished = true))
    }

    @Test
    fun `Manquants sans repli offers Prochaine fois or Supprimer for each one`() {
        draw(leftoverState())

        compose.onNodeWithTag(UiTags.GROCERY_RUN_LEFTOVERS).assertIsDisplayed()
        compose.onNodeWithText("Pain").assertIsDisplayed()
        compose.onNodeWithText("manquant chez Super U").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.groceryRunNextTime("i-1")).performClick()
        compose.onNodeWithTag(UiTags.groceryRunRemove("i-1")).performClick()
        assertEquals(listOf("next:i-1", "remove:i-1"), log)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land")
    fun `in landscape the step Manquants sans repli shows the same choices`() {
        draw(leftoverState())

        compose.onNodeWithTag(UiTags.groceryRunNextTime("i-1")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.groceryRunRemove("i-1")).assertIsDisplayed()
    }

    @Test
    fun `Fin closes the activity`() {
        draw(leftoverState().copy(ended = true, load = GroceryRunLoad.EMPTY))

        compose.onNodeWithText("Courses terminées").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.GROCERY_RUN_END_CLOSE).performClick()
        assertEquals(1, quits)
    }
}
