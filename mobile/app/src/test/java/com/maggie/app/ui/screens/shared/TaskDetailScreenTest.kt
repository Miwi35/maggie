package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskLink
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A task's detail with what Google Tasks gives it (MAG-404): the notes, the
 * subtasks in their order, the links. The same content is the phone's sheet and
 * the tablet's pane; `15-task-subtasks` opens it on a device.
 */
@RunWith(AndroidJUnit4::class)
class TaskDetailScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val parent = Task(
        id = "p1",
        title = "Préparer le déménagement",
        description = "Les cartons sont à la cave",
        dueDate = "2026-10-13",
        links = listOf(TaskLink(type = "email", description = "Le mail du déménageur", link = "https://mail.google.com/mail/#all/abc")),
    )
    private val truck = Task(id = "s1", title = "Réserver le camion", parent = "/api/tasks/p1", position = "0")
    private val bank = Task(id = "s2", title = "Changer d'adresse à la banque", parent = "/api/tasks/p1", position = "1")

    private fun show(task: Task, onEdit: () -> Unit = {}, onToggleDone: (Boolean) -> Unit = {}) {
        compose.setContent {
            TaskDetailContent(
                task = task,
                subtasks = SubtasksUiState(parentId = "p1", subtasks = listOf(truck, bank)),
                onEdit = onEdit,
                onToggleDone = onToggleDone,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        }
        compose.waitForIdle()
    }

    private fun top(text: String): Float =
        compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    @Test
    fun `the notes, the due day, the links and the subtasks in their order`() {
        show(parent)

        compose.onNodeWithText("Les cartons sont à la cave").assertIsDisplayed()
        compose.onNodeWithText("Mardi 13 octobre 2026").assertIsDisplayed()
        compose.onNodeWithText("Le mail du déménageur").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sous-tâches").performScrollTo().assertIsDisplayed()
        assertTrue(top("Réserver le camion") < top("Changer d'adresse à la banque"))
    }

    /** MAG-721: a fiche is read; every change goes through the pencil. */
    @Test
    fun `the fiche has no field, no delete, no add-subtask, and a pencil`() {
        var edited = 0
        show(parent, onEdit = { edited++ })

        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Supprimer").assertDoesNotExist()
        compose.onNodeWithContentDescription("Ajouter la sous-tâche").assertDoesNotExist()
        compose.onNodeWithText("Ajouter une sous-tâche").assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TASK_DETAIL_EDIT).assertIsDisplayed().performClick()
        assertEquals(1, edited)
    }

    @Test
    fun `the rows read Échéance, Criticité and Priorité`() {
        show(parent.copy(criticality = "high", priority = "low"))

        compose.onNodeWithText("Échéance").assertIsDisplayed()
        compose.onNodeWithText("Criticité").assertIsDisplayed()
        compose.onNodeWithText("Élevé").assertIsDisplayed()
        compose.onNodeWithText("Priorité").assertIsDisplayed()
        compose.onNodeWithText("Basse").assertIsDisplayed()
        assertTrue(top("Échéance") < top("Criticité"))
        assertTrue(top("Criticité") < top("Priorité"))
    }

    @Test
    fun `« Marquer comme faite » ticks the task, and unticks a done one`() {
        val calls = mutableListOf<Boolean>()
        show(parent, onToggleDone = { calls += it })
        compose.onNodeWithText("Marquer comme faite").performScrollTo().performClick()

        assertEquals(listOf(true), calls)
    }

    /** MAG-406: the urgency the API computed, as a word with an icon, never as something to set. */
    @Test
    fun `the urgency is read in words, and absent when the API sent none`() {
        show(parent.copy(urgency = "critical"))

        compose.onNodeWithText("Urgence :").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TASK_URGENCY, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("critique", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a task without urgency shows no urgency line`() {
        show(parent)

        compose.onNodeWithText("Urgence :").assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TASK_URGENCY, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a subtask shows no subtasks section`() {
        show(truck)

        compose.onNodeWithText("Sous-tâches").assertDoesNotExist()
    }
}
