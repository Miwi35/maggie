package com.maggie.app.ui.screens.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Task
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A late task says so in words, with its original day (MAG-405), and so does its urgency (MAG-406): the colour alone says nothing to everyone. */
@RunWith(AndroidJUnit4::class)
class DashboardTaskItemScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `a late task reads how late it is and its original day`() {
        compose.setContent {
            DashboardTaskItem(
                task = Task(id = "t", title = "Renvoyer le formulaire", dueDate = "2026-10-11", overdueDays = 2),
                onToggleDone = {},
            )
        }

        compose.onNodeWithText("En retard de 2 j").assertIsDisplayed()
        compose.onNodeWithText("prévue le 11 oct.").assertIsDisplayed()
    }

    @Test
    fun `a task due today carries no lateness and shows its plain day`() {
        compose.setContent {
            DashboardTaskItem(
                task = Task(id = "t", title = "Appeler le garage", dueDate = "2026-10-13"),
                onToggleDone = {},
            )
        }

        compose.onNodeWithText("13 oct.").assertIsDisplayed()
        compose.onNodeWithText("En retard", substring = true).assertDoesNotExist()
    }

    @Test
    fun `ticking a late task reports it as done`() {
        var ticked: Boolean? = null
        compose.setContent {
            DashboardTaskItem(
                task = Task(id = "t", title = "Renvoyer le formulaire", dueDate = "2026-10-11", overdueDays = 2),
                onToggleDone = { ticked = it },
            )
        }

        // The row opens the detail; the box is what ticks.
        compose.onNodeWithText("Renvoyer le formulaire").performClick()
        assertEquals(null, ticked)
        compose.onNode(isToggleable()).performClick()
        assertEquals(true, ticked)
    }

    /** MAG-406: the urgency is a word with an icon, beside the lateness. */
    @Test
    fun `a task reads its urgency in words, beside its lateness`() {
        compose.setContent {
            DashboardTaskItem(
                task = Task(id = "t", title = "Rendre le livre", dueDate = "2026-10-12", overdueDays = 1, urgency = "high", orderScore = 67),
                onToggleDone = {},
            )
        }

        compose.onNodeWithTag(UiTags.TASK_URGENCY, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("haute", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("En retard de 1 j").assertIsDisplayed()
    }

    @Test
    fun `an urgency the app does not know draws nothing`() {
        compose.setContent {
            DashboardTaskItem(
                task = Task(id = "t", title = "Arroser", dueDate = "2026-10-13", urgency = "whatever-comes-next"),
                onToggleDone = {},
            )
        }

        compose.onNodeWithTag(UiTags.TASK_URGENCY, useUnmergedTree = true).assertDoesNotExist()
    }
}
