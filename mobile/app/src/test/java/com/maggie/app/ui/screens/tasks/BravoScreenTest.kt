package com.maggie.app.ui.screens.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DaySlot
import com.maggie.app.data.model.DayTask
import com.maggie.app.data.model.Reward
import com.maggie.app.data.model.Stars
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.reward.Celebration
import com.maggie.app.data.reward.TaskRewards
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The Bravo (MAG-508) over the real ViewModel and a faked repository: the circle, « {titre} · faite »,
 * the message, « 3 sur 5 aujourd'hui », « Retour à ma journée »; the étoile filante apart.
 */
@RunWith(AndroidJUnit4::class)
class BravoScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val today = LocalDate.of(2026, 10, 15)
    private val repository = mockk<TaskRepository>()
    private var backs = 0

    private val reward = Reward(
        id = "rw1",
        subjectType = "task",
        subjectId = "d3",
        level = "standard",
        messageKey = "direct.4",
        message = "Faite. Suivante.",
        star = true,
        earnedAt = "2026-10-15T08:00:00+00:00",
    )

    private var stars: Result<Stars> = Result.success(Stars(month = "2026-10", count = 5, total = 12))

    private fun day(doneCount: Int): DayPlan {
        val tasks = listOf(
            DayTask(id = "d1", title = "Arroser"),
            DayTask(id = "d2", title = "Sortir les poubelles"),
            DayTask(id = "d3", title = "Renvoyer le formulaire"),
            DayTask(id = "d4", title = "Appeler le garage", estimatedMinutes = 10),
            DayTask(id = "d5", title = "Acheter du pain"),
        ).mapIndexed { i, task -> if (i < doneCount) task.copy(completedAt = "2026-10-15T08:00:00Z") else task }
        val slots = tasks.mapIndexed { i, task -> DaySlot(position = i + 1, task = task) }
        return DayPlan(id = "day", date = today.toString(), slots = slots, now = slots.firstOrNull { !it.task.isDone }, done = doneCount, total = 5)
    }

    private fun show(doneCount: Int = 3) {
        coEvery { repository.getTaskReward("d3") } returns Result.success(reward)
        coEvery { repository.getTaskSettings() } returns Result.failure(IllegalStateException("offline"))
        coEvery { repository.getDayPlan(today) } returns Result.success(day(doneCount))
        coEvery { repository.getStars() } returns stars
        val viewModel = BravoViewModel(repository, TaskRewards(repository, mockk(relaxed = true)), today = { today })
        viewModel.open(BravoRequest("d3", "Renvoyer le formulaire"))
        compose.setContent { BravoScreen(viewModel = viewModel, onBack = { backs++ }) }
        compose.waitForIdle()
    }

    @Test
    fun `shows the circle, the task done, the message, the day's bar and nothing about a next task`() {
        show()

        compose.onNodeWithTag(UiTags.BRAVO_CIRCLE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BRAVO_TITLE).assertTextEquals("Renvoyer le formulaire · faite")
        compose.onNodeWithTag(UiTags.BRAVO_MESSAGE).assertTextEquals("Faite. Suivante.")
        compose.onNodeWithTag(UiTags.BRAVO_DAY_BAR).assertIsDisplayed()
        compose.onNodeWithText("3 sur 5 aujourd'hui").assertIsDisplayed()
        compose.onNodeWithText("Suivante : ", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Pause méritée").assertDoesNotExist()
        compose.onNodeWithTag(UiTags.BRAVO_SHOOTING_STAR).assertDoesNotExist()
    }

    @Test
    fun `the month's constellation sits under the message, counting the new star (MAG-509)`() {
        show()

        compose.onNodeWithTag(UiTags.CONSTELLATION_TITLE).assertTextEquals("Ta constellation d'octobre · 5 étoiles")
        compose.onNodeWithTag(UiTags.CONSTELLATION_LATEST, useUnmergedTree = true).assertExists()
        assertEquals(4, compose.onAllNodesWithTag(UiTags.CONSTELLATION_STAR, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag(UiTags.CONSTELLATION_MORE).assertDoesNotExist()
    }

    @Test
    fun `a constellation that cannot be read leaves the Bravo whole (MAG-509)`() {
        stars = Result.failure(IllegalStateException("offline"))
        show()

        compose.onNodeWithTag(UiTags.CONSTELLATION).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.BRAVO_MESSAGE).assertTextEquals("Faite. Suivante.")
        compose.onNodeWithTag(UiTags.BRAVO_DAY_BAR).assertIsDisplayed()
    }

    @Test
    fun `Retour à ma journée is the only button, and goes back to the day`() {
        show()

        compose.onNodeWithText("Retour à ma journée").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BRAVO_BACK).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(1, backs)
    }

    @Test
    fun `the day done, Retour à ma journée is still the way out`() {
        show(doneCount = 5)

        compose.onNodeWithText("5 sur 5 aujourd'hui").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BRAVO_BACK).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an étoile filante crosses the screen with its message, and stays still when nothing may move`() {
        val shooting = reward.copy(level = "surprise", surprise = "fullDay", message = "Étoile filante : journée complète.")
        val state = BravoUiState(
            taskId = "d3",
            title = "Renvoyer le formulaire",
            celebration = Celebration(shooting, animate = true),
            day = day(5),
        )
        var animate by mutableStateOf(true)
        compose.setContent { BravoContent(state = state, animate = animate, onBack = {}) }

        compose.onNodeWithTag(UiTags.BRAVO_SHOOTING_STAR).assertExists()
        compose.onNodeWithTag(UiTags.BRAVO_MESSAGE).assertTextEquals("Étoile filante : journée complète.")

        animate = false
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.BRAVO_SHOOTING_STAR).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BRAVO_CIRCLE).assertIsDisplayed()
    }
}
