package com.maggie.app.ui.screens.home

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.Routine
import com.maggie.app.data.model.Task
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.home.HomeTasksFakes.Companion.EMPTY_CARD
import com.maggie.app.ui.screens.home.HomeTasksFakes.Companion.TODAY_CARD
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The « Tâches » card as the user sees it (MAG-561): what it draws from the API's answer, a tick in place with the
 * request it makes, what tomorrow forbids, and what opens the Tâches screen. The real ViewModel over faked repositories.
 */
@RunWith(AndroidJUnit4::class)
class TasksCardScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val fakes = HomeTasksFakes()
    private var opened = 0

    private fun show(day: HomeDay = HomeDay.TODAY): TasksCardViewModel {
        coEvery { fakes.taskRepository.toggleDone(any(), any()) } returns Result.success(mockk<Task>(relaxed = true))
        coEvery { fakes.routineRepository.setChecked(any(), any(), any()) } returns Result.success(mockk<Routine>(relaxed = true))
        val viewModel = fakes.viewModel()
        viewModel.show(day)
        compose.setContent { TasksCard(viewModel = viewModel, onOpen = { opened++ }) }
        compose.waitForIdle()
        return viewModel
    }

    @Test
    fun `the ring holds the done count, « n sur N » and the sentence beside it`() {
        show()

        compose.onNodeWithText("Tâches").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_TASKS_RING).assertTextEquals("1 sur 4")
        compose.onNodeWithTag(UiTags.HOME_TASKS_NEXT).assertTextEquals("Le reste peut attendre une pause")
        compose.onNodeWithText("1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Appeler le garage").assertIsDisplayed()
        compose.onNodeWithText("Choisir l'horaire").assertIsDisplayed()
        compose.onNodeWithText("+ 2 autres").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_TASKS_SAGA).assertIsDisplayed()
        compose.onNodeWithText("Déménagement · 2/6").assertIsDisplayed()
        compose.onNodeWithText("En retard de 2 j").assertDoesNotExist()
        compose.onNodeWithText("1 en retard").assertDoesNotExist()
    }

    @Test
    fun `the sentence follows the count none, some, all, tomorrow`() {
        assertEquals("Une seule suffit pour lancer la journée", ringNext(0, 3, tomorrow = false))
        assertEquals("Le reste peut attendre une pause", ringNext(1, 3, tomorrow = false))
        assertEquals("Journée bouclée", ringNext(3, 3, tomorrow = false))
        assertEquals("Prévues demain", ringNext(0, 3, tomorrow = true))
    }

    @Test
    fun `a row reads its when pill, its urgency and its meta under a one-line title`() {
        show()

        compose.onNodeWithTag(UiTags.homeTaskWhen("t1"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("avant ce soir", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(UiTags.homeTaskUrgency("t1"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Critique", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(UiTags.homeTaskMeta("t1"), useUnmergedTree = true).assertTextEquals("reportée depuis lundi")
        compose.onNodeWithTag(UiTags.homeTaskWhen("t2"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the routines are rows under « Routines · après le dîner » with « 1 sur 2 »`() {
        show()

        compose.onNodeWithText("Routines · après le dîner").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_TASKS_ROUTINES_COUNT).assertTextEquals("1 sur 2")
        compose.onNodeWithText("Boire un verre d'eau").assertIsDisplayed()
        compose.onNodeWithText("Vitamines").assertIsDisplayed()
    }

    @Test
    fun `the done rows and the checked routines are drawn ticked, the others not`() {
        show()

        compose.onNodeWithTag(UiTags.homeTaskCheck("t3")).assertIsOn()
        compose.onNodeWithTag(UiTags.homeTaskCheck("t1")).assertIsOff()
        compose.onNodeWithTag(UiTags.homeRoutineCheck("r2")).assertIsOn()
        compose.onNodeWithTag(UiTags.homeRoutineCheck("r1")).assertIsOff()
    }

    @Test
    fun `the box of a task is at least 48 dp wide and tall`() {
        show()

        compose.onNodeWithTag(UiTags.homeTaskCheck("t1")).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun `a subtask is drawn under its task, indented`() {
        show()

        val parent = compose.onNodeWithTag(UiTags.homeTaskCheck("t2")).getUnclippedBoundsInRoot()
        val child = compose.onNodeWithTag(UiTags.homeTaskCheck("t2a")).getUnclippedBoundsInRoot()

        assertTrue(child.left > parent.left)
        assertTrue(child.top > parent.top)
    }

    @Test
    fun `ticking a task strikes it in place, moves the ring, and asks the API`() {
        show()
        fakes.answers(HomeDay.TODAY, TODAY_CARD.withTaskDone("t2", true))

        compose.onNodeWithTag(UiTags.homeTaskCheck("t2")).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.homeTaskCheck("t2")).assertIsOn()
        coVerify(exactly = 1) { fakes.taskRepository.toggleDone("t2", true) }
        assertEquals(0, opened)
    }

    @Test
    fun `ticking a routine checks its row and puts today's date to the API`() {
        show()
        fakes.answers(HomeDay.TODAY, TODAY_CARD.withRoutineChecked("r1", true))

        compose.onNodeWithTag(UiTags.homeRoutineCheck("r1")).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.homeRoutineCheck("r1")).assertIsOn()
        coVerify(exactly = 1) { fakes.routineRepository.setChecked("r1", LocalDate.of(2026, 10, 23), true) }
    }

    @Test
    fun `a tick the API refuses is taken back and said in words`() {
        coEvery { fakes.taskRepository.toggleDone(any(), any()) } returns Result.failure(RuntimeException("HTTP 500"))
        val viewModel = fakes.viewModel()
        viewModel.show(HomeDay.TODAY)
        compose.setContent { TasksCard(viewModel = viewModel, onOpen = {}) }
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.homeTaskCheck("t2")).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.homeTaskCheck("t2")).assertIsOff()
        compose.onNodeWithTag(UiTags.HOME_TASKS_MESSAGE).assertIsDisplayed()
        compose.onNodeWithText(TasksCardViewModel.TASK_FAILED).assertIsDisplayed()
        compose.onNodeWithText("HTTP 500", substring = true).assertDoesNotExist()

        compose.onNodeWithText("Fermer").performClick()
        compose.onNodeWithTag(UiTags.HOME_TASKS_MESSAGE).assertDoesNotExist()
    }

    @Test
    fun `tomorrow draws its rows and chips but nothing can be ticked`() {
        show(HomeDay.TOMORROW)

        compose.onNodeWithText("Rendez-vous dentiste").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.homeTaskCheck("t9")).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.homeRoutineCheck("r1")).assertIsNotEnabled()

        compose.onNodeWithTag(UiTags.homeTaskCheck("t9")).performClick()
        coVerify(exactly = 0) { fakes.taskRepository.toggleDone(any(), any()) }
    }

    @Test
    fun `today's boxes and chips are enabled`() {
        show()

        compose.onNodeWithTag(UiTags.homeTaskCheck("t1")).assertIsEnabled()
        compose.onNodeWithTag(UiTags.homeRoutineCheck("r1")).assertIsEnabled()
    }

    @Test
    fun `the header, a task's title and « + n autres » open the Tâches screen, the box does not`() {
        show()

        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.TASKS)).performClick()
        assertEquals(1, opened)
        compose.onNodeWithText("Réserver le train").performClick()
        assertEquals(2, opened)
        compose.onNodeWithTag(UiTags.HOME_TASKS_MORE).performClick()
        assertEquals(3, opened)
        compose.onNodeWithTag(UiTags.homeTaskCheck("t1")).performClick()
        assertEquals(3, opened)
    }

    @Test
    fun `a card with nothing to say draws nothing`() {
        fakes.answers(HomeDay.TODAY, EMPTY_CARD)
        show()

        compose.onNodeWithTag(UiTags.homeCard(HomeModules.TASKS)).assertDoesNotExist()
    }

    @Test
    fun `without a ring, a « + n autres » or a saga the card shows only what it has`() {
        fakes.answers(HomeDay.TODAY, HomeTasksFakes.TOMORROW_CARD.copy(day = "today", editable = true, total = 0, tasks = emptyList()))
        show()

        compose.onNodeWithTag(UiTags.HOME_TASKS_RING).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.HOME_TASKS_MORE).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.HOME_TASKS_SAGA).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.HOME_TASKS_ROUTINES).assertIsDisplayed()
    }

    @Test
    fun `a failed read says so in words and Réessayer reads again`() {
        coEvery { fakes.cards.read(HomeDay.TODAY, any(), any()) } returns Result.failure(RuntimeException("HTTP 503"))
        show()

        compose.onNodeWithTag(UiTags.HOME_TASKS_ERROR).assertIsDisplayed()
        compose.onNodeWithText(TasksCardViewModel.READ_FAILED).assertIsDisplayed()
        compose.onNodeWithText("HTTP 503", substring = true).assertDoesNotExist()

        fakes.answers(HomeDay.TODAY, HomeTasksFakes.TODAY_CARD)
        compose.onNodeWithTag(UiTags.HOME_TASKS_RETRY).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Appeler le garage").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_TASKS_ERROR).assertDoesNotExist()
    }
}
