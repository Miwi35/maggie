package com.maggie.app.ui.screens.shared

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.api.TaskCreateRequest
import com.maggie.app.data.model.Task
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The one form of a task (MAG-721): « Nouvelle tâche » to create, « Modifier la tâche » to change. */
@RunWith(AndroidJUnit4::class)
class TaskFormScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val task = Task(id = "t1", title = "Piscine", description = "Les clés sont au garage", dueDate = "2026-10-13")
    private val truck = Task(id = "s1", title = "Réserver le camion", parent = "/api/tasks/t1", position = "0")

    private fun showCreate(onCreate: (TaskCreateRequest) -> Unit = {}, initialDueDay: String? = null) {
        compose.setContent {
            TaskFormScreen(task = null, onCreate = onCreate, onUpdate = {}, onDelete = {}, onBack = {}, initialDueDay = initialDueDay)
        }
        compose.waitForIdle()
    }

    private fun showEdit(
        onUpdate: (JsonObject) -> Unit = {},
        onDelete: () -> Unit = {},
        actions: SubtaskActions = SubtaskActions(),
    ) {
        compose.setContent {
            TaskFormScreen(
                task = task,
                onCreate = {},
                onUpdate = onUpdate,
                onDelete = onDelete,
                onBack = {},
                subtasks = SubtasksUiState(parentId = "t1", subtasks = listOf(truck)),
                subtaskActions = actions,
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `creating reads « Nouvelle tâche » and « Créer », with no delete`() {
        showCreate()

        compose.onNodeWithText("Nouvelle tâche").assertIsDisplayed()
        compose.onNodeWithText("Créer").assertIsDisplayed()
        compose.onNodeWithText("Enregistrer").assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TASK_FORM_DELETE).assertDoesNotExist()
        compose.onNodeWithText("Sous-tâches").assertDoesNotExist()
    }

    @Test
    fun `editing reads « Modifier la tâche » and « Enregistrer », with the task's values and a delete`() {
        showEdit()

        compose.onNodeWithText("Modifier la tâche").assertIsDisplayed()
        compose.onNodeWithText("Enregistrer").assertIsDisplayed()
        compose.onNodeWithText("Créer").assertDoesNotExist()
        compose.onNodeWithText("Piscine").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TASK_FORM_DELETE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the fields come in the mockup's order in both modes`() {
        showCreate()

        assertTopToBottom(
            "titre" to compose.onNodeWithTag(UiTags.TASK_FORM_TITLE),
            "échéance" to compose.onNodeWithText("Échéance"),
            "criticité" to compose.onNodeWithText("Criticité"),
            "notes" to compose.onNodeWithTag(UiTags.TASK_NOTES),
        )
    }

    @Test
    fun `« Créer » needs a title, then hands the request over`() {
        val created = mutableListOf<TaskCreateRequest>()
        showCreate(onCreate = { created += it }, initialDueDay = "2026-10-16")

        compose.onNodeWithTag(UiTags.TASK_FORM_SUBMIT).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.TASK_FORM_TITLE).performTextInput("Acheter du pain")
        compose.onNodeWithTag(UiTags.TASK_FORM_SUBMIT).performClick()

        assertEquals(listOf("Acheter du pain"), created.map { it.title })
        assertEquals("2026-10-16", created.single().dueDate)
    }

    @Test
    fun `« Enregistrer » sends the changed title and no completedAt`() {
        val updates = mutableListOf<JsonObject>()
        showEdit(onUpdate = { updates += it })

        compose.onNodeWithTag(UiTags.TASK_FORM_TITLE).performTextInput("Grande ")
        compose.onNodeWithTag(UiTags.TASK_FORM_SUBMIT).performClick()

        assertEquals("Grande Piscine", updates.single()["title"]?.jsonPrimitive?.content)
        assertEquals(false, updates.single().containsKey("completedAt"))
    }

    @Test
    fun `« Supprimer » asks to delete the task`() {
        var deleted = 0
        showEdit(onDelete = { deleted++ })

        compose.onNodeWithTag(UiTags.TASK_FORM_DELETE).performScrollTo().performClick()

        assertEquals(1, deleted)
    }

    @Test
    fun `the subtasks are edited in the form, and go to the actions with the subtask they are about`() {
        val calls = mutableListOf<String>()
        showEdit(
            actions = SubtaskActions(
                onToggleDone = { id, done -> calls += "tick $id $done" },
                onAdd = { calls += "add $it" },
            ),
        )

        compose.onNodeWithContentDescription("Monter « Réserver le camion »").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Terminer « Réserver le camion »").performScrollTo().performClick()
        compose.onNodeWithText("Ajouter une sous-tâche").performScrollTo().performTextInput("Acheter des cartons")
        compose.onNodeWithContentDescription("Ajouter la sous-tâche").performClick()

        assertEquals(listOf("tick s1 true", "add Acheter des cartons"), calls)
    }
}
