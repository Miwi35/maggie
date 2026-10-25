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
        val chrome = chromeFor(phone, Screen.Home.route)

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
     * Réglages is a rail entry that is not a main screen, so it takes the whole width
     * and loses the rail. That is today's behaviour on a phone — the screen brings its
     * own back arrow — and it is written down in `plan.md` rather than left to be
     * discovered on a tablet. Finance has its own rail, below.
     */
    @Test
    fun `a rail entry that is not a main screen keeps the whole width`() {
        listOf(Screen.Settings.route).forEach { route ->
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
    fun `the lists draw their detail beside them where the window has room`() {
        listOf(Screen.Calendar, Screen.Cookbook, Screen.Grocery, Screen.AccountList, Screen.Contacts).forEach { screen ->
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
    fun `the Accueil has no pane, its events stay sheets on a tablet too`() {
        val chrome = chromeFor(tabletLandscape, Screen.Home.route)

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
    fun `an account opened from the dashboard goes straight to the list when a pane has room`() {
        assertEquals(Screen.AccountList.route, accountRouteFor(true))
        assertEquals(Screen.AccountTransactions.route, accountRouteFor(false))
        // The detail route it avoids would be folded into this very list, losing its one-shot request.
        assertEquals(accountRouteFor(true), foldsDetailRouteIntoPane(Screen.AccountTransactions.route, true))
    }

    @Test
    fun `a route that is not a detail route is never replaced`() {
        listOf(Screen.Cookbook, Screen.Calendar, Screen.RecipeEdit, Screen.Loading).forEach {
            assertNull(it.route, foldsDetailRouteIntoPane(it.route, true))
        }
        assertNull(foldsDetailRouteIntoPane(null, true))
    }

    @Test
    fun `entering the Accueil on a wide window drops what a pane held`() {
        assertTrue(dropsPaneSelection(Screen.Home.route, true))
        assertFalse("the phone's sheet is the Accueil's own", dropsPaneSelection(Screen.Home.route, false))
        assertFalse(dropsPaneSelection(Screen.Calendar.route, true))
        assertFalse(dropsPaneSelection(null, true))
    }

    @Test
    fun `an item open in the pane opens again as a route once the pane is gone`() {
        assertEquals(Screen.RecipeDetail.route, reopensDetailRoute(Screen.Cookbook.route, recipeOpen = true, accountOpen = false))
        assertEquals(Screen.AccountTransactions.route, reopensDetailRoute(Screen.AccountList.route, recipeOpen = false, accountOpen = true))
    }

    @Test
    fun `nothing reopens without an open item or away from the list`() {
        assertNull(reopensDetailRoute(Screen.Cookbook.route, recipeOpen = false, accountOpen = true))
        assertNull(reopensDetailRoute(Screen.AccountList.route, recipeOpen = true, accountOpen = false))
        assertNull(reopensDetailRoute(Screen.Calendar.route, recipeOpen = true, accountOpen = true))
        assertNull(reopensDetailRoute(null, recipeOpen = true, accountOpen = true))
    }

    @Test
    fun `on a tablet no finance screen draws a rail, the breadcrumb is its navigation (MAG-537)`() {
        FinanceSection.entries.flatMap { it.pages + it.details }.forEach { route ->
            val chrome = chromeFor(tabletLandscape, route)

            assertFalse("$route should not draw the main rail", chrome.showsRail)
            assertTrue("$route keeps the conversation", chrome.showsChatPanel)
            assertFalse("$route has no chat band at the bottom", chrome.showsChatBar)
        }
    }

    @Test
    fun `on a phone the finance screens keep Maggie's bar at the bottom and no rail`() {
        FinanceSection.entries.flatMap { it.pages + it.details }.forEach { route ->
            val chrome = chromeFor(phone, route)

            assertFalse("$route should not draw a rail", chrome.showsRail)
            assertTrue("$route keeps Maggie's bar at the bottom", chrome.showsChatBar)
        }
    }

    @Test
    fun `a short wide window keeps the compact bottom bar on finance, no rail and no chat in a rail`() {
        val chrome = chromeFor(phoneLandscape, Screen.FinanceDashboard.route)

        assertFalse(chrome.showsRail)
        assertFalse(chrome.showsChatInRail)
        assertTrue(chrome.showsChatBar)
    }

    @Test
    fun `the main rail stays where it was outside the finance module`() {
        assertTrue(chromeFor(tabletLandscape, Screen.Grocery.route).showsRail)
        assertTrue(chromeFor(phoneLandscape, Screen.Grocery.route).showsChatInRail)
    }
}
