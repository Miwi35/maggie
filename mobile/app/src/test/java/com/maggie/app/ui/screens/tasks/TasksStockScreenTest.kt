package com.maggie.app.ui.screens.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskView
import com.maggie.app.data.model.TaskViewResponse
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.reward.TaskRewards
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** « Tâches » (MAG-934, mock-up `Taches-Tel`): the groups, the cards and « Aujourd'hui » on each row. */
@RunWith(AndroidJUnit4::class)
class TasksStockScreenTest {

    @get:Rule
    val compose = ScreenRule()

    /** A Thursday. */
    private val today = LocalDate.of(2026, 10, 15)

    private val garage = Task(id = "t1", title = "Appeler le garage", dueDate = "2026-10-16", urgency = "high")
    private val mutuelle = Task(id = "t2", title = "Envoyer la mutuelle", scheduledDate = "2026-10-17", rrule = "FREQ=WEEKLY")
    private val bank = Task(id = "t3", title = "Trier les papiers", dueDate = "2026-10-18")
    private val tax = Task(id = "t4", title = "Déclarer les impôts", dueDate = "2026-11-28")
    private val photos = Task(id = "t5", title = "Trier les photos de vacances")
    private val box = Task(id = "t6", title = "Ranger le garage")

    private lateinit var repository: TaskRepository

    private fun show(upcoming: List<Task>, someday: List<Task>): TasksViewModel {
        repository = mockk()
        coEvery { repository.getTaskView(TaskView.UPCOMING, null) } returns Result.success(TaskViewResponse(member = upcoming))
        coEvery { repository.getTaskView(TaskView.SOMEDAY, null) } returns Result.success(TaskViewResponse(member = someday))
        coEvery { repository.getTaskView(TaskView.TODAY, null) } returns Result.success(TaskViewResponse())
        coEvery { repository.getCompletedBetween(any(), any(), any()) } returns emptyList()
        coEvery { repository.updateTask(any(), any()) } returns Result.success(garage)
        val auth = mockk<AuthRepository>()
        every { auth.token } returns flowOf("jwt")
        coEvery { auth.getUserId() } returns "u1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        val viewModel = TasksViewModel(repository, mercure, auth, TaskRewards(repository, mockk(relaxed = true)), offlineRoutines(), today = { today })
        compose.setContent { TasksScreen(viewModel = viewModel, onTaskClick = {}, onCreateTask = {}) }
        compose.waitForIdle()
        viewModel.selectSection(TasksSection.STOCK)
        compose.waitForIdle()
        return viewModel
    }

    @Test
    fun `the groups read CETTE SEMAINE · 3, PLUS TARD · 1, SANS DATE · 2`() {
        show(upcoming = listOf(garage, mutuelle, bank, tax), someday = listOf(photos, box))

        compose.onNodeWithTag(UiTags.tasksStockGroup("this_week")).assertIsDisplayed().assertTextEquals("CETTE SEMAINE · 3")
        compose.onNodeWithTag(UiTags.tasksStockGroup("later")).assertTextEquals("PLUS TARD · 1")
        compose.onNodeWithTag(UiTags.tasksStockGroup("undated")).assertTextEquals("SANS DATE · 2")
    }

    @Test
    fun `the Aujourd'hui button of a row calls planToday with its task`() {
        show(upcoming = listOf(garage), someday = listOf(photos))

        compose.onNodeWithTag(UiTags.tasksStockPick("t1")).assertIsDisplayed().performClick()
        compose.waitForIdle()

        coVerify { repository.updateTask("t1", any()) }
    }

    @Test
    fun `a row keeps tasks_check and tasks_repeat for the journeys`() {
        show(upcoming = listOf(mutuelle), someday = emptyList())

        compose.onNodeWithTag(UiTags.tasksCheck("t2")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.tasksRepeat("t2"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.tasksStockRow("t2")).assertIsDisplayed()
    }

    @Test
    fun `the empty section says Aucune tâche`() {
        show(upcoming = emptyList(), someday = emptyList())

        compose.onNodeWithTag(UiTags.TASKS_EMPTY).assertIsDisplayed().assertTextEquals("Aucune tâche.")
    }
}
