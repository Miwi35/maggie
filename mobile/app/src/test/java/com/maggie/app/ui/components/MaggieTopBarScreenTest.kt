package com.maggie.app.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The generic bar is the menu and the title; the loupe and the bell are the frame's (MAG-712). */
@RunWith(AndroidJUnit4::class)
class MaggieTopBarScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `the bar is the menu and the title when the frame gives no header actions`() {
        compose.setContent { MaggieTopBar(title = "Agenda", onMenuClick = {}) }

        compose.onNodeWithText("Agenda").assertIsDisplayed()
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rechercher").assertDoesNotExist()
    }

    @Test
    fun `the bar wears the header actions of the frame at its right`() {
        compose.setContent {
            CompositionLocalProvider(LocalHeaderActions provides { HeaderActions(com.maggie.app.ui.interruption.InterruptionsUiState(), InterruptionsActions(), onSearch = {}) }) {
                MaggieTopBar(title = "Agenda", onMenuClick = null)
            }
        }

        compose.onNodeWithContentDescription("Rechercher").assertIsDisplayed()
        compose.onNodeWithContentDescription("Interruptions de Maggie").assertIsDisplayed()
    }
}
