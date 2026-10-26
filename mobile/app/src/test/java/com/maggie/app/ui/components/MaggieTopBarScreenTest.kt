package com.maggie.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The phone's header is the menu and the title (MAG-624); a window with a rail keeps the two actions. */
@RunWith(AndroidJUnit4::class)
class MaggieTopBarScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `on a phone the bar is the menu and the title, nothing else`() {
        compose.setContent { MaggieTopBar(title = "Agenda", onMenuClick = {}, actionsInBar = false, unreadCount = 2) }

        compose.onNodeWithText("Agenda").assertIsDisplayed()
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rechercher").assertDoesNotExist()
        compose.onNodeWithContentDescription("Notifications").assertDoesNotExist()
    }

    @Test
    fun `without a burger the search and the notifications stay in the bar`() {
        compose.setContent { MaggieTopBar(title = "Agenda", onMenuClick = null) }

        compose.onNodeWithContentDescription("Rechercher").assertIsDisplayed()
        compose.onNodeWithContentDescription("Notifications").assertIsDisplayed()
    }
}
