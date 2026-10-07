package com.maggie.app.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.BuildConfig
import com.maggie.app.screentest.ScreenRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Paramètres > À propos names the commit the installed app was built from (MAG-254). */
@RunWith(AndroidJUnit4::class)
class AboutSettingsScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val viewModel = mockk<SettingsViewModel>(relaxed = true).also {
        every { it.uiState } returns MutableStateFlow(SettingsUiState())
    }

    @Test
    fun `the about screen shows the commit of the installed build`() {
        compose.setContent { SettingsScreen(viewModel = viewModel, onBack = {}) }

        compose.onNodeWithText("À propos").performClick()

        compose.onNodeWithText("Commit ${BuildConfig.GIT_SHA}").assertIsDisplayed()
    }
}
