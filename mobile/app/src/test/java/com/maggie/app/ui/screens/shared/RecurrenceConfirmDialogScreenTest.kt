package com.maggie.app.ui.screens.shared

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecurrenceConfirmDialogScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `offers the three choices by default`() {
        compose.setContent { RecurrenceConfirmDialog(isDelete = true, onConfirm = {}, onDismiss = {}) }

        compose.onNodeWithText("Cet événement").assertIsDisplayed()
        compose.onNodeWithText("Cet événement et tous les suivants").assertIsDisplayed()
        compose.onNodeWithText("Tous les événements").assertIsDisplayed()
    }

    @Test
    fun `offers only the choices it is given`() {
        var chosen: RecurrenceAction? = null
        compose.setContent {
            RecurrenceConfirmDialog(
                isDelete = true,
                actions = listOf(RecurrenceAction.THIS, RecurrenceAction.ALL),
                onConfirm = { chosen = it },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("Cet événement").assertIsDisplayed()
        compose.onNodeWithText("Cet événement et tous les suivants").assertDoesNotExist()
        compose.onNodeWithText("Tous les événements").performClick()
        compose.onNodeWithText("Supprimer").performClick()

        assertEquals(RecurrenceAction.ALL, chosen)
    }
}
