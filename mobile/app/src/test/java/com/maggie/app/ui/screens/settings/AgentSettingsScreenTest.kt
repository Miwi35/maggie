package com.maggie.app.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The agent's entry in Réglages is named after her, « Maggie » unless the user renamed her (MAG-329). */
@RunWith(AndroidJUnit4::class)
class AgentSettingsScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun viewModel(state: SettingsUiState) = mockk<SettingsViewModel>(relaxed = true).also {
        every { it.uiState } returns MutableStateFlow(state)
    }

    @Test
    fun `the entry and the screen title say Maggie by default`() {
        compose.setContent { SettingsScreen(viewModel = viewModel(SettingsUiState()), onBack = {}) }

        compose.onNodeWithText("Agent").assertDoesNotExist()
        compose.onNodeWithText("Maggie").assertIsDisplayed().performClick()
        compose.onNodeWithText("Maggie").assertIsDisplayed()
        compose.onNodeWithText("Paramètres").assertDoesNotExist()
    }

    @Test
    fun `a renamed Maggie gives her name to the entry and the screen title`() {
        compose.setContent {
            SettingsScreen(viewModel = viewModel(SettingsUiState(agentName = "Alfred")), onBack = {})
        }

        compose.onNodeWithText("Maggie").assertDoesNotExist()
        compose.onNodeWithText("Alfred").assertIsDisplayed().performClick()
        compose.onNodeWithText("Alfred").assertIsDisplayed()
        compose.onNodeWithText("Paramètres").assertDoesNotExist()
    }
}
