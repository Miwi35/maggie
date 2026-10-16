package com.maggie.app.ui.screens.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DayReview
import com.maggie.app.data.model.DaySlot
import com.maggie.app.data.model.DayTask
import com.maggie.app.data.model.TaskView
import com.maggie.app.data.model.TaskViewResponse
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
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
import java.time.LocalTime

/**
 * « Le soir » (MAG-500) over the real ViewModel and a faked repository: it opens by itself after the
 * evening time, « 3 sur 5 » with what is done above what is left, each left task with its three ways
 * out; « Bonne nuit » records what helped and gives way to the day.
 */
@RunWith(AndroidJUnit4::class)
class TasksEveningScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val today = LocalDate.of(2026, 10, 15)
    private val doneAt = "2026-10-15T09:00:00Z"

    private val garage = DayTask(id = "d1", title = "Rappeler le garage", completedAt = doneAt)
    private val mail = DayTask(id = "d2", title = "Trier le courrier", completedAt = doneAt)
    private val plants = DayTask(id = "d3", title = "Arroser les plantes", completedAt = doneAt)
    private val train = DayTask(id = "d4", title = "Réserver le train")
    private val canteen = DayTask(id = "d5", title = "Payer la cantine")

    private lateinit var repository: TaskRepository

    private fun dayOf(date: LocalDate, tasks: List<DayTask>): DayPlan {
        val slots = tasks.mapIndexed { i, task -> DaySlot(position = i + 1, task = task) }
        return DayPlan(id = "day-$date", date = date.toString(), slots = slots, done = slots.count { it.task.isDone }, total = slots.size)
    }

    private fun show(tomorrow: List<DayTask> = emptyList()) {
        val day = dayOf(today, listOf(train, garage, mail, canteen, plants)).copy(confirmedAt = "2026-10-15T06:40:00Z")
        repository = mockk()
        coEvery { repository.getDayPlan(today) } returns Result.success(day)
        coEvery { repository.getDayPlan(today.plusDays(1)) } returns Result.success(dayOf(today.plusDays(1), tomorrow))
        coEvery { repository.getTaskView(TaskView.TODAY, null) } returns Result.success(TaskViewResponse())
        coEvery { repository.getMorningTime() } returns Result.success("08:00")
        coEvery { repository.getEveningTime() } returns Result.success("21:00")
        coEvery { repository.getDayReview(today) } returns Result.success(DayReview(id = "rv", date = today.toString()))
        coEvery { repository.setDaySlots(today.plusDays(1), any(), false) } answers {
            Result.success(dayOf(today.plusDays(1), secondArg<List<String>>().map { id -> listOf(train, canteen).first { it.id == id } }))
        }
        coEvery { repository.saveDayReview(today, any()) } returns
            Result.success(DayReview(id = "rv", date = today.toString(), reviewedAt = "2026-10-15T19:10:00Z"))
        val auth = mockk<AuthRepository>()
        every { auth.token } returns flowOf("jwt")
        coEvery { auth.getUserId() } returns "u1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        val viewModel = TasksViewModel(repository, mercure, auth, today = { today }, now = { LocalTime.of(21, 5) })
        compose.setContent { TasksScreen(viewModel = viewModel, onTaskClick = {}) }
        compose.waitForIdle()
    }

    @Test
    fun `it opens by itself, « 3 sur 5 », what is done above what is left`() {
        show()

        compose.onNodeWithTag(UiTags.TASKS_EVENING).assertIsDisplayed()
        compose.onNodeWithContentDescription("3 sur 5 tâches faites").assertIsDisplayed()
        assertTopToBottom(
            "done" to compose.onNodeWithTag(UiTags.TASKS_EVENING_DONE),
            "train" to compose.onNodeWithTag(UiTags.tasksEveningLeft("d4")),
            "canteen" to compose.onNodeWithTag(UiTags.tasksEveningLeft("d5")),
        )
        compose.onNodeWithText("Rappeler le garage").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.tasksEveningOutcome("d4", "later")).assertIsSelected()
    }

    @Test
    fun `« Demain » writes the task in tomorrow's five`() {
        show()

        compose.onNodeWithTag(UiTags.tasksEveningOutcome("d4", "tomorrow")).performClick()
        compose.waitForIdle()

        coVerify { repository.setDaySlots(today.plusDays(1), listOf("d4"), false) }
        compose.onNodeWithTag(UiTags.tasksEveningOutcome("d4", "tomorrow")).assertIsSelected()
    }

    @Test
    fun `« Demain » is refused when tomorrow already holds five`() {
        show(tomorrow = List(5) { DayTask(id = "o$it", title = "Autre $it") })

        compose.onNodeWithTag(UiTags.tasksEveningOutcome("d4", "tomorrow")).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.tasksEveningOutcome("d4", "later")).assertIsSelected()
    }

    @Test
    fun `« Commencer petit » then « Bonne nuit » records it and gives way to the day`() {
        show()

        compose.onNodeWithTag(UiTags.tasksEveningHelped(DayReview.START_SMALL)).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.TASKS_EVENING_GOOD_NIGHT).performScrollTo().performClick()
        compose.waitForIdle()

        coVerify { repository.saveDayReview(today, listOf("start_small")) }
        compose.onNodeWithTag(UiTags.TASKS_EVENING).assertDoesNotExist()
    }

    @Test
    fun `« Plus tard » closes the evening without recording it`() {
        show()

        compose.onNodeWithTag(UiTags.TASKS_EVENING_LATER).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.TASKS_EVENING).assertDoesNotExist()
        coVerify(exactly = 0) { repository.saveDayReview(any(), any()) }
    }
}
