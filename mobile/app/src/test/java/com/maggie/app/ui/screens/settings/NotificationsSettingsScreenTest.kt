package com.maggie.app.ui.screens.settings

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Réglages › Notifications says when Android would drop every notification, and opens its settings (MAG-29). */
@RunWith(AndroidJUnit4::class)
class NotificationsSettingsScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(NotificationManager::class.java)

    private val viewModel = mockk<SettingsViewModel>(relaxed = true).also {
        every { it.uiState } returns MutableStateFlow(SettingsUiState())
    }

    @Test
    fun `a refused permission is said in the list and in the section, and opens Android's settings`() {
        shadowOf(manager).setNotificationsEnabled(false)
        compose.setContent { SettingsScreen(viewModel = viewModel, onBack = {}) }

        compose.onNodeWithText("Bloquées dans Android").assertIsDisplayed()
        compose.onNodeWithText("Notifications").performClick()
        compose.onNodeWithText("Les notifications sont bloquées dans Android", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Ouvrir les réglages Android").performClick()

        val started: Intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(app.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `a granted permission shows no warning`() {
        shadowOf(manager).setNotificationsEnabled(true)
        compose.setContent { SettingsScreen(viewModel = viewModel, onBack = {}) }

        compose.onNodeWithText("Bloquées dans Android").assertDoesNotExist()
        compose.onNodeWithText("Notifications").performClick()
        compose.onNodeWithText("Ouvrir les réglages Android").assertDoesNotExist()
    }
}
