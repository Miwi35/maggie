package com.maggie.app.ui.components

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Android 13+ shows nothing until the permission is asked: once, with the reason (MAG-29). */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionTest {

    @get:Rule
    val compose = ScreenRule()

    private val manager = ApplicationProvider.getApplicationContext<Application>()
        .getSystemService(NotificationManager::class.java)

    @Test
    fun `without the permission, the app asks for it and says why`() {
        shadowOf(manager).setNotificationsEnabled(false)

        compose.setContent { NotificationPermissionPrompt() }

        compose.onNodeWithText("Recevoir les notifications ?").assertIsDisplayed()
        compose.onNodeWithText("Autoriser").assertIsDisplayed()
    }

    @Test
    fun `with the permission, nothing is asked`() {
        shadowOf(manager).setNotificationsEnabled(true)

        compose.setContent { NotificationPermissionPrompt() }

        compose.onNodeWithText("Recevoir les notifications ?").assertDoesNotExist()
    }

    @Test
    fun `asked once, the answer is kept for the next start`() {
        shadowOf(manager).setNotificationsEnabled(false)
        compose.setContent { NotificationPermissionPrompt() }

        compose.onNodeWithText("Plus tard").performClick()

        compose.onNodeWithText("Recevoir les notifications ?").assertDoesNotExist()

        val prefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("push", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("notification_permission_asked", false))
    }
}
