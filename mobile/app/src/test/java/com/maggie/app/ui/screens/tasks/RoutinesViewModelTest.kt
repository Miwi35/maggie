package com.maggie.app.ui.screens.tasks

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.AlertRule
import com.maggie.app.data.model.Routine
import com.maggie.app.data.model.RoutineDay
import com.maggie.app.data.model.RoutineRecurrence
import com.maggie.app.data.model.TaskSettings
import com.maggie.app.data.model.TaskSettingsValues
import com.maggie.app.data.repository.RoutineRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.reward.RewardFeedback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The « Routines » tab (MAG-511): one test per transition — read (and refused), ticked, unticked,
 * created, changed, paused, deleted — and the buzz only when the user lets rewards vibrate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutinesViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: RoutineRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var feedback: RewardFeedback
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private val routinePushes = MutableSharedFlow<MercureEvent>()
    private val checkPushes = MutableSharedFlow<MercureEvent>()

    /** A Tuesday. */
    private val today = LocalDate.of(2026, 10, 20)

    private fun routine(
        id: String,
        title: String,
        anchor: String = Routine.ANCHOR_WAKE,
        time: String = "07:30",
        recurrence: RoutineRecurrence = RoutineRecurrence(RoutineRecurrence.DAILY),
        checked: Boolean = false,
        due: Boolean = true,
        active: Boolean = true,
        monthCount: Int = 3,
        totalCount: Int = 9,
    ) = Routine(
        id = id,
        title = title,
        anchor = anchor,
        anchorTime = time.takeIf { anchor == Routine.ANCHOR_TIME },
        time = time,
        recurrence = recurrence,
        active = active,
        remind = false,
        createdAt = "2026-08-03T09:00:00+02:00",
        checkedToday = checked,
        dueToday = due,
        monthCount = monthCount,
        totalCount = totalCount,
        recentDays = (6 downTo 0).map { ago -> RoutineDay(today.minusDays(ago.toLong()).toString(), due = true, checked = ago == 0 && checked) },
    )

    private val water = routine("r1", "Boire un verre d'eau")
    private val bins = routine(
        "r2",
        "Sortir les poubelles",
        anchor = Routine.ANCHOR_AFTER_DINNER,
        time = "20:00",
        recurrence = RoutineRecurrence(RoutineRecurrence.WEEKLY, weekday = 2),
        monthCount = 0,
        totalCount = 0,
    )

    private fun settings(vibration: Boolean): TaskSettings {
        val values = TaskSettingsValues(
            orderLevels = mapOf("timePressure" to 2),
            alertSchedule = mapOf("normal" to AlertRule(daysBefore = emptyList(), dueDay = true, overdueEveryDays = 2)),
            morningTime = "08:00",
            eveningTime = "21:00",
            rewardAnimation = true,
            rewardVibration = vibration,
            rewardSound = true,
            tone = "doux",
            dayMoments = mapOf("wake" to "07:30"),
            reminderDailyCap = 6,
            quietStart = "21:00",
            quietEnd = "08:00",
        )
        return TaskSettings(
            id = "ts1",
            orderLevels = values.orderLevels,
            alertSchedule = values.alertSchedule,
            morningTime = values.morningTime,
            eveningTime = values.eveningTime,
            rewardAnimation = true,
            rewardVibration = vibration,
            rewardSound = true,
            tone = values.tone,
            dayMoments = values.dayMoments,
            reminderDailyCap = values.reminderDailyCap,
            quietStart = values.quietStart,
            quietEnd = values.quietEnd,
            defaults = values,
        )
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        taskRepository = mockk()
        feedback = mockk(relaxed = true)
        mercureService = mockk()
        authRepository = mockk(relaxed = true)
        coEvery { repository.getRoutines() } returns Result.success(listOf(water, bins))
        coEvery { taskRepository.getTaskSettings() } returns Result.success(settings(vibration = true))
        every { authRepository.token } returns flowOf("test-jwt")
        coEvery { authRepository.getUserId() } returns "u1"
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.ROUTINES)) } returns routinePushes
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.ROUTINE_CHECKS)) } returns checkPushes
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(deviceToday: LocalDate = today) =
        RoutinesViewModel(repository, taskRepository, feedback, mercureService, authRepository, today = { deviceToday })

    private fun loaded(): RoutinesViewModel = createViewModel().also { it.refresh() }

    // --- Read ---

    @Test
    fun `opening reads every routine, in the API's order`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("r1", "r2"), state.routines.map { it.id })
        assertFalse(state.isLoading)
        assertNull(state.error)
        viewModel.onClosed()
    }

    @Test
    fun `a list that cannot be read says so, with nothing to show`() = runTest {
        coEvery { repository.getRoutines() } returns Result.failure(IllegalStateException("Hors ligne"))
        val viewModel = loaded()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Hors ligne", state.error)
        assertTrue(state.routines.isEmpty())
        assertFalse(state.isLoading)
    }

    @Test
    fun `a routine or a tick pushed from elsewhere reads the list again`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        coEvery { repository.getRoutines() } returns Result.success(listOf(water))
        checkPushes.emit(MercureEvent())
        advanceUntilIdle()

        assertEquals(listOf("r1"), viewModel.uiState.value.routines.map { it.id })
        viewModel.onClosed()
    }

    // --- Tick ---

    @Test
    fun `ticking shows it at once, then the routine the API answers, and buzzes`() = runTest {
        val answer = CompletableDeferred<Result<Routine>>()
        coEvery { repository.setChecked("r1", today, true) } coAnswers { answer.await() }
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.toggleCheck(water)
        runCurrent()
        val shown = viewModel.uiState.value.routines.first { it.id == "r1" }
        assertTrue(shown.checkedToday)
        assertEquals(4, shown.monthCount)
        assertTrue(shown.recentDays.last().checked)
        assertTrue("r1" in viewModel.uiState.value.pending)

        answer.complete(Result.success(water.copy(checkedToday = true, monthCount = 4, totalCount = 10)))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(10, state.routines.first { it.id == "r1" }.totalCount)
        assertTrue(state.pending.isEmpty())
        verify(exactly = 1) { feedback.vibrate() }
    }

    @Test
    fun `no buzz when the user turned the vibration off`() = runTest {
        coEvery { taskRepository.getTaskSettings() } returns Result.success(settings(vibration = false))
        coEvery { repository.setChecked("r1", today, true) } returns Result.success(water.copy(checkedToday = true))
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.toggleCheck(water)
        advanceUntilIdle()

        verify(exactly = 0) { feedback.vibrate() }
    }

    @Test
    fun `unticking sends the delete and buzzes nothing`() = runTest {
        val done = water.copy(checkedToday = true, monthCount = 4, totalCount = 10)
        coEvery { repository.getRoutines() } returns Result.success(listOf(done, bins))
        coEvery { repository.setChecked("r1", today, false) } returns Result.success(water)
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.toggleCheck(done)
        advanceUntilIdle()

        val shown = viewModel.uiState.value.routines.first { it.id == "r1" }
        assertFalse(shown.checkedToday)
        assertEquals(3, shown.monthCount)
        coVerify(exactly = 1) { repository.setChecked("r1", today, false) }
        verify(exactly = 0) { feedback.vibrate() }
    }

    @Test
    fun `a refused tick is taken back and said`() = runTest {
        coEvery { repository.setChecked("r1", today, true) } returns Result.failure(IllegalStateException("Refusé"))
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.toggleCheck(water)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.routines.first { it.id == "r1" }.checkedToday)
        assertEquals(3, state.routines.first { it.id == "r1" }.monthCount)
        assertEquals("Refusé", state.message)
        assertTrue(state.pending.isEmpty())
    }

    @Test
    fun `the tick is on the API's today, the last of the seven days, not the device's date`() = runTest {
        // Past midnight on the phone, still the 20th in the user's zone.
        coEvery { repository.setChecked("r1", today, true) } returns Result.success(water.copy(checkedToday = true))
        val viewModel = createViewModel(deviceToday = today.plusDays(1)).also { it.refresh() }
        advanceUntilIdle()

        viewModel.toggleCheck(water)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.setChecked("r1", today, true) }
        coVerify(exactly = 0) { repository.setChecked("r1", today.plusDays(1), any()) }
    }

    @Test
    fun `a new routine takes its weekday and date from the API's today`() = runTest {
        val viewModel = createViewModel(deviceToday = today.plusDays(1)).also { it.refresh() }
        advanceUntilIdle()

        viewModel.openNew()

        val draft = viewModel.uiState.value.draft!!
        assertEquals(2, draft.weekday)
        assertEquals(20, draft.monthDay)
    }

    @Test
    fun `a routine not due today cannot be ticked`() = runTest {
        val notDue = bins.copy(dueToday = false)
        coEvery { repository.getRoutines() } returns Result.success(listOf(water, notDue))
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.toggleCheck(notDue)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.setChecked(any(), any(), any()) }
    }

    // --- Create ---

    @Test
    fun `a new routine starts every day at waking, its weekday and day of the month on today`() = runTest {
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.openNew()

        val draft = viewModel.uiState.value.draft
        assertNotNull(draft)
        assertTrue(draft!!.isNew)
        assertEquals(Routine.ANCHOR_WAKE, draft.anchor)
        assertEquals(RoutineRecurrence.DAILY, draft.kind)
        assertEquals(2, draft.weekday)
        assertEquals(20, draft.monthDay)
        assertFalse(draft.remind)
    }

    @Test
    fun `saving a new routine posts every field, closes the sheet and reads the list again`() = runTest {
        val body = slot<JsonObject>()
        val created = routine("r3", "Étirements", anchor = Routine.ANCHOR_TIME, time = "22:30")
        coEvery { repository.createRoutine(capture(body)) } returns Result.success(created)
        val viewModel = loaded()
        advanceUntilIdle()
        coEvery { repository.getRoutines() } returns Result.success(listOf(water, created, bins))

        viewModel.openNew()
        viewModel.updateDraft(
            viewModel.uiState.value.draft!!.copy(
                title = "  Étirements ",
                anchor = Routine.ANCHOR_TIME,
                anchorTime = "22:30",
                kind = RoutineRecurrence.WEEKLY,
                weekday = 2,
                remind = true,
            ),
        )
        viewModel.save()
        advanceUntilIdle()

        val sent = body.captured
        assertEquals("Étirements", sent["title"]!!.jsonPrimitive.content)
        assertEquals("time", sent["anchor"]!!.jsonPrimitive.content)
        assertEquals("22:30", sent["anchorTime"]!!.jsonPrimitive.content)
        val recurrence = sent["recurrence"]!!.jsonObject
        assertEquals("weekly", recurrence["kind"]!!.jsonPrimitive.content)
        assertEquals(2, recurrence["weekday"]!!.jsonPrimitive.int)
        assertNull(recurrence["monthDay"])
        assertTrue(sent["active"]!!.jsonPrimitive.boolean)
        assertTrue(sent["remind"]!!.jsonPrimitive.boolean)
        val state = viewModel.uiState.value
        assertNull(state.draft)
        assertFalse(state.isSaving)
        assertEquals(listOf("r1", "r3", "r2"), state.routines.map { it.id })
    }

    @Test
    fun `a routine at a moment sends no hour`() = runTest {
        val body = slot<JsonObject>()
        coEvery { repository.createRoutine(capture(body)) } returns Result.success(water)
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.openNew()
        viewModel.updateDraft(viewModel.uiState.value.draft!!.copy(title = "Boire", anchorTime = "22:30"))
        viewModel.save()
        advanceUntilIdle()

        assertEquals(JsonNull, body.captured["anchorTime"])
    }

    @Test
    fun `a refused creation keeps the sheet open with the reason`() = runTest {
        coEvery { repository.createRoutine(any()) } returns Result.failure(IllegalStateException("title: trop long"))
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.openNew()
        viewModel.updateDraft(viewModel.uiState.value.draft!!.copy(title = "Boire"))
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.draft)
        assertEquals("title: trop long", state.message)
        assertFalse(state.isSaving)
    }

    @Test
    fun `a routine with no title is not sent`() = runTest {
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.openNew()
        viewModel.save()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createRoutine(any()) }
        assertNotNull(viewModel.uiState.value.draft)
    }

    // --- Change ---

    @Test
    fun `a card opens the sheet on that routine`() = runTest {
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.openEdit(bins)

        val draft = viewModel.uiState.value.draft!!
        assertEquals("r2", draft.id)
        assertEquals("Sortir les poubelles", draft.title)
        assertEquals(Routine.ANCHOR_AFTER_DINNER, draft.anchor)
        assertEquals(RoutineRecurrence.WEEKLY, draft.kind)
        assertEquals(2, draft.weekday)
    }

    @Test
    fun `saving a change sends only what changed and shows the API's routine`() = runTest {
        val body = slot<JsonObject>()
        val moved = bins.copy(recurrence = RoutineRecurrence(RoutineRecurrence.WEEKLY, weekday = 4))
        coEvery { repository.updateRoutine("r2", capture(body)) } returns Result.success(moved)
        val viewModel = loaded()
        advanceUntilIdle()
        coEvery { repository.getRoutines() } returns Result.success(listOf(water, moved))

        viewModel.openEdit(bins)
        viewModel.updateDraft(viewModel.uiState.value.draft!!.copy(weekday = 4))
        viewModel.save()
        advanceUntilIdle()

        assertEquals(setOf("recurrence"), body.captured.keys)
        assertEquals(4, body.captured["recurrence"]!!.jsonObject["weekday"]!!.jsonPrimitive.int)
        val state = viewModel.uiState.value
        assertNull(state.draft)
        assertEquals(4, state.routines.first { it.id == "r2" }.recurrence.weekday)
    }

    @Test
    fun `moving a routine to an hour sends the anchor and the hour together`() = runTest {
        val body = slot<JsonObject>()
        coEvery { repository.updateRoutine("r1", capture(body)) } returns Result.success(water)
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.openEdit(water)
        viewModel.updateDraft(viewModel.uiState.value.draft!!.copy(anchor = Routine.ANCHOR_TIME, anchorTime = "06:45"))
        viewModel.save()
        advanceUntilIdle()

        assertEquals(setOf("anchor", "anchorTime"), body.captured.keys)
        assertEquals("06:45", body.captured["anchorTime"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pausing a routine sends active false and dims it`() = runTest {
        val body = slot<JsonObject>()
        val paused = water.copy(active = false, dueToday = false)
        coEvery { repository.updateRoutine("r1", capture(body)) } returns Result.success(paused)
        val viewModel = loaded()
        advanceUntilIdle()
        coEvery { repository.getRoutines() } returns Result.success(listOf(paused, bins))

        viewModel.openEdit(water)
        viewModel.updateDraft(viewModel.uiState.value.draft!!.copy(active = false))
        viewModel.save()
        advanceUntilIdle()

        assertEquals(setOf("active"), body.captured.keys)
        assertFalse(body.captured["active"]!!.jsonPrimitive.boolean)
        assertFalse(viewModel.uiState.value.routines.first { it.id == "r1" }.active)
    }

    @Test
    fun `saving with nothing changed only closes the sheet`() = runTest {
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.openEdit(water)
        viewModel.save()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateRoutine(any(), any()) }
        assertNull(viewModel.uiState.value.draft)
    }

    // --- Delete ---

    @Test
    fun `deleting removes the routine and closes the sheet`() = runTest {
        coEvery { repository.deleteRoutine("r2") } returns Result.success(Unit)
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.openEdit(bins)
        viewModel.delete()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("r1"), state.routines.map { it.id })
        assertNull(state.draft)
        coVerify(exactly = 1) { repository.deleteRoutine("r2") }
    }

    @Test
    fun `a refused delete keeps the routine and says so`() = runTest {
        coEvery { repository.deleteRoutine("r2") } returns Result.failure(IllegalStateException("Introuvable"))
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.openEdit(bins)
        viewModel.delete()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("r1", "r2"), state.routines.map { it.id })
        assertNotNull(state.draft)
        assertEquals("Introuvable", state.message)
    }
}
