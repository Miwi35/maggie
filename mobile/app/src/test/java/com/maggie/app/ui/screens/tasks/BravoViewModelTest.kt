package com.maggie.app.ui.screens.tasks

import com.maggie.app.data.model.AlertRule
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DaySlot
import com.maggie.app.data.model.DayTask
import com.maggie.app.data.model.Reward
import com.maggie.app.data.model.Stars
import com.maggie.app.data.model.TaskSettings
import com.maggie.app.data.model.TaskSettingsValues
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.reward.RewardFeedback
import com.maggie.app.data.reward.TaskRewards
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The Bravo (MAG-508): one test per transition — opened on a task ticked, « Retour à ma journée » — and the buzz and the sound each played only when their switch is on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BravoViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: TaskRepository
    private lateinit var feedback: RewardFeedback
    private val today = LocalDate.of(2026, 10, 15)

    private val ticked = BravoRequest("d1", "Renvoyer le formulaire")
    private val reward = Reward(
        id = "rw1",
        subjectType = "task",
        subjectId = "d1",
        level = "standard",
        messageKey = "doux.1",
        message = "Et une de faite. Doucement mais sûrement.",
        star = true,
        earnedAt = "2026-10-15T08:00:00+00:00",
    )

    private val stars = Stars(
        month = "2026-10",
        count = 5,
        total = 12,
        best = Stars.BestMonth(month = "2026-09", count = 7),
        latestAt = "2026-10-15T08:00:00+00:00",
    )

    private val values = TaskSettingsValues(
        orderLevels = mapOf("timePressure" to 2, "criticality" to 2, "priority" to 2),
        alertSchedule = mapOf("normal" to AlertRule(daysBefore = emptyList(), dueDay = true, overdueEveryDays = 2)),
        morningTime = "08:00",
        eveningTime = "21:00",
        rewardAnimation = true,
        rewardVibration = true,
        rewardSound = true,
        tone = "doux",
        dayMoments = mapOf("wake" to "07:30", "afterBreakfast" to "08:30", "afterLunch" to "13:30", "afterDinner" to "20:00"),
        reminderDailyCap = 6,
        quietStart = "21:00",
        quietEnd = "08:00",
    )

    private fun settings(animation: Boolean = true, vibration: Boolean = true, sound: Boolean = true) = TaskSettings(
        id = "ts1",
        orderLevels = values.orderLevels,
        alertSchedule = values.alertSchedule,
        morningTime = values.morningTime,
        eveningTime = values.eveningTime,
        rewardAnimation = animation,
        rewardVibration = vibration,
        rewardSound = sound,
        tone = values.tone,
        dayMoments = values.dayMoments,
        reminderDailyCap = values.reminderDailyCap,
        quietStart = values.quietStart,
        quietEnd = values.quietEnd,
        defaults = values,
    )

    /** Five slots, the first three done: « Maintenant » is the 4th, 10 minutes. */
    private fun day(doneCount: Int = 3): DayPlan {
        val tasks = listOf(
            DayTask(id = "d1", title = "Renvoyer le formulaire"),
            DayTask(id = "d2", title = "Arroser"),
            DayTask(id = "d3", title = "Sortir les poubelles"),
            DayTask(id = "d4", title = "Appeler le garage", estimatedMinutes = 10),
            DayTask(id = "d5", title = "Acheter du pain"),
        ).mapIndexed { i, task -> if (i < doneCount) task.copy(completedAt = "2026-10-15T08:00:00Z") else task }
        val slots = tasks.mapIndexed { i, task -> DaySlot(position = i + 1, task = task) }
        return DayPlan(
            id = "day",
            date = today.toString(),
            slots = slots,
            now = slots.firstOrNull { !it.task.isDone },
            done = doneCount,
            total = slots.size,
        )
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        feedback = mockk(relaxed = true)
        coEvery { repository.getTaskReward("d1") } returns Result.success(reward)
        coEvery { repository.getTaskSettings() } returns Result.success(settings())
        coEvery { repository.getDayPlan(today) } returns Result.success(day())
        coEvery { repository.getStars() } returns Result.success(stars)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = BravoViewModel(repository, TaskRewards(repository, feedback), today = { today })

    @Test
    fun `opened on a task ticked, it shows the title at once, then the reward, the day's count`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        viewModel.open(ticked)
        assertEquals("Renvoyer le formulaire", viewModel.uiState.value.title)
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(reward, state.celebration?.reward)
        assertTrue(state.celebration!!.animate)
        assertEquals(3, state.day?.done)
        assertEquals(5, state.day?.total)
        assertEquals("3 sur 5 aujourd'hui", dayBarLabelOf(state.day!!.done, state.day!!.total))
    }

    @Test
    fun `the month's constellation is read with the new star in it (MAG-509)`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        viewModel.open(ticked)
        advanceUntilIdle()

        assertEquals(stars, viewModel.uiState.value.stars)
        assertEquals("Ta constellation d'octobre · 5 étoiles", viewModel.uiState.value.stars?.title)
    }

    @Test
    fun `a constellation that cannot be read leaves the rest of the Bravo as it was (MAG-509)`() = runTest(testDispatcher) {
        coEvery { repository.getStars() } returns Result.failure(IllegalStateException("offline"))
        val viewModel = viewModel()

        viewModel.open(ticked)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.stars)
        assertFalse(state.isLoading)
        assertEquals(reward, state.celebration?.reward)
        assertEquals(3, state.day?.done)
    }

    @Test
    fun `the vibration and the sound both on are each played once`() = runTest(testDispatcher) {
        viewModel().open(ticked)
        advanceUntilIdle()

        verify(exactly = 1) { feedback.vibrate() }
        verify(exactly = 1) { feedback.playSound() }
    }

    @Test
    fun `the vibration off, no vibration is asked for, the sound still plays`() = runTest(testDispatcher) {
        coEvery { repository.getTaskSettings() } returns Result.success(settings(vibration = false))

        viewModel().open(ticked)
        advanceUntilIdle()

        verify(exactly = 0) { feedback.vibrate() }
        verify(exactly = 1) { feedback.playSound() }
    }

    @Test
    fun `the sound off, no sound is played, the vibration still comes`() = runTest(testDispatcher) {
        coEvery { repository.getTaskSettings() } returns Result.success(settings(sound = false))

        viewModel().open(ticked)
        advanceUntilIdle()

        verify(exactly = 0) { feedback.playSound() }
        verify(exactly = 1) { feedback.vibrate() }
    }

    @Test
    fun `the animation off reaches the screen`() = runTest(testDispatcher) {
        coEvery { repository.getTaskSettings() } returns Result.success(settings(animation = false))
        val viewModel = viewModel()

        viewModel.open(ticked)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.celebration!!.animate)
    }

    @Test
    fun `settings that cannot be read keep the phone quiet`() = runTest(testDispatcher) {
        coEvery { repository.getTaskSettings() } returns Result.failure(IllegalStateException("offline"))
        val viewModel = viewModel()

        viewModel.open(ticked)
        advanceUntilIdle()

        assertEquals(reward, viewModel.uiState.value.celebration?.reward)
        verify(exactly = 0) { feedback.vibrate() }
        verify(exactly = 0) { feedback.playSound() }
    }

    @Test
    fun `a tick that earned no reward shows the Bravo without a message, and no buzz`() = runTest(testDispatcher) {
        coEvery { repository.getTaskReward("d1") } returns Result.success(null)
        val viewModel = viewModel()

        viewModel.open(ticked)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.celebration)
        assertEquals(3, viewModel.uiState.value.day?.done)
        verify(exactly = 0) { feedback.vibrate() }
    }

    @Test
    fun `opening the Bravo already shown reads nothing again, as a rotation does`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.open(ticked)
        advanceUntilIdle()

        viewModel.open(ticked)
        advanceUntilIdle()

        verify(exactly = 1) { feedback.vibrate() }
    }

    @Test
    fun `Retour à ma journée leaves for the day`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.open(ticked)
        advanceUntilIdle()

        viewModel.back()

        assertTrue(viewModel.uiState.value.leaves)
    }

    @Test
    fun `an answer that comes after the Bravo was closed is dropped`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Result<Reward?>>()
        coEvery { repository.getTaskReward("d1") } coAnswers { gate.await() }
        val viewModel = viewModel()
        viewModel.open(ticked)
        runCurrent()

        viewModel.close()
        gate.complete(Result.success(reward))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.taskId)
        assertNull(viewModel.uiState.value.celebration)
    }
}
