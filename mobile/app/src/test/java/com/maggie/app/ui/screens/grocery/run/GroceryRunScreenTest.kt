package com.maggie.app.ui.screens.grocery.run

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        setVisited = { id, visited -> log += "visit:$id:$visited" },
        start = { log += "start" },
        retry = { log += "retry" },
        dismissQuit = { log += "stay" },
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
        compose.onNodeWithText("C'est parti").assertIsDisplayed()
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
    fun `the choice buttons and C'est parti act`() {
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
}
