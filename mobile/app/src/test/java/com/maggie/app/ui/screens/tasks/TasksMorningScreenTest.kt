package com.maggie.app.ui.screens.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DayProposal
import com.maggie.app.data.model.DayTask
import com.maggie.app.data.model.ProposalEntry
import com.maggie.app.data.model.ProposalSlot
import com.maggie.app.data.model.TaskView
import com.maggie.app.data.model.TaskViewResponse
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.reward.TaskRewards
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

/**
 * « Ta journée » (MAG-498) over the real ViewModel and a faked repository: it opens by itself on an
 * unconfirmed day, shows the five numbered with why and minutes, the time against the free time and
 * the reserve in a line; a swap takes the next task, « C'est parti » writes the five.
 */
@RunWith(AndroidJUnit4::class)
class TasksMorningScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val today = LocalDate.of(2026, 10, 15)

    private val form = DayTask(id = "d1", title = "Renvoyer le formulaire de la mutuelle", estimatedMinutes = 10)
    private val tax = DayTask(id = "d2", title = "Payer l'impôt")
    private val photos = DayTask(id = "r1", title = "Trier les photos de vacances")
    private val books = DayTask(id = "r2", title = "Ranger les livres")

    private val proposal = DayProposal(
        date = today.toString(),
        slots = listOf(
            ProposalSlot(position = 1, reason = "postponed", minutes = 10, task = form),
            ProposalSlot(position = 2, reason = "urgent", minutes = 15, task = tax),
        ),
        reserveRanked = listOf(
            ProposalEntry(reason = "quick", minutes = 5, task = photos),
            ProposalEntry(reason = "whenever", minutes = 15, task = books),
        ),
        totalMinutes = 25,
        freeMinutes = 150,
    )

    private lateinit var repository: TaskRepository

    private fun show() {
        val day = DayPlan(id = "day", date = today.toString())
        repository = mockk()
        coEvery { repository.getDayPlan(today) } returns Result.success(day)
        coEvery { repository.getTaskView(TaskView.TODAY, null) } returns Result.success(TaskViewResponse())
        coEvery { repository.getMorningTime() } returns Result.success("00:00")
        coEvery { repository.getDayProposal(today) } returns Result.success(proposal)
        coEvery { repository.setDaySlots(today, any()) } returns Result.success(day.copy(confirmedAt = "2026-10-15T08:00:00Z"))
        val auth = mockk<AuthRepository>()
        every { auth.token } returns flowOf("jwt")
        coEvery { auth.getUserId() } returns "u1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        val viewModel = TasksViewModel(repository, mercure, auth, TaskRewards(repository, mockk(relaxed = true)), offlineRoutines(), today = { today })
        compose.setContent { TasksScreen(viewModel = viewModel, onTaskClick = {}) }
        compose.waitForIdle()
    }

    @Test
    fun `it opens by itself with the five numbered, why and minutes on each row`() {
        show()

        compose.onNodeWithTag(UiTags.TASKS_MORNING).assertIsDisplayed()
        assertTopToBottom(
            "slot 1" to compose.onNodeWithTag(UiTags.tasksMorningSlot(1)),
            "slot 2" to compose.onNodeWithTag(UiTags.tasksMorningSlot(2)),
        )
        compose.onNodeWithText("Renvoyer le formulaire de la mutuelle").assertIsDisplayed()
        compose.onNodeWithText("reportée").assertIsDisplayed()
        compose.onNodeWithText("urgent").assertIsDisplayed()
        compose.onNodeWithText("10 min").assertIsDisplayed()
    }

    @Test
    fun `the time is said against the free time, and the reserve fits in a line`() {
        show()

        compose.onNodeWithTag(UiTags.TASKS_MORNING_TIME).assertIsDisplayed()
        compose.onNodeWithContentDescription("≈ 25 min · 2 h 30 libres").assertIsDisplayed()
        compose.onNodeWithText("Ensuite : Trier les photos de vacances · Ranger les livres").assertIsDisplayed()
    }

    @Test
    fun `a swap takes the next task of the reserve in the same position`() {
        show()

        compose.onNodeWithTag(UiTags.tasksMorningSwap(2)).performClick()

        compose.onNodeWithText("Trier les photos de vacances").assertIsDisplayed()
        compose.onNodeWithText("Payer l'impôt", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("≈ 15 min · 2 h 30 libres").assertIsDisplayed()
    }

    @Test
    fun `« C'est parti » writes the kept tasks and gives way to the day`() {
        show()

        compose.onNodeWithTag(UiTags.tasksMorningSwap(1)).performClick()
        compose.onNodeWithTag(UiTags.TASKS_MORNING_GO).performClick()
        compose.waitForIdle()

        coVerify { repository.setDaySlots(today, listOf("r1", "d2")) }
        compose.onNodeWithTag(UiTags.TASKS_MORNING).assertDoesNotExist()
    }

    @Test
    fun `« Plus tard » closes the morning and writes nothing`() {
        show()

        compose.onNodeWithTag(UiTags.TASKS_MORNING_LATER).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.TASKS_MORNING).assertDoesNotExist()
        coVerify(exactly = 0) { repository.setDaySlots(any(), any()) }
    }
}
