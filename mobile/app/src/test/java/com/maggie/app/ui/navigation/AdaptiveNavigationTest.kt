package com.maggie.app.ui.navigation

import com.maggie.app.ui.layout.appLayoutFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val foldableOpen = appLayoutFor(674, 841)
    private val tabletPortrait = appLayoutFor(800, 1280)
    private val tabletLandscape = appLayoutFor(1280, 800)

    @Test
    fun `a phone keeps the burger and the collapsed bar`() {
        val chrome = chromeFor(phone, Screen.Dashboard.route)

        assertFalse(chrome.showsRail)
        assertFalse(chrome.showsChatPanel)
        assertTrue(chrome.showsChatBar)
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
}
