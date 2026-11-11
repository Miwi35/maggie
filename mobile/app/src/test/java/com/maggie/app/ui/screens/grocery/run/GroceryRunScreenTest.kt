package com.maggie.app.ui.screens.grocery.run

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.layout.positionInRoot
import com.maggie.app.data.model.Store
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.AisleSummary
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
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
        askMissing = { log += "ask:$it" },
        confirmMissing = { log += "confirm" },
        dismissMissing = { log += "dismiss" },
        setVisited = { id, visited -> log += "visit:$id:$visited" },
        start = { log += "start" },
        retry = { log += "retry" },
        dismissQuit = { log += "stay" },
        openRecap = { log += "recap" },
        closeRecap = { log += "back" },
        setPaidAmount = { log += "paid:$it" },
        finishStore = { log += "finish" },
        choose = { id, choice -> log += "choose:$id:$choice" },
        endRun = { log += "end" },
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
        compose.onNodeWithTag(UiTags.GROCERY_RUN_HEADER).assertTextEquals("Courses")
        compose.onNodeWithTag(UiTags.GROCERY_RUN_HEADER_COUNT).assertTextEquals("4 articles")
        compose.onNodeWithTag(UiTags.GROCERY_RUN_REORDERED).assertDoesNotExist()
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
    fun `a store left out stays at its place, without position or handle`() {
        draw(state(GroceryRunState(tour = listOf("s-u", "s-m"), skipped = listOf("s-b"))))

        assertHabitualOrder()
        compose.onNodeWithContentDescription("Déplacer Super U").assertExists()
        compose.onNodeWithContentDescription("Déplacer Biocoop").assertDoesNotExist()
        compose.onNodeWithTag(UiTags.groceryRunPosition("s-b")).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.groceryRunPosition("s-m")).assertTextEquals("2")
    }

    @Test
    fun `the ordre du jour pill shows once the session order is not the habitual one`() {
        draw(state(GroceryRunState(tour = listOf("s-m", "s-u", "s-b"))))

        compose.onNodeWithTag(UiTags.GROCERY_RUN_REORDERED).assertTextEquals("ordre du jour")
    }

    @Test
    fun `the cross comes first in the header, before the title`() {
        draw(state())

        val x = listOf(UiTags.ACTIVITY_QUIT, UiTags.GROCERY_RUN_HEADER, UiTags.ACTIVITY_MAGGIE)
            .map { compose.onNodeWithTag(it).fetchSemanticsNode().positionInRoot.x }
        assertEquals(x.sorted(), x)
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
        compose.onNodeWithText("Ce qui est coché est gardé.").assertIsDisplayed()
        compose.onNodeWithText("Continuer").assertIsDisplayed()
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
            "FRAIS" to compose.onNodeWithText("FRAIS"),
            "ÉPICERIE" to compose.onNodeWithText("ÉPICERIE"),
            "SANS RAYON" to compose.onNodeWithText("SANS RAYON"),
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

        compose.onNodeWithTag(UiTags.GROCERY_RUN_STORE_PROGRESS).assertTextEquals("1 / 4 ici")
        compose.onNodeWithText("Magasin 1 sur 3").assertDoesNotExist()
    }

    @Test
    fun `the header names the store with its place in the tour and counts the tour's articles, no bar under the title`() {
        draw(storeState())

        compose.onNodeWithTag(UiTags.GROCERY_RUN_HEADER).assertTextEquals("Super U · 1 / 3")
        compose.onNodeWithTag(UiTags.GROCERY_RUN_HEADER_COUNT).assertTextEquals("1 / 4")
        compose.onNodeWithText("Courses").assertDoesNotExist()
    }

    @Test
    fun `a pending tick shows the article ticked in the progress`() {
        draw(storeState(pending = mapOf("i-3" to true)))

        compose.onNodeWithTag(UiTags.GROCERY_RUN_STORE_PROGRESS).assertTextEquals("2 / 4 ici")
    }

    @Test
    fun `tapping a line asks to tick it`() {
        draw(storeState())

        compose.onNodeWithTag(UiTags.groceryRunLine("i-3")).performClick()

        assertEquals(listOf("check:i-3"), log)
    }

    @Test
    fun `Manquant on a line opens its sheet, and a ticked line has none`() {
        draw(storeState())

        compose.onNodeWithTag(UiTags.groceryRunMissing("i-3")).performClick()

        assertEquals(listOf("ask:i-3"), log)
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

    // Recette refusée (10 nov.): the mockup's sheet « <article> manquant », to confirm or cancel.
    @Test
    fun `the Manquant sheet reads as the mockup, Le prendre à the fallback, its place in the tour, and Annuler`() {
        draw(storeState().copy(missingAsked = "i-3", items = storeItems.map { if (it.id == "i-3") it.copy(product = milk.copy(fallbackStore = "/api/stores/s-b")) else it }, mealTags = mapOf("p-milk" to "ce soir · bœuf bourguignon")))

        compose.onNodeWithTag(UiTags.GROCERY_RUN_MISSING_SHEET).assertIsDisplayed()
        compose.onNodeWithText("Lait manquant").assertIsDisplayed()
        // The line's pill, and the sheet's line under its title.
        compose.onAllNodesWithText("pour : ce soir · bœuf bourguignon").assertCountEquals(2)
        compose.onNodeWithTag(UiTags.GROCERY_RUN_MISSING_CONFIRM).assertTextEquals("Le prendre à Biocoop", "magasin à défaut · 2ᵉ de la tournée")
            // The sheet's window takes no touch under Robolectric: its click actions are run instead (the journey taps them).
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag(UiTags.GROCERY_RUN_MISSING_DISMISS).assertTextEquals("Annuler").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("confirm", "dismiss"), log)
    }

    @Test
    fun `without a fallback in the tour the sheet offers to mark it missing`() {
        draw(storeState().copy(missingAsked = "i-1"))

        compose.onNodeWithText("Pain manquant").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.GROCERY_RUN_MISSING_CONFIRM).assertTextEquals("Le marquer manquant", "pas de magasin à défaut dans la tournée")
    }

    @Test
    fun `a missing line has the mockup's orange box with a dash, and Annuler marks it back at once`() {
        draw(storeState(pendingMissing = mapOf("i-3" to true)).let { it.copy(items = storeItems.map { item -> if (item.id == "i-3") item.copy(product = milk.copy(fallbackStore = "/api/stores/s-b")) else item }) })

        compose.onNodeWithTag(UiTags.groceryRunMissingMark("i-3"), useUnmergedTree = true).assertTextEquals("–")
        compose.onNodeWithTag(UiTags.groceryRunMissingTag("i-3"), useUnmergedTree = true).assertTextEquals("manquant · ira à Biocoop")
        compose.onNodeWithTag(UiTags.groceryRunMissingMark("i-2"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.groceryRunMissing("i-3")).assertTextEquals("Annuler").performClick()
        assertEquals(listOf("missing:i-3"), log)
    }

    @Test
    fun `a missing article without a fallback stays, marked pas de repli and counted as done`() {
        draw(storeState(pendingMissing = mapOf("i-1" to true)))

        compose.onNodeWithTag(UiTags.groceryRunMissingTag("i-1"), useUnmergedTree = true).assertTextEquals("manquant · pas de repli")
        compose.onNodeWithTag(UiTags.GROCERY_RUN_STORE_PROGRESS).assertTextEquals("2 / 4 ici")
    }

    @Test
    fun `Terminer le magasin asks for the summary`() {
        draw(storeState())

        compose.onNodeWithTag(UiTags.GROCERY_RUN_FINISH).assertTextEquals("Terminer Super U").performClick()

        assertEquals(listOf("recap"), log)
    }

    @Test
    fun `the summary reads as the mockup, the counts, where the missing articles go, Montant payé and the next store`() {
        val moved = storeItems[2].copy(store = Store("s-b", "Biocoop"), missingFromStore = Store("s-u", "Super U"), missingAt = "2026-11-08T10:00:00+00:00")
        draw(storeState(pendingMissing = mapOf("i-1" to true)).let { it.copy(recap = true, items = storeItems.map { item -> if (item.id == "i-3") moved else item }) })

        compose.onNodeWithText("Super U, c'est fait").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.GROCERY_RUN_RECAP_TAKEN).assertTextEquals("1", "pris")
        compose.onNodeWithTag(UiTags.GROCERY_RUN_RECAP_MISSING).assertTextEquals("2", "manquants")
        compose.onNodeWithTag(UiTags.groceryRunRecapGoes("i-3"), useUnmergedTree = true).assertTextEquals("→ Biocoop")
        compose.onNodeWithTag(UiTags.groceryRunRecapGoes("i-1"), useUnmergedTree = true).assertTextEquals("pas de repli")
        compose.onNodeWithText("MONTANT PAYÉ").assertIsDisplayed()
        compose.onNodeWithText("Retour").assertDoesNotExist()
        compose.onNodeWithText("Valider").assertDoesNotExist()
        assertTopToBottom(
            "Super U, c'est fait" to compose.onNodeWithText("Super U, c'est fait"),
            "pris" to compose.onNodeWithTag(UiTags.GROCERY_RUN_RECAP_TAKEN),
            "Lait" to compose.onNodeWithText("Lait"),
            "Montant payé" to compose.onNodeWithTag(UiTags.GROCERY_RUN_PAID),
        )

        compose.onNodeWithTag(UiTags.GROCERY_RUN_PAID).performTextInput("42,80")
        compose.onNodeWithTag(UiTags.GROCERY_RUN_RECAP_CONFIRM).assertTextEquals("Magasin suivant · Biocoop").performClick()
        assertEquals(listOf("paid:42,80", "finish"), log)
    }

    // Recette refusée (10 nov.): the mockup's « Magasin suivant · Biocoop › » carries a chevron after its text.
    @Test
    fun `Magasin suivant carries the mockup's chevron, Finir les courses has none`() {
        draw(storeState().copy(recap = true))
        compose.onNodeWithTag(UiTags.GROCERY_RUN_RECAP_CHEVRON, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the last store's summary ends with Finir les courses`() {
        draw(storeState().let { it.copy(recap = true, run = it.run.copy(done = listOf("s-b", "s-m"))) })

        compose.onNodeWithTag(UiTags.GROCERY_RUN_RECAP_CONFIRM).assertTextEquals("Finir les courses")
        compose.onNodeWithTag(UiTags.GROCERY_RUN_RECAP_CHEVRON, useUnmergedTree = true).assertDoesNotExist()
    }

    // Recette refusée (10 nov.): « Terminer » keeps « Super U · 1 / 3 » and the tour's count in the header.
    @Test
    fun `the summary keeps the store's header, its segment filled`() {
        draw(storeState().copy(recap = true))

        compose.onNodeWithTag(UiTags.GROCERY_RUN_HEADER).assertTextEquals("Super U · 1 / 3")
        compose.onNodeWithTag(UiTags.GROCERY_RUN_HEADER_COUNT).assertTextEquals("1 / 4")
        assertEquals(listOf(true, false, false), storeState().copy(recap = true).progressSegments())
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
    fun `Pas trouvés reads as the mockup, with no sentence, and offers Prochaine fois or Supprimer for each one`() {
        draw(leftoverState())

        compose.onNodeWithTag(UiTags.GROCERY_RUN_LEFTOVERS).assertIsDisplayed()
        compose.onNodeWithText("Pas trouvés").assertIsDisplayed()
        compose.onNodeWithText("aucun autre magasin de la tournée").assertIsDisplayed()
        compose.onNodeWithText("Manquants sans repli").assertDoesNotExist()
        compose.onNodeWithText("On les garde", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Pain").assertIsDisplayed()
        compose.onNodeWithText("manquant chez Super U").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.groceryRunNextTime("i-1")).performClick()
        compose.onNodeWithTag(UiTags.groceryRunRemove("i-1")).performClick()
        assertEquals(listOf("choose:i-1:NEXT_TIME", "choose:i-1:REMOVE"), log)
    }

    // Recette refusée (10 nov.): « Courses » and « N magasins », every segment filled, as the mockup draws « Sans repli ».
    @Test
    fun `Pas trouvés heads with Courses and the tour's stores, every segment filled`() {
        draw(leftoverState())

        compose.onNodeWithTag(UiTags.GROCERY_RUN_HEADER).assertTextEquals("Courses")
        compose.onNodeWithTag(UiTags.GROCERY_RUN_HEADER_COUNT).assertTextEquals("3 magasins")
        assertEquals(listOf(true, true, true), leftoverState().progressSegments())
    }

    @Test
    fun `Finir les courses waits for an answer to each one`() {
        draw(leftoverState())
        compose.onNodeWithTag(UiTags.GROCERY_RUN_END_RUN).assertTextEquals("Finir les courses").assertIsNotEnabled()
    }

    @Test
    fun `the answer chosen is selected, and Finir les courses ends the tour`() {
        draw(leftoverState().copy(choices = mapOf("i-1" to LeftoverChoice.NEXT_TIME)))

        compose.onNodeWithTag(UiTags.groceryRunNextTime("i-1")).assertIsSelected()
        compose.onNodeWithTag(UiTags.groceryRunRemove("i-1")).assertIsNotSelected()
        compose.onNodeWithTag(UiTags.GROCERY_RUN_END_RUN).performClick()
        assertEquals(listOf("end"), log)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land")
    fun `in landscape the step Pas trouvés shows the same choices`() {
        draw(leftoverState())

        compose.onNodeWithTag(UiTags.groceryRunNextTime("i-1")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.groceryRunRemove("i-1")).assertIsDisplayed()
    }

    @Test
    fun `Courses finies sums up each store, the articles kept for next time, then Stock à jour, and Fermer closes`() {
        val ends = listOf(
            StoreEnd("s-u", "Super U", taken = 13, movedTo = listOf("Biocoop"), paidCents = 6240),
            StoreEnd("s-b", "Biocoop", taken = 6, paidCents = 1890),
            StoreEnd("s-m", "Marché", taken = 2),
        )
        draw(
            leftoverState().let {
                it.copy(ended = true, load = GroceryRunLoad.EMPTY, run = it.run.copy(ends = ends), keptForNextTime = listOf(RecapLine("i-1", "Pain", null, "Super U")))
            },
        )

        compose.onNodeWithText("Courses finies").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.groceryRunEndStore("s-u")).assertTextEquals("Super U", "13 pris · 1 vers Biocoop", "62,40 €")
        compose.onNodeWithTag(UiTags.groceryRunEndStore("s-b")).assertTextEquals("Biocoop", "6 pris", "18,90 €")
        compose.onNodeWithTag(UiTags.groceryRunEndStore("s-m")).assertTextEquals("Marché", "2 pris")
        compose.onNodeWithText("PROCHAINE FOIS").assertIsDisplayed()
        compose.onNodeWithText("reste sur la liste").assertIsDisplayed()
        assertTopToBottom(
            "Courses finies" to compose.onNodeWithText("Courses finies"),
            "Super U" to compose.onNodeWithTag(UiTags.groceryRunEndStore("s-u")),
            "Pain" to compose.onNodeWithText("Pain"),
            "Stock à jour" to compose.onNodeWithText("Stock à jour"),
        )
        compose.onNodeWithTag(UiTags.GROCERY_RUN_END_CLOSE).performClick()
        assertEquals(1, quits)
    }

    // Recette refusée (10 nov.): « Courses finies » keeps the header, every segment filled, and a hairline between stores.
    @Test
    fun `Courses finies keeps the header and draws a hairline between the stores`() {
        val ends = listOf(StoreEnd("s-u", "Super U", taken = 1), StoreEnd("s-b", "Biocoop", taken = 1), StoreEnd("s-m", "Marché", taken = 1))
        // Every article bought: the list read once over holds none, nor their stores.
        val state = leftoverState().let { it.copy(ended = true, load = GroceryRunLoad.EMPTY, stores = emptyList(), items = emptyList(), run = it.run.copy(ends = ends)) }
        draw(state)

        compose.onNodeWithTag(UiTags.GROCERY_RUN_HEADER).assertTextEquals("Courses")
        compose.onNodeWithTag(UiTags.GROCERY_RUN_HEADER_COUNT).assertTextEquals("3 magasins")
        assertEquals(listOf(true, true, true), state.progressSegments())
        compose.onAllNodesWithTag(UiTags.GROCERY_RUN_END_DIVIDER, useUnmergedTree = true).assertCountEquals(2)
    }
}
