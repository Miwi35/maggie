package com.maggie.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthRedirectTest {
    @Test
    fun `nothing happens while the signed-in state is still loading`() {
        assertEquals(AuthRedirect.NONE, authRedirect(null, isLocked = true, route = null))
        assertEquals(AuthRedirect.NONE, authRedirect(null, isLocked = false, route = Screen.Loading.route))
    }

    @Test
    fun `a signed-out user is sent to the login screen`() {
        assertEquals(AuthRedirect.LOGIN, authRedirect(false, isLocked = true, route = Screen.Loading.route))
        assertEquals(AuthRedirect.LOGIN, authRedirect(false, isLocked = false, route = Screen.Chat.route))
        assertEquals(AuthRedirect.NONE, authRedirect(false, isLocked = false, route = Screen.Login.route))
    }

    @Test
    fun `a signed-in user leaves the loading and login screens for the dashboard`() {
        assertEquals(AuthRedirect.DASHBOARD, authRedirect(true, isLocked = false, route = null))
        assertEquals(AuthRedirect.DASHBOARD, authRedirect(true, isLocked = false, route = Screen.Loading.route))
        assertEquals(AuthRedirect.DASHBOARD, authRedirect(true, isLocked = false, route = Screen.Login.route))
    }

    @Test
    fun `a locked app stays where it is until it is unlocked`() {
        assertEquals(AuthRedirect.NONE, authRedirect(true, isLocked = true, route = Screen.Loading.route))
    }

    @Test
    fun `a deep link already on the back stack is never replaced by the dashboard`() {
        assertEquals(AuthRedirect.NONE, authRedirect(true, isLocked = false, route = Screen.Chat.route))
        assertEquals(AuthRedirect.NONE, authRedirect(true, isLocked = false, route = DeepLinks.EVENT_ROUTE))
        assertEquals(AuthRedirect.NONE, authRedirect(true, isLocked = false, route = Screen.FinanceDashboard.route))
    }
}
