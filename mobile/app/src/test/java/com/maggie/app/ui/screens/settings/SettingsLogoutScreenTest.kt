package com.maggie.app.ui.screens.settings

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** « Se déconnecter » is a text button of the charte under the list, not a bar across the screen (MAG-634). */
@RunWith(AndroidJUnit4::class)
class SettingsLogoutScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val viewModel = mockk<SettingsViewModel>(relaxed = true).also {
        every { it.uiState } returns MutableStateFlow(SettingsUiState())
    }

    @Test
    fun `the logout button hugs its label instead of filling the width, and still logs out`() {
        compose.setContent { SettingsScreen(viewModel = viewModel, onBack = {}) }

        val logout = compose.onNodeWithTag(UiTags.SETTINGS_LOGOUT)
        logout.performScrollTo()
        val width = logout.getBoundsInRoot().let { it.right - it.left }
        val screen = compose.onRoot().getBoundsInRoot().let { it.right - it.left }
        assertTrue("logout is $width wide on a $screen screen", width < screen - 64.dp)

        logout.performClick()
        verify(exactly = 1) { viewModel.logout() }
    }
}
