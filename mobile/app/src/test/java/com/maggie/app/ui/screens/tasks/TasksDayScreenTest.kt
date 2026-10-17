package com.maggie.app.ui.screens.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DaySlot
import com.maggie.app.data.model.DayTask
import com.maggie.app.data.model.TaskView
import com.maggie.app.data.model.TaskViewResponse
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.reward.TaskRewards
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * « Aujourd'hui » as the day (MAG-497), over the real ViewModel and a faked repository: « Maintenant »
 * with its first step and estimate, the ring, the five in order with « reportée » in words, and the
 * reserve folded under its count until asked for. `17b-tasks-day` adds a task on a device.
 */
@RunWith(AndroidJUnit4::class)
class TasksDayScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val today = LocalDate.of(2026, 10, 15)

    private val form = DayTask(
        id = "d1",
        title = "Renvoyer le formulaire de la mutuelle",
        estimatedMinutes = 10,
        firstStep = "Ouvrir le dossier",
        postponedSince = "2026-10-12",
        urgency = "high",
    )
    private val tax = DayTask(id = "d2", title = "Payer l'impôt", postponedSince = "2026-10-12", urgency = "critical")
    private val bins = DayTask(id = "d3", title = "Sortir les poubelles", completedAt = "2026-10-15T07:00:00Z")
    private val photos = DayTask(id = "r1", title = "Trier les photos de vacances", postponedSince = "2026-10-14")

    private fun show(
        slotted: List<DayTask>,
        reserve: List<DayTask> = listOf(photos),
        onFocus: (String) -> Unit = {},
    ): TasksViewModel {
        val slots = slotted.mapIndexed { i, task -> DaySlot(position = i + 1, task = task) }
        val day = DayPlan(
            id = "day",
            date = today.toString(),
            slots = slots,
            now = slots.firstOrNull { !it.task.isDone },
            reserve = reserve,
            done = slots.count { it.task.isDone },
            total = slots.size,
        )
        val repository = mockk<TaskRepository>()
        coEvery { repository.getDayPlan(today) } returns Result.success(day)
        coEvery { repository.getTaskView(TaskView.TODAY, null) } returns Result.success(TaskViewResponse())
        coEvery { repository.getMorningTime() } returns Result.failure(IllegalStateException("offline"))
        coEvery { repository.getEveningTime() } returns Result.failure(IllegalStateException("offline"))
        val auth = mockk<AuthRepository>()
        every { auth.token } returns flowOf("jwt")
        coEvery { auth.getUserId() } returns "u1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        val viewModel = TasksViewModel(repository, mercure, auth, TaskRewards(repository, mockk(relaxed = true)), today = { today })
        compose.setContent { TasksScreen(viewModel = viewModel, onTaskClick = {}, onFocus = onFocus) }
        compose.waitForIdle()
        return viewModel
    }

    @Test
    fun `« Maintenant » shows the first task not done, its first step and its estimate`() {
        show(listOf(bins, form, tax))

        compose.onNodeWithTag(UiTags.TASKS_NOW).assertIsDisplayed()
        compose.onNodeWithText("Ouvrir le dossier").assertIsDisplayed()
        compose.onNodeWithText("Juste 10 minutes").assertIsDisplayed()
        compose.onNodeWithContentDescription("1 sur 3 tâches faites").assertIsDisplayed()
    }

    @Test
    fun `the five in order, « reportée » in words and a critical task with its own mark`() {
        show(listOf(form, tax, bins))

        compose.onNodeWithText("Mes 5").assertIsDisplayed()
        assertTopToBottom(
            "slot 1" to compose.onNodeWithTag(UiTags.tasksSlot(1)),
            "slot 2" to compose.onNodeWithTag(UiTags.tasksSlot(2)),
            "slot 3" to compose.onNodeWithTag(UiTags.tasksSlot(3)),
        )
        compose.onNodeWithText("reportée depuis lundi", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("critique", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("En retard", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the reserve is folded under its count until its header is tapped`() {
        show(listOf(form))

        compose.onNodeWithText("Réserve (1)").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.tasksReserveItem("r1")).assertDoesNotExist()

        compose.onNodeWithTag(UiTags.TASKS_RESERVE_TOGGLE).performClick()
        compose.onNode(hasTestTag(UiTags.TASKS)).assertIsDisplayed()
        compose.onNodeWithText("Trier les photos de vacances").assertIsDisplayed()
        compose.onNodeWithText("reportée depuis hier", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `an empty day has no « Maintenant » and no ring, but its add field and its reserve`() {
        show(emptyList())

        compose.onNodeWithTag(UiTags.TASKS_NOW).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TASKS_RING).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TASKS_DAY_ADD_INPUT).assertIsDisplayed()
        compose.onNodeWithText("Réserve (1)").assertIsDisplayed()
    }

    @Test
    fun `« Juste 10 minutes » opens the Focus on the task of « Maintenant »`() {
        val focused = mutableListOf<String>()
        show(listOf(bins, form, tax), onFocus = { focused += it })

        compose.onNodeWithTag(UiTags.TASKS_NOW_FOCUS).performClick()

        assertEquals(listOf("d1"), focused)
    }

    @Test
    fun `the menu of a slot offers the Focus on that slot's task`() {
        val focused = mutableListOf<String>()
        show(listOf(form, tax), onFocus = { focused += it })

        compose.onNodeWithTag(UiTags.tasksSlotMenu("d2")).performClick()
        compose.onNodeWithTag(UiTags.TASKS_SLOT_FOCUS).performClick()

        assertEquals(listOf("d2"), focused)
    }
}
