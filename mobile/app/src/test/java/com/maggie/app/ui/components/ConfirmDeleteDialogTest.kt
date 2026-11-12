package com.maggie.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfirmDeleteDialogTest {

    @get:Rule
    val compose = ScreenRule()

    private var confirmed = 0
    private var dismissed = 0

    private fun show() {
        compose.setContent {
            ConfirmDeleteDialog(
                title = "Supprimer le magasin « Biocoop » ?",
                onConfirm = { confirmed++ },
                onDismiss = { dismissed++ },
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `showsTitle`() {
        show()
        compose.onNodeWithText("Supprimer le magasin « Biocoop » ?").assertIsDisplayed()
    }

    @Test
    fun `cancelDismissesWithoutConfirming`() {
        show()
        compose.onNodeWithTag(UiTags.CONFIRM_DELETE_CANCEL).performClick()
        assertEquals(0, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun `confirmCallsOnConfirm`() {
        show()
        compose.onNodeWithTag(UiTags.CONFIRM_DELETE).performClick()
        assertEquals(1, confirmed)
    }
}
