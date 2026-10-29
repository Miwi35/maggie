package com.maggie.app.ui.screens.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Task
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The Focus (MAG-499) over the real ViewModel and a faked repository: the task, its first step marked
 * « 2 min », the ring at 10:00, the petits pas, and each action's button. `17c-task-focus` runs it on a device.
 */
@RunWith(AndroidJUnit4::class)
class FocusScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val repository = mockk<TaskRepository>()
    private val focused = Task(id = "t1", title = "Appeler le garage", firstStep = "Trouver le numéro")
    private val stepOne = Task(id = "s1", title = "Chercher le numéro", parent = Task.iriOf("t1"), position = "1")
    private val stepTwo = Task(id = "s2", title = "Dire la plaque", parent = Task.iriOf("t1"), position = "2")
    private var exits = 0
    private var bravo: BravoRequest? = null
    private var now = 0L

    private fun show(task: Task = focused) {
        coEvery { repository.fetchTask("t1") } returns Result.success(task)
        coEvery { repository.getSubtasks("t1") } returns Result.success(listOf(stepOne, stepTwo))
        // No tick during the test: the ring is read at the moment it is drawn.
        val viewModel = FocusViewModel(repository, today = { LocalDate.of(2026, 10, 15) }, clock = { now }, tickMs = 3_600_000)
        compose.setContent { FocusScreen(taskId = "t1", viewModel = viewModel, onExit = { exits++ }, onDone = { bravo = it }) }
        compose.waitForIdle()
    }

    @Test
    fun `shows the task, its first step marked 2 min, the ring at 10 00 and the petits pas`() {
        show()

        compose.onNodeWithText("Appeler le garage").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FOCUS_FIRST_STEP).assertIsDisplayed()
        compose.onNodeWithText("2 min").assertIsDisplayed()
        compose.onNodeWithText("Trouver le numéro").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FOCUS_RING).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.FOCUS_START).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.FOCUS_TIMER_TOGGLE).assertIsDisplayed()
        compose.onNodeWithText("Chercher le numéro").assertIsDisplayed()
        compose.onNodeWithText("Dire la plaque").assertIsDisplayed()
    }

    @Test
    fun `a task of 15 minutes shows its duration, and still no ring`() {
        show(focused.copy(estimatedMinutes = 15))

        compose.onNodeWithTag(UiTags.FOCUS_ESTIMATE).assertTextEquals("≈ 15 min")
        compose.onNodeWithTag(UiTags.FOCUS_RING).assertDoesNotExist()
    }

    @Test
    fun `a task with no duration shows no estimate`() {
        show()

        compose.onNodeWithTag(UiTags.FOCUS_ESTIMATE).assertDoesNotExist()
    }

    @Test
    fun `Activer le minuteur shows the ring at the task's duration, and a second press removes it`() {
        show(focused.copy(estimatedMinutes = 15))

        compose.onNodeWithText("Activer le minuteur").performScrollTo().performClick()
        compose.onNodeWithText("Minuteur · 15 min").assertIsDisplayed()
        compose.onNodeWithContentDescription("Minuteur, 15:00 restantes").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FOCUS_START).assertIsDisplayed()

        compose.onNodeWithTag(UiTags.FOCUS_TIMER_TOGGLE).performClick()
        compose.onNodeWithTag(UiTags.FOCUS_RING).assertDoesNotExist()
        compose.onNodeWithText("Activer le minuteur").assertIsDisplayed()
    }

    @Test
    fun `a task with no first step shows none, and never a « Faire avec Maggie »`() {
        show(focused.copy(firstStep = null))

        compose.onNodeWithTag(UiTags.FOCUS_FIRST_STEP).assertDoesNotExist()
        compose.onNodeWithText("Faire avec Maggie", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the timer button goes from start to pause, then to resume`() {
        show()

        compose.onNodeWithTag(UiTags.FOCUS_TIMER_TOGGLE).performClick()
        compose.onNodeWithTag(UiTags.FOCUS_START).performClick()
        compose.onNodeWithTag(UiTags.FOCUS_PAUSE).assertIsDisplayed()

        now = 5_000
        compose.onNodeWithTag(UiTags.FOCUS_PAUSE).performClick()
        compose.onNodeWithTag(UiTags.FOCUS_RESUME).assertIsDisplayed()
        compose.onNodeWithContentDescription("Minuteur en pause, 09:55 restantes").assertIsDisplayed()
    }

    @Test
    fun `ticking a petit pas saves it`() {
        coEvery { repository.toggleDone("s1", true) } returns Result.success(stepOne.copy(completedAt = "2026-10-15T08:00:00Z"))
        show()

        compose.onNodeWithTag(UiTags.focusStep("s1")).performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { repository.toggleDone("s1", true) }
    }

    @Test
    fun `« Je bloque » opens a field to write a petit pas, which is added to the list`() {
        val added = Task(id = "s3", title = "Noter l'heure", parent = Task.iriOf("t1"), position = "3")
        coEvery { repository.createSubtask("t1", "Noter l'heure") } returns Result.success(added)
        show()

        compose.onNodeWithTag(UiTags.FOCUS_BLOCKED).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Noter l'heure")
        compose.onNodeWithTag(UiTags.FOCUS_ADD_SUBMIT).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Noter l'heure").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `« C'est fait » ticks the task and leaves the Focus for its Bravo (MAG-508)`() {
        coEvery { repository.toggleDone("t1", true) } returns Result.success(focused.copy(completedAt = "2026-10-15T08:00:00Z"))
        show()

        compose.onNodeWithTag(UiTags.FOCUS_DONE).performScrollTo().performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { repository.toggleDone("t1", true) }
        assertEquals(BravoRequest("t1", "Appeler le garage"), bravo)
        assertEquals(0, exits)
    }

    @Test
    fun `a task that cannot be read offers the way back to the day`() {
        coEvery { repository.fetchTask("t1") } returns Result.failure(RuntimeException("boom"))
        val viewModel = FocusViewModel(repository, clock = { 0L })
        compose.setContent { FocusScreen(taskId = "t1", viewModel = viewModel, onExit = { exits++ }) }
        compose.waitForIdle()

        compose.onNodeWithText("Impossible d'ouvrir la tâche.").assertIsDisplayed()
        compose.onNodeWithText("Retour à la journée").performClick()
        assertEquals(1, exits)
    }
}
