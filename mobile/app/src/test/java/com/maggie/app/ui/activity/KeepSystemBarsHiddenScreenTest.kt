package com.maggie.app.ui.activity

import android.app.Activity
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** MAG-785: a sheet is a window of its own, and the status bar came back while it was open. */
@RunWith(AndroidJUnit4::class)
class KeepSystemBarsHiddenScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `a sheet asks for its own window's bars to be hidden`() {
        val hidden = mutableListOf<Window>()
        var activityWindow: Window? = null

        compose.setContent {
            activityWindow = (LocalContext.current as Activity).window
            ModalBottomSheet(onDismissRequest = {}) {
                KeepSystemBarsHidden(hide = { hidden += it })
                Text("Contenu de la feuille")
            }
        }

        compose.onNodeWithText("Contenu de la feuille").assertIsDisplayed()
        assertEquals(1, hidden.distinct().size)
        assertNotSame(activityWindow, hidden.first())
    }

    @Test
    fun `outside a dialog window there is nothing to hide`() {
        val hidden = mutableListOf<Window>()

        compose.setContent {
            Box { KeepSystemBarsHidden(hide = { hidden += it }) }
        }
        compose.waitForIdle()

        assertEquals(emptyList<Window>(), hidden)
    }
}
