package com.maggie.app.ui.screens.shared

import androidx.compose.ui.test.assertContentDescriptionContains
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
import com.maggie.app.util.DateRanges
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        task: Task = this.task,
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
            "échéance" to compose.onNodeWithTag(UiTags.TASK_FORM_DUE),
            "programmée" to compose.onNodeWithTag(UiTags.TASK_FORM_SCHEDULED),
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

    // The dates card (MAG-718).

    private val atSix = task.copy(scheduledDate = "2026-10-16", scheduledMoment = "time", scheduledTime = "18:00", reminders = listOf(0, 30))

    private fun chooseMoment(moment: String) {
        compose.onNodeWithTag(UiTags.TASK_FORM_MOMENT).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.taskFormMoment(moment)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the moment only appears once a day is scheduled, and « Demain » schedules tomorrow`() {
        val created = mutableListOf<TaskCreateRequest>()
        showCreate(onCreate = { created += it })

        compose.onNodeWithTag(UiTags.TASK_FORM_MOMENT).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TASK_FORM_REMINDERS).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TASK_FORM_SCHEDULED).performClick()
        compose.onNodeWithTag(UiTags.PICKER_TOMORROW).performClick()
        compose.onNodeWithTag(UiTags.TASK_FORM_MOMENT).assertExists()

        compose.onNodeWithTag(UiTags.TASK_FORM_TITLE).performTextInput("Appeler le garage")
        compose.onNodeWithTag(UiTags.TASK_FORM_SUBMIT).performClick()

        val request = created.single()
        assertEquals(DateRanges.todayDate().plusDays(1).toString(), request.scheduledDate)
        assertNull(request.scheduledMoment)
        assertNull(request.scheduledTime)
        assertNull(request.reminders)
        assertNull(request.dueDate)
    }

    @Test
    fun `the moment's menu offers the four moments, the time with its value`() {
        showEdit(task = atSix)

        compose.onNodeWithTag(UiTags.TASK_FORM_MOMENT).performScrollTo().assertContentDescriptionContains("18:00", substring = true)
        compose.onNodeWithTag(UiTags.TASK_FORM_MOMENT).performClick()

        listOf("Matin", "Après-midi", "Soir", "Heure précise · 18:00 ›").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `« Soir » hides the reminders and saves no time and no reminder`() {
        val updates = mutableListOf<JsonObject>()
        showEdit(onUpdate = { updates += it }, task = atSix)
        compose.onNodeWithTag(UiTags.TASK_FORM_REMINDERS).performScrollTo().assertIsDisplayed()

        chooseMoment("evening")

        compose.onNodeWithTag(UiTags.TASK_FORM_REMINDERS).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TASK_FORM_SUBMIT).performClick()
        val body = updates.single()
        assertEquals("2026-10-16", body["scheduledDate"]?.jsonPrimitive?.content)
        assertEquals("evening", body["scheduledMoment"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, body["scheduledTime"])
        assertEquals(JsonArray(emptyList()), body["reminders"])
    }

    @Test
    fun `« Heure précise » opens the 24 h clock, then shows the time and the reminders at « À l'heure »`() {
        val updates = mutableListOf<JsonObject>()
        showEdit(onUpdate = { updates += it }, task = task.copy(scheduledDate = "2026-10-16", scheduledMoment = "evening"))
        compose.onNodeWithTag(UiTags.TASK_FORM_REMINDERS).assertDoesNotExist()

        chooseMoment("time")
        compose.onNodeWithText("Heure précise").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PICKER_CONFIRM).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.TASK_FORM_MOMENT).assertContentDescriptionContains("Moment, 18:00")
        compose.onNodeWithTag(UiTags.TASK_FORM_REMINDERS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("À l'heure").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TASK_FORM_SUBMIT).performClick()
        val body = updates.single()
        assertEquals("time", body["scheduledMoment"]?.jsonPrimitive?.content)
        assertEquals("18:00", body["scheduledTime"]?.jsonPrimitive?.content)
        assertEquals(listOf(0), body["reminders"]?.jsonArray?.map { it.jsonPrimitive.int })
    }

    @Test
    fun `clearing the scheduled day clears the whole programmation`() {
        val updates = mutableListOf<JsonObject>()
        showEdit(onUpdate = { updates += it }, task = atSix)

        compose.onNodeWithContentDescription("Effacer : Programmée").performScrollTo().performClick()

        compose.onNodeWithTag(UiTags.TASK_FORM_MOMENT).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TASK_FORM_SUBMIT).performClick()
        val body = updates.single()
        listOf("scheduledDate", "scheduledMoment", "scheduledTime").forEach { assertEquals(it, JsonNull, body[it]) }
        assertEquals(JsonArray(emptyList()), body["reminders"])
        // The due date is another field: it stays.
        assertEquals("2026-10-13", body["dueDate"]?.jsonPrimitive?.content)
    }
}
