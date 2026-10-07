package com.maggie.app.ui.navigation

import com.maggie.app.ui.layout.appLayoutFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the window's size and the current route together decide (MAG-35).
 *
 * The formats themselves are `WindowLayoutTest`; this is the half that also needs a
 * route — the rail serves the drawer's destinations and so appears where the drawer
 * did, the permanent panel replaces the collapsed bar rather than joining it, and
 * the full-screen chat gets neither.
 */
class AdaptiveNavigationTest {

    private val phone = appLayoutFor(412, 1000)
    private val phoneLandscape = appLayoutFor(891, 411)
    private val foldableOpen = appLayoutFor(674, 841)
    private val tabletPortrait = appLayoutFor(800, 1280)
    private val tabletLandscape = appLayoutFor(1280, 800)

    @Test
    fun `a phone keeps the burger and the collapsed bar`() {
        val chrome = chromeFor(phone, Screen.Dashboard.route)

        assertFalse(chrome.showsRail)
        assertFalse(chrome.showsChatPanel)
        assertTrue(chrome.showsChatBar)
        assertFalse(chrome.showsChatInRail)
        assertFalse(chrome.denseTopBar)
    }

    /**
     * The refused recette (MAG-35): a phone in landscape had a 64 dp top bar and a
     * 72 dp band around 275 dp of content. The band's three buttons go to the rail,
     * which costs no height, and the top bar is drawn dense.
     */
    @Test
    fun `a phone in landscape reaches the chat from the rail and keeps no band`() {
        val chrome = chromeFor(phoneLandscape, Screen.Grocery.route)

        assertTrue(chrome.showsRail)
        assertTrue(chrome.showsChatInRail)
        assertFalse("the band is what the window has no height for", chrome.showsChatBar)
        assertFalse(chrome.showsChatPanel)
        assertTrue(chrome.denseTopBar)
    }

    @Test
    fun `the chat screen in landscape gets no entry point of its own`() {
        val chrome = chromeFor(phoneLandscape, Screen.Chat.route)

        assertTrue(chrome.showsRail)
        assertFalse("the screen is the conversation", chrome.showsChatInRail)
        assertFalse(chrome.showsChatBar)
    }

    @Test
    fun `a foldable opened flat gets the rail and keeps the collapsed bar`() {
        val chrome = chromeFor(foldableOpen, Screen.Grocery.route)

        assertTrue(chrome.showsRail)
        assertFalse(chrome.showsChatPanel)
        assertTrue(chrome.showsChatBar)
    }

    @Test
    fun `a tablet in portrait gets the rail and keeps the bar`() {
        val chrome = chromeFor(tabletPortrait, Screen.Cookbook.route)

        assertTrue(chrome.showsRail)
        assertFalse(chrome.showsChatPanel)
        assertTrue(chrome.showsChatBar)
    }

    @Test
    fun `the permanent panel replaces the collapsed bar, it does not join it`() {
        val chrome = chromeFor(tabletLandscape, Screen.Cookbook.route)

        assertTrue(chrome.showsRail)
        assertTrue(chrome.showsChatPanel)
        assertFalse("the panel is the conversation; the bar would be a second way in", chrome.showsChatBar)
        assertFalse("nor would a button in the rail", chrome.showsChatInRail)
    }

    @Test
    fun `the full-screen chat gets no panel and no bar - it is the chat`() {
        val chrome = chromeFor(tabletLandscape, Screen.Chat.route)

        assertTrue(chrome.showsRail)
        assertFalse(chrome.showsChatPanel)
        assertFalse(chrome.showsChatBar)
    }

    /**
     * Paramètres and Finance are both rail entries that are not main screens, so
     * both take the whole width and lose the rail. That is today's behaviour on a
     * phone — each screen brings its own back arrow — and it is written down in
     * `plan.md` rather than left to be discovered on a tablet.
     */
    @Test
    fun `a rail entry that is not a main screen keeps the whole width`() {
        listOf(Screen.Settings.route, Screen.FinanceDashboard.route).forEach { route ->
            val chrome = chromeFor(tabletLandscape, route)

            assertFalse("$route should not keep the rail", chrome.showsRail)
            assertFalse("$route should not keep the panel", chrome.showsChatPanel)
            assertFalse("$route should not keep the bar", chrome.showsChatBar)
            assertFalse("$route should not keep the rail actions", chrome.showsChatInRail)
        }
    }

    @Test
    fun `nothing of the frame is drawn before a route exists`() {
        val chrome = chromeFor(tabletLandscape, null)

        assertFalse(chrome.showsRail)
        assertFalse(chrome.showsChatPanel)
        assertFalse(chrome.showsChatBar)
    }

    @Test
    fun `the sheet opens on a phone and stays shut behind the panel`() {
        assertFalse(showsChatSheet(requested = false, voiceMode = false, hasChatPanel = false))
        assertTrue(showsChatSheet(requested = true, voiceMode = false, hasChatPanel = false))
        assertFalse(
            "the conversation is already on screen",
            showsChatSheet(requested = true, voiceMode = false, hasChatPanel = true),
        )
    }

    @Test
    fun `voice mode still opens the sheet behind the panel`() {
        assertTrue(
            "the panel has no push-to-talk bar",
            showsChatSheet(requested = true, voiceMode = true, hasChatPanel = true),
        )
    }

    @Test
    fun `the four lists draw their detail beside them where the window has room`() {
        listOf(Screen.Calendar, Screen.Cookbook, Screen.Grocery, Screen.AccountList).forEach { screen ->
            val chrome = chromeFor(tabletLandscape, screen.route)

            assertTrue(screen.route, chrome.showsDetailPane)
            assertFalse("no sheet over a screen that has the pane", chrome.detailsAreSheets)
        }
    }

    @Test
    fun `below the threshold the detail stays a sheet or a route, as before`() {
        listOf(phone, foldableOpen).forEach { layout ->
            val chrome = chromeFor(layout, Screen.Calendar.route)

            assertFalse(chrome.showsDetailPane)
            assertTrue(chrome.detailsAreSheets)
        }
    }

    @Test
    fun `the dashboard has no pane, its events stay sheets on a tablet too`() {
        val chrome = chromeFor(tabletLandscape, Screen.Dashboard.route)

        assertFalse(chrome.showsDetailPane)
        assertTrue(chrome.detailsAreSheets)
    }

    @Test
    fun `a screen without a list and detail has no pane even where there is room`() {
        assertFalse(chromeFor(tabletLandscape, Screen.Chat.route).showsDetailPane)
        assertFalse(chromeFor(tabletLandscape, Screen.Settings.route).showsDetailPane)
        assertFalse(chromeFor(tabletLandscape, null).showsDetailPane)
    }

    @Test
    fun `a detail route is replaced by its list once the pane has room`() {
        assertEquals(Screen.Cookbook.route, foldsDetailRouteIntoPane(Screen.RecipeDetail.route, true))
        assertEquals(Screen.AccountList.route, foldsDetailRouteIntoPane(Screen.AccountTransactions.route, true))
    }

    @Test
    fun `a detail route stays a route while the pane has no room`() {
        assertNull(foldsDetailRouteIntoPane(Screen.RecipeDetail.route, false))
        assertNull(foldsDetailRouteIntoPane(Screen.AccountTransactions.route, false))
    }

    @Test
    fun `a route that is not a detail route is never replaced`() {
        listOf(Screen.Cookbook, Screen.Calendar, Screen.RecipeEdit, Screen.Loading).forEach {
            assertNull(it.route, foldsDetailRouteIntoPane(it.route, true))
        }
        assertNull(foldsDetailRouteIntoPane(null, true))
    }

    @Test
    fun `entering the dashboard on a wide window drops what a pane held`() {
        assertTrue(dropsPaneSelection(Screen.Dashboard.route, true))
        assertFalse("the phone's sheet is the dashboard's own", dropsPaneSelection(Screen.Dashboard.route, false))
        assertFalse(dropsPaneSelection(Screen.Calendar.route, true))
        assertFalse(dropsPaneSelection(null, true))
    }
}
