package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskLink
import com.maggie.app.screentest.ScreenRule
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

    private fun show(task: Task, actions: SubtaskActions = SubtaskActions()) {
        compose.setContent {
            TaskDetailContent(
                task = task,
                subtasks = SubtasksUiState(parentId = "p1", subtasks = listOf(truck, bank)),
                subtaskActions = actions,
                onEdit = {},
                onDelete = {},
                onToggleDone = {},
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

    @Test
    fun `the first subtask cannot move up, nor the last down`() {
        show(parent)

        compose.onNodeWithContentDescription("Monter « Réserver le camion »").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Descendre « Changer d'adresse à la banque »").assertIsNotEnabled()
    }

    @Test
    fun `ticking, moving and adding go to the actions with the subtask they are about`() {
        val calls = mutableListOf<String>()
        show(
            parent,
            SubtaskActions(
                onToggleDone = { id, done -> calls += "tick $id $done" },
                onAdd = { calls += "add $it" },
                onMoveUp = { calls += "up $it" },
                onMoveDown = { calls += "down $it" },
            ),
        )

        compose.onNodeWithContentDescription("Terminer « Réserver le camion »").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Descendre « Réserver le camion »").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Acheter des cartons")
        compose.onNodeWithContentDescription("Ajouter la sous-tâche").performClick()

        assertEquals(listOf("tick s1 true", "down s1", "add Acheter des cartons"), calls)
    }

    @Test
    fun `a subtask shows no subtasks section`() {
        show(truck)

        compose.onNodeWithText("Sous-tâches").assertDoesNotExist()
        compose.onNodeWithText("Ajouter une sous-tâche").assertDoesNotExist()
    }
}
