package com.maggie.app.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.UserPreference
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
        compose.onNodeWithText("Réglages").assertDoesNotExist()
    }

    @Test
    fun `a renamed Maggie gives her name to the entry and the screen title`() {
        compose.setContent {
            SettingsScreen(viewModel = viewModel(SettingsUiState(agentName = "Alfred")), onBack = {})
        }

        compose.onNodeWithText("Maggie").assertDoesNotExist()
        compose.onNodeWithText("Alfred").assertIsDisplayed().performClick()
        compose.onNodeWithText("Alfred").assertIsDisplayed()
        compose.onNodeWithText("Réglages").assertDoesNotExist()
    }

    /** Réglages › Maggie (MAG-787): the two rendezvous, as saved. */
    private fun openAgent(state: SettingsUiState): SettingsViewModel {
        val viewModel = viewModel(state)
        compose.setContent { SettingsScreen(viewModel = viewModel, onBack = {}) }
        compose.onNodeWithTag(UiTags.SETTINGS_AGENT).performClick()
        return viewModel
    }

    @Test
    fun `the two cards show the saved switches and hours`() {
        openAgent(
            SettingsUiState(
                preferences = UserPreference(morningDigestEnabled = false, eveningReviewTime = "22:15"),
            ),
        )

        compose.onNodeWithText("Digest du matin").assertIsDisplayed()
        compose.onNodeWithText("Bilan du soir").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SETTINGS_DIGEST_SWITCH).assertIsOff()
        compose.onNodeWithTag(UiTags.SETTINGS_REVIEW_SWITCH).assertIsOn()
        compose.onNodeWithText("07:00").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SETTINGS_DIGEST_TIME).assertIsNotEnabled()
        compose.onNodeWithText("22:15").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SETTINGS_RENDEZVOUS_SAVED).assertDoesNotExist()
    }

    @Test
    fun `a tap on a switch changes it and a save shows Enregistre`() {
        val viewModel = openAgent(SettingsUiState(preferences = UserPreference(), rendezvousSaved = true))

        compose.onNodeWithTag(UiTags.SETTINGS_DIGEST_SWITCH).performClick()

        verify { viewModel.setMorningDigestEnabled(false) }
        compose.onNodeWithTag(UiTags.SETTINGS_RENDEZVOUS_SAVED).assertIsDisplayed()
    }

    @Test
    fun `the hour opens the clock`() {
        openAgent(SettingsUiState(preferences = UserPreference()))

        compose.onNodeWithTag(UiTags.SETTINGS_REVIEW_TIME).performClick()

        compose.onNodeWithTag(UiTags.PICKER_CONFIRM).assertIsDisplayed()
    }

    @Test
    fun `the working hours card shows the saved hours and days`() {
        openAgent(SettingsUiState(preferences = UserPreference(workEndTime = "17:00", workDays = listOf(1, 2, 3, 4))))

        compose.onNodeWithTag(UiTags.SETTINGS_WORK_START).performScrollTo().assertTextContains("09:00", substring = true)
        compose.onNodeWithTag(UiTags.SETTINGS_WORK_END).assertTextContains("17:00", substring = true)
        compose.onNodeWithTag(UiTags.settingsWorkDay(4)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(UiTags.settingsWorkDay(5)).assertIsNotSelected()
    }

    @Test
    fun `a day's pill and the end hour reach the view model`() {
        val viewModel = openAgent(SettingsUiState(preferences = UserPreference()))

        compose.onNodeWithTag(UiTags.settingsWorkDay(5)).performScrollTo().performClick()
        verify { viewModel.toggleWorkDay(5) }

        compose.onNodeWithTag(UiTags.SETTINGS_WORK_END).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.PICKER_CONFIRM).performClick()
        verify { viewModel.setWorkEndTime(18, 0) }
    }

    @Test
    fun `an end before the start says so`() {
        openAgent(SettingsUiState(preferences = UserPreference(), rendezvousError = WORK_END_BEFORE_START))

        compose.onNodeWithText(WORK_END_BEFORE_START).performScrollTo().assertIsDisplayed()
    }
}
