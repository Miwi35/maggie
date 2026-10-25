package com.maggie.app.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The names of the mock-up (`charte/Tiroir`, MAG-620): « Réglages » and « Agenda », never « Paramètres » and « Calendrier ». */
@RunWith(AndroidJUnit4::class)
class SettingsNamesScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `the screen is Réglages and its calendar entry is Agenda`() {
        val viewModel = mockk<SettingsViewModel>(relaxed = true).also {
            every { it.uiState } returns MutableStateFlow(SettingsUiState())
        }
        compose.setContent { SettingsScreen(viewModel = viewModel, onBack = {}) }

        compose.onNodeWithText("Réglages").assertIsDisplayed()
        compose.onNodeWithText("Agenda").assertIsDisplayed()
        compose.onNodeWithText("Paramètres").assertDoesNotExist()
        compose.onNodeWithText("Calendrier").assertDoesNotExist()
    }
}
