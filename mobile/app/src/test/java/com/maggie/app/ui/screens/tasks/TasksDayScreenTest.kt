package com.maggie.app.ui.screens.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DayTask
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskView
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
 * « Aujourd'hui » as the tasks of the day (MAG-766, mock-up `Tac-Main`), over the real ViewModel and a faked
 * repository: each task with its box and no number, the « quand » and urgency pills, the timer of a timed task
 * only, a task done struck without pills, and no reserve. `17b-tasks-day` plays it on a device.
 */
@RunWith(AndroidJUnit4::class)
class TasksDayScreenTest {

    @get:Rule
    val compose = ScreenRule()

    /** A Thursday. */
    private val today = LocalDate.of(2026, 10, 15)

    private val garage = Task(
        id = "t1",
        title = "Appeler le garagiste",
        scheduledDate = "2026-10-15",
        scheduledMoment = Task.MOMENT_TIME,
        scheduledTime = "10:00",
        urgency = "high",
        estimatedMinutes = 10,
    )
    private val mutuelle = Task(id = "t2", title = "Renvoyer le dossier de la mutuelle", dueDate = "2026-10-15", urgency = "critical")
    private val meditate = Task(
        id = "t3",
        title = "Méditer",
        scheduledDate = "2026-10-15",
        scheduledMoment = Task.MOMENT_EVENING,
        timed = true,
        estimatedMinutes = 20,
        urgency = "normal",
    )
    private val papers = Task(id = "t4", title = "Trier les papiers de la banque", scheduledDate = "2026-10-15", dueDate = "2026-10-23", urgency = "normal")
    private val parcel = Task(id = "t5", title = "Renvoyer le colis", completedAt = "2026-10-15T07:12:00Z", timed = true, estimatedMinutes = 5)
    private val photos = DayTask(id = "r1", title = "Trier les photos de vacances")

    private fun show(
        open: List<Task> = listOf(garage, mutuelle, meditate, papers),
        done: List<Task> = listOf(parcel),
        onTimer: (String) -> Unit = {},
    ): TasksViewModel {
        // The server as the ticks leave it: a task ticked leaves the today view for those done today.
        val ticked = mutableSetOf<String>()
        val repository = mockk<TaskRepository>()
        coEvery { repository.getDayPlan(today) } returns Result.success(DayPlan(id = "day", date = today.toString(), reserve = listOf(photos)))
        coEvery { repository.getTaskView(TaskView.TODAY, null) } answers {
            val left = open.filter { it.id !in ticked }
            Result.success(TaskViewResponse(member = left, totalItems = left.size))
        }
        coEvery { repository.getCompletedBetween(any(), any(), any()) } answers {
            open.filter { it.id in ticked }.map { it.copy(completedAt = "2026-10-15T08:00:00Z") } + done
        }
        coEvery { repository.getEveningTime() } returns Result.failure(IllegalStateException("offline"))
        coEvery { repository.toggleDone(any(), any()) } answers {
            val id = firstArg<String>()
            if (secondArg<Boolean>()) ticked += id else ticked -= id
            Result.success(open.first { it.id == id })
        }
        coEvery { repository.getTaskReward(any()) } returns Result.success(null)
        val auth = mockk<AuthRepository>()
        every { auth.token } returns flowOf("jwt")
        coEvery { auth.getUserId() } returns "u1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        val viewModel = TasksViewModel(repository, mercure, auth, TaskRewards(repository, mockk(relaxed = true)), offlineRoutines(), today = { today })
        compose.setContent { TasksScreen(viewModel = viewModel, onTaskClick = {}, onCreateTask = {}, onTimer = onTimer) }
        compose.waitForIdle()
        return viewModel
    }

    private fun pillOf(taskId: String, text: String) =
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(UiTags.tasksTodayRow(taskId))), useUnmergedTree = true)

    @Test
    fun `every task of the day has its box, with no number, no « C'est parti », no « Plus tard » and no reserve`() {
        show()

        listOf("t1", "t2", "t3", "t4").forEach { compose.onNodeWithTag(UiTags.tasksTodayCheck(it)).performScrollTo().assertIsOff() }
        compose.onNodeWithTag(UiTags.tasksTodayCheck("t5")).performScrollTo().assertIsOn()
        compose.onNodeWithContentDescription("1 sur 5 tâches faites").assertIsDisplayed()
        listOf("C'est parti", "Plus tard", "Maintenant", "Mes 5", "Changer", "Le soir", "1", "2").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithText("Réserve", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the « quand » pill says the time, the moment, a deadline today or another day`() {
        show()

        pillOf("t1", "à 10:00").assertExists()
        pillOf("t3", "ce soir").assertExists()
        pillOf("t2", "avant ce soir").assertExists()
        pillOf("t4", "limite jeu. 23").assertDoesNotExist()
        pillOf("t4", "limite ven. 23").assertExists()
    }

    @Test
    fun `the urgency pill is drawn for Critique and Haute only`() {
        show()

        pillOf("t2", "Critique").assertExists()
        pillOf("t1", "Haute").assertExists()
        compose.onNodeWithTag(UiTags.tasksTodayUrgency("t3"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.tasksTodayUrgency("t4"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Normale", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `only a timed task not done has a timer, which opens its Focus`() {
        val timers = mutableListOf<String>()
        show(onTimer = { timers += it })

        compose.onNodeWithTag(UiTags.tasksTodayTimer("t1")).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.tasksTodayTimer("t5")).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.tasksTodayTimer("t3")).performScrollTo().assertIsDisplayed()
        pillOf("t3", "20 min").assertExists()

        compose.onNodeWithTag(UiTags.tasksTodayTimer("t3")).performClick()

        assertEquals(listOf("t3"), timers)
    }

    @Test
    fun `a task done is struck without pills and says when it was done`() {
        show()

        pillOf("t5", "faite à 9 h 12").assertExists()
        // The star it earned (MAG-509).
        compose.onNode(hasTestTag(UiTags.TASK_STAR) and hasAnyAncestor(hasTestTag(UiTags.tasksTodayRow("t5"))), useUnmergedTree = true).assertExists()
        compose.onNode(hasTestTag(UiTags.TASK_STAR) and hasAnyAncestor(hasTestTag(UiTags.tasksTodayRow("t1"))), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.tasksTodayWhen("t5"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.tasksTodayUrgency("t5"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a task ticked from the list moves the ring`() {
        show()

        compose.onNodeWithTag(UiTags.tasksTodayCheck("t2")).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.tasksTodayCheck("t2")).assertIsOn()
        compose.onNodeWithContentDescription("2 sur 5 tâches faites").assertExists()
    }

    @Test
    fun `a day with no task has no ring and says so, and there is no reserve`() {
        show(open = emptyList(), done = emptyList())

        compose.onNodeWithTag(UiTags.TASKS_RING).assertDoesNotExist()
        compose.onNodeWithText("Rien à faire aujourd'hui.").assertIsDisplayed()
        compose.onNodeWithText("Réserve", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the progress is a card with the ring, the count and its sentence`() {
        show()

        compose.onNodeWithContentDescription("1 sur 5 tâches faites").assertIsDisplayed()
        compose.onNode(hasText("La journée est lancée, à ton rythme.") and hasAnyAncestor(hasTestTag(UiTags.TASKS_RING))).assertIsDisplayed()
        val ring = compose.onNodeWithTag(UiTags.TASKS_RING).getBoundsInRoot()
        val root = compose.onRoot().getBoundsInRoot()
        assertEquals(16.dp, ring.left)
        assertEquals(root.right - 16.dp, ring.right)
    }

    @Test
    fun `the ring's sentence follows the day`() {
        assertEquals("Une seule suffit pour lancer la journée.", ringPhraseOf(0, 3))
        assertEquals("La journée est lancée, à ton rythme.", ringPhraseOf(1, 3))
        assertEquals("Tout est fait pour aujourd’hui.", ringPhraseOf(3, 3))
    }

    @Test
    fun `each task is a card inside the 16 dp margins, its timer within it`() {
        show()

        val root = compose.onRoot().getBoundsInRoot()
        val row = compose.onNodeWithTag(UiTags.tasksTodayRow("t3")).performScrollTo().getBoundsInRoot()
        val timer = compose.onNodeWithTag(UiTags.tasksTodayTimer("t3")).getBoundsInRoot()
        assertEquals(16.dp, row.left)
        assertEquals(root.right - 16.dp, row.right)
        assert(row.bottom - row.top >= 68.dp) { "a card is 68 dp at least, got ${row.bottom - row.top}" }
        assert(timer.right <= row.right - 12.dp) { "the timer stays inside its card: ${timer.right} > ${row.right - 12.dp}" }
    }
}
