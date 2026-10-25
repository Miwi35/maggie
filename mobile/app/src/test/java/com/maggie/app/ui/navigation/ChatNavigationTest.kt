package com.maggie.app.ui.navigation

import com.maggie.app.ui.components.DRAWER_DESTINATIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNavigationTest {
    @Test
    fun `the drawer has no chat entry, Maggie lives in the bottom bar`() {
        assertTrue(DRAWER_DESTINATIONS.none { it.route == Screen.Chat.route })
        assertTrue(DRAWER_DESTINATIONS.none { it.label == "Chat" })
    }

    @Test
    fun `every drawer entry is unique`() {
        val routes = DRAWER_DESTINATIONS.map { it.route }

        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun `the chat bottom bar is hidden on the chat screen itself`() {
        assertFalse(showsChatBottomBar(Screen.Chat.route))
    }

    @Test
    fun `the chat bottom bar stays on the other main screens`() {
        assertTrue(showsChatBottomBar(Screen.Home.route))
        assertTrue(showsChatBottomBar(Screen.Calendar.route))
        assertFalse(showsChatBottomBar(Screen.EventEdit.route))
        assertTrue("the bottom of Finance is Maggie's", showsChatBottomBar(Screen.FinanceDashboard.route))
        assertTrue(showsChatBottomBar(Screen.Cushion.route))
        assertFalse(showsChatBottomBar(null))
    }
}
