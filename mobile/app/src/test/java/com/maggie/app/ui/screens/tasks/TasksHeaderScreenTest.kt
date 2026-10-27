package com.maggie.app.ui.screens.tasks

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DayTask
import com.maggie.app.data.model.TaskView
import com.maggie.app.data.model.TaskViewCounts
import com.maggie.app.data.model.TaskViewResponse
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.reward.TaskRewards
import com.maggie.app.screentest.ScreenRule
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
 * The header « Tâches › Aujourd'hui ▾ » of the mockup (MAG-626): the current view's name, a menu of
 * the five views with their counts, and no tab row.
 */
@RunWith(AndroidJUnit4::class)
class TasksHeaderScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val today = LocalDate.of(2026, 10, 15)

    private fun show(withRoutines: Boolean = true) {
        val day = DayPlan(id = "day", date = today.toString(), reserve = listOf(DayTask(id = "r1", title = "Trier les photos")))
        val repository = mockk<TaskRepository>()
        coEvery { repository.getDayPlan(today) } returns Result.success(day)
        coEvery { repository.getTaskView(TaskView.TODAY, null) } returns
            Result.success(TaskViewResponse(counts = TaskViewCounts(today = 1, upcoming = 2, someday = 3, done = 4)))
        coEvery { repository.getTaskView(TaskView.UPCOMING, null) } returns
            Result.success(TaskViewResponse(counts = TaskViewCounts(today = 1, upcoming = 2, someday = 3, done = 4)))
        coEvery { repository.getMorningTime() } returns Result.failure(IllegalStateException("offline"))
        coEvery { repository.getEveningTime() } returns Result.failure(IllegalStateException("offline"))
        val auth = mockk<AuthRepository>()
        every { auth.token } returns flowOf("jwt")
        coEvery { auth.getUserId() } returns "u1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        val viewModel = TasksViewModel(repository, mercure, auth, TaskRewards(repository, mockk(relaxed = true)), offlineRoutines(), today = { today })
        compose.setContent {
            TasksScreen(
                viewModel = viewModel,
                onTaskClick = {},
                routines = if (withRoutines) ({ Text("Mes routines") }) else null,
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `the screen opens on the breadcrumb « Tâches › Aujourd'hui » and has no tab row`() {
        show()

        compose.onNodeWithTag(UiTags.TASKS_VIEW_MENU).assertIsDisplayed().assertTextEquals("Tâches › Aujourd'hui")
        compose.onNodeWithTag(UiTags.tasksTab("upcoming")).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.tasksTab(ROUTINES_TAB)).assertDoesNotExist()
    }

    @Test
    fun `the menu lists the five views in order, with the counts the tabs had`() {
        show()

        compose.onNodeWithTag(UiTags.TASKS_VIEW_MENU).performClick()

        compose.onNodeWithTag(UiTags.tasksTab("today")).assertTextContains("Aujourd'hui (0)")
        compose.onNodeWithTag(UiTags.tasksTab(ROUTINES_TAB)).assertTextEquals("Routines")
        compose.onNodeWithTag(UiTags.tasksTab("upcoming")).assertTextContains("À venir (2)")
        compose.onNodeWithTag(UiTags.tasksTab("someday")).assertTextContains("Un jour (3)")
        compose.onNodeWithTag(UiTags.tasksTab("done")).assertTextContains("Faites (4)")
    }

    @Test
    fun `without routines the menu has the four views only`() {
        show(withRoutines = false)

        compose.onNodeWithTag(UiTags.TASKS_VIEW_MENU).performClick()

        compose.onNodeWithTag(UiTags.tasksTab("upcoming")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.tasksTab(ROUTINES_TAB)).assertDoesNotExist()
    }

    @Test
    fun `picking « À venir » selects the view and the header follows`() {
        show()

        compose.onNodeWithTag(UiTags.TASKS_VIEW_MENU).performClick()
        compose.onNodeWithTag(UiTags.tasksTab("upcoming")).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.TASKS_VIEW_MENU).assertTextEquals("Tâches › À venir")
        compose.onNodeWithTag(UiTags.tasksTab("upcoming")).assertDoesNotExist()
    }

    @Test
    fun `picking « Routines » shows them under the header « Tâches › Routines »`() {
        show()

        compose.onNodeWithTag(UiTags.TASKS_VIEW_MENU).performClick()
        compose.onNodeWithTag(UiTags.tasksTab(ROUTINES_TAB)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.TASKS_VIEW_MENU).assertTextEquals("Tâches › Routines")
        compose.onNodeWithText("Mes routines").assertIsDisplayed()
    }

    @Test
    fun `the day reads « Réserve · 1 » and offers « Ajouter une tâche »`() {
        show()

        compose.onNodeWithText("Réserve · 1").assertIsDisplayed()
        compose.onNodeWithText("Ajouter une tâche").assertIsDisplayed()
    }

    @Test
    fun `a view label is its name, and its count when it has one`() {
        assertEquals("Un jour", TaskView.SOMEDAY.label)
        assertEquals("Faites", tabLabelOf(TaskView.DONE, TasksUiState()))
    }
}
