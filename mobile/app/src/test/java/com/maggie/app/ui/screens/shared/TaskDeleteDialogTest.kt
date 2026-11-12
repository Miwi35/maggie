package com.maggie.app.ui.screens.shared

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
class TaskDeleteDialogTest {

    @get:Rule
    val compose = ScreenRule()

    private var confirmed = 0
    private var dismissed = 0

    private fun show(subtaskCount: Int = 0) {
        compose.setContent {
            TaskDeleteDialog(
                title = "Payer la cantine",
                subtaskCount = subtaskCount,
                onConfirm = { confirmed++ },
                onDismiss = { dismissed++ },
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `titleNamesTheTask`() {
        show()
        compose.onNodeWithText("Supprimer la tâche « Payer la cantine » ?").assertExists()
    }

    @Test
    fun `titleCountsSubtasks`() {
        show(subtaskCount = 3)
        compose.onNodeWithText("Supprimer la tâche « Payer la cantine » et ses 3 sous-tâches ?").assertExists()
    }

    @Test
    fun `cancelDoesNotConfirm`() {
        show()
        compose.onNodeWithTag(UiTags.TASK_DELETE_CANCEL).performClick()
        assertEquals(0, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun `confirmCallsOnConfirm`() {
        show()
        compose.onNodeWithTag(UiTags.TASK_DELETE_CONFIRM).performClick()
        assertEquals(1, confirmed)
    }
}
