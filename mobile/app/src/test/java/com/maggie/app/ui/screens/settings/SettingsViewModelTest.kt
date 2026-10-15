package com.maggie.app.ui.screens.settings

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.fcm.PushTokenRegistrar
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.AlertRule
import com.maggie.app.data.model.TaskOrderPreviewItem
import com.maggie.app.data.model.TaskSettings
import com.maggie.app.data.model.TaskSettingsValues
import com.maggie.app.data.model.User
import com.maggie.app.data.model.UserPreference
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.voice.VoiceManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var apiService: MaggieApiService
    private lateinit var authRepository: AuthRepository
    private lateinit var userPreferenceRepository: UserPreferenceRepository
    private lateinit var agendaRepository: AgendaRepository
    private lateinit var mercureService: MercureService
    private lateinit var pushTokenRegistrar: PushTokenRegistrar

    private val perso = Agenda(id = "a1", name = "Perso", isDefault = true)
    private val concerts = Agenda(id = "a2", name = "Concerts")

    private val defaultValues = TaskSettingsValues(
        orderLevels = linkedMapOf("timePressure" to 2, "criticality" to 2, "priority" to 2),
        alertSchedule = mapOf(
            "critical" to AlertRule(daysBefore = listOf(3, 1), dueDay = true, overdueEveryDays = 1),
            "high" to AlertRule(daysBefore = listOf(1), dueDay = true, overdueEveryDays = 1),
            "normal" to AlertRule(daysBefore = emptyList(), dueDay = true, overdueEveryDays = 2),
        ),
        morningTime = "08:00",
    )

    private fun taskSettings(values: TaskSettingsValues = defaultValues) = TaskSettings(
        id = "ts1",
        orderLevels = values.orderLevels,
        alertSchedule = values.alertSchedule,
        morningTime = values.morningTime,
        defaults = defaultValues,
    )

    /** The user's own settings: morning at 07:30, a critical task alerted the day before only, never when late. */
    private val ownValues = defaultValues.copy(
        morningTime = "07:30",
        alertSchedule = defaultValues.alertSchedule +
            ("critical" to AlertRule(daysBefore = listOf(1), dueDay = false, overdueEveryDays = null)),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        apiService = mockk()
        authRepository = mockk()
        userPreferenceRepository = mockk()
        agendaRepository = mockk()
        mercureService = mockk()
        pushTokenRegistrar = mockk(relaxed = true)

        coEvery { apiService.getMe() } returns User(id = "u1")
        coEvery { apiService.getTtsVoices() } returns emptyList()
        coEvery { apiService.getTtsVoice() } returns "fr-FR-DeniseNeural"
        coEvery { apiService.getAgentName() } returns "Maggie"
        every { userPreferenceRepository.preference } returns MutableStateFlow<UserPreference?>(null)
        coEvery { userPreferenceRepository.refresh() } returns Result.failure(IllegalStateException("no preference"))
        coEvery { authRepository.getUserId() } returns "u1"
        coEvery { agendaRepository.getAgendas() } returns listOf(perso, concerts)
        every { mercureService.subscribe(any()) } returns emptyFlow()
        coEvery { apiService.getTaskSettings() } returns taskSettings(ownValues)
        coEvery { apiService.getTaskOrderPreview() } returns listOf(preview("t1"), preview("t2"), preview("t3"))
    }

    private fun preview(id: String) = TaskOrderPreviewItem(id = id, title = "Tâche $id", urgency = "high")

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = SettingsViewModel(
        apiService,
        authRepository,
        userPreferenceRepository,
        agendaRepository,
        mercureService,
        mockk<VoiceManager>(relaxed = true),
        pushTokenRegistrar,
    )

    @Test
    fun `the agent settings carry the name the user gave Maggie`() = runTest {
        coEvery { apiService.getAgentName() } returns "Alfred"
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals("Alfred", viewModel.uiState.value.agentName)
    }

    @Test
    fun `the agent settings are called Maggie when the name is blank or unreachable`() = runTest {
        coEvery { apiService.getAgentName() } returns "  "
        val blank = viewModel()
        advanceUntilIdle()
        assertEquals("Maggie", blank.uiState.value.agentName)

        coEvery { apiService.getAgentName() } throws IllegalStateException("agent down")
        val unreachable = viewModel()
        advanceUntilIdle()
        assertEquals("Maggie", unreachable.uiState.value.agentName)
        assertNull(unreachable.uiState.value.error)
    }

    @Test
    fun `loads the agendas with the default one flagged`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("Perso"), viewModel.uiState.value.agendas.filter { it.isDefault }.map { it.name })
    }

    @Test
    fun `choosing a default agenda swaps the flag in the state`() = runTest {
        coEvery { agendaRepository.setDefaultAgenda("a2") } returns
            Result.success(listOf(perso.copy(isDefault = false), concerts.copy(isDefault = true)))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.updateDefaultAgenda("a2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Concerts"), state.agendas.filter { it.isDefault }.map { it.name })
        assertNull(state.error)
    }

    @Test
    fun `a refused default keeps the agendas and reports the error`() = runTest {
        coEvery { agendaRepository.setDefaultAgenda("a2") } returns Result.failure(IllegalStateException("boom"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.updateDefaultAgenda("a2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Perso"), state.agendas.filter { it.isDefault }.map { it.name })
        assertEquals("boom", state.error)
    }

    @Test
    fun `a change published on the agendas topic refreshes the default shown`() = runTest {
        val updates = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.AGENDAS)) } returns updates
        coEvery { agendaRepository.refreshAgendas() } returns
            Result.success(listOf(perso.copy(isDefault = false), concerts.copy(isDefault = true)))
        val viewModel = viewModel()
        advanceUntilIdle()
        runCurrent()

        updates.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals(listOf("Concerts"), viewModel.uiState.value.agendas.filter { it.isDefault }.map { it.name })
        coVerify(exactly = 1) { agendaRepository.refreshAgendas() }
    }

    @Test
    fun `a failed refresh on the agendas topic leaves the agendas as they were`() = runTest {
        val updates = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.AGENDAS)) } returns updates
        coEvery { agendaRepository.refreshAgendas() } returns Result.failure(IllegalStateException("offline"))
        val viewModel = viewModel()
        advanceUntilIdle()
        runCurrent()

        updates.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.agendas.firstOrNull { it.isDefault && it.name == "Perso" })
    }

    @Test
    fun `logging out removes the push token before the credentials are cleared`() = runTest {
        coEvery { authRepository.clear() } returns Unit
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.logout()
        advanceUntilIdle()

        coVerifyOrder {
            pushTokenRegistrar.unregister()
            authRepository.clear()
        }
    }

    // --- Réglages › Tâches and « Ce qui passe en premier » (MAG-407) ---

    private val bodies = mutableListOf<JsonObject>()

    private fun answersWith(values: TaskSettingsValues) {
        coEvery { apiService.updateTaskSettings(capture(bodies)) } returns taskSettings(values)
    }

    private fun alertsOf(urgency: String, rule: AlertRule) = ownValues.copy(alertSchedule = ownValues.alertSchedule + (urgency to rule))

    @Test
    fun `the task settings and the preview load into the state`() = runTest {
        val gate = CompletableDeferred<TaskSettings>()
        coEvery { apiService.getTaskSettings() } coAnswers { gate.await() }
        val viewModel = viewModel()
        runCurrent()

        assertTrue(viewModel.uiState.value.tasks.isLoading)
        assertNull(viewModel.uiState.value.tasks.settings)

        gate.complete(taskSettings(ownValues))
        advanceUntilIdle()

        val tasks = viewModel.uiState.value.tasks
        assertFalse(tasks.isLoading)
        assertEquals("07:30", tasks.settings?.morningTime)
        assertEquals(AlertRule(daysBefore = listOf(1), dueDay = false, overdueEveryDays = null), tasks.settings?.alertSchedule?.get("critical"))
        assertEquals(listOf("t1", "t2", "t3"), tasks.preview.map { it.id })
        assertTrue(tasks.moves.isEmpty())
        assertFalse(tasks.saved)
    }

    @Test
    fun `task settings that cannot be read say so, apart from the profile`() = runTest {
        coEvery { apiService.getTaskSettings() } throws IllegalStateException("offline")
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("offline", state.tasks.error)
        assertFalse(state.tasks.isLoading)
        assertNull(state.tasks.settings)
        assertNull(state.error)
    }

    @Test
    fun `a day chip switched on is shown at once, saved alone, then Enregistré`() = runTest {
        val answer = CompletableDeferred<TaskSettings>()
        coEvery { apiService.updateTaskSettings(capture(bodies)) } coAnswers { answer.await() }
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleAlertDay("critical", 3)
        runCurrent()

        assertEquals(listOf(3, 1), viewModel.uiState.value.tasks.settings?.alertSchedule?.get("critical")?.daysBefore)
        assertFalse(viewModel.uiState.value.tasks.saved)
        assertEquals("""{"alertSchedule":{"critical":{"daysBefore":[3,1]}}}""", bodies.single().toString())

        answer.complete(taskSettings(alertsOf("critical", AlertRule(listOf(3, 1), dueDay = false, overdueEveryDays = null))))
        advanceUntilIdle()

        val tasks = viewModel.uiState.value.tasks
        assertTrue(tasks.saved)
        assertNull(tasks.error)
        assertEquals(listOf(3, 1), tasks.settings?.alertSchedule?.get("critical")?.daysBefore)
    }

    @Test
    fun `a day chip switched off keeps the days no chip shows`() = runTest {
        coEvery { apiService.getTaskSettings() } returns
            taskSettings(alertsOf("high", AlertRule(listOf(5, 3, 1), dueDay = true, overdueEveryDays = 1)))
        answersWith(alertsOf("high", AlertRule(listOf(5, 1), dueDay = true, overdueEveryDays = 1)))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleAlertDay("high", 3)
        advanceUntilIdle()

        assertEquals("""{"alertSchedule":{"high":{"daysBefore":[5,1]}}}""", bodies.single().toString())
        assertEquals(listOf(5, 1), viewModel.uiState.value.tasks.settings?.alertSchedule?.get("high")?.daysBefore)
    }

    @Test
    fun `the Jour J chip sends the due day alone`() = runTest {
        answersWith(alertsOf("critical", AlertRule(listOf(1), dueDay = true, overdueEveryDays = null)))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleDueDay("critical")
        advanceUntilIdle()

        assertEquals("""{"alertSchedule":{"critical":{"dueDay":true}}}""", bodies.single().toString())
        assertEquals(true, viewModel.uiState.value.tasks.settings?.alertSchedule?.get("critical")?.dueDay)
        assertTrue(viewModel.uiState.value.tasks.saved)
    }

    @Test
    fun `En retard set to Jamais sends an explicit null`() = runTest {
        answersWith(alertsOf("high", AlertRule(listOf(1), dueDay = true, overdueEveryDays = null)))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setOverdueEvery("high", null)
        advanceUntilIdle()

        assertEquals("""{"alertSchedule":{"high":{"overdueEveryDays":null}}}""", bodies.single().toString())
        assertNull(viewModel.uiState.value.tasks.settings?.alertSchedule?.get("high")?.overdueEveryDays)
        assertTrue(viewModel.uiState.value.tasks.saved)
    }

    @Test
    fun `En retard set to every week sends the days`() = runTest {
        answersWith(alertsOf("normal", AlertRule(emptyList(), dueDay = true, overdueEveryDays = 7)))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setOverdueEvery("normal", 7)
        advanceUntilIdle()

        assertEquals("""{"alertSchedule":{"normal":{"overdueEveryDays":7}}}""", bodies.single().toString())
        assertEquals(7, viewModel.uiState.value.tasks.settings?.alertSchedule?.get("normal")?.overdueEveryDays)
    }

    @Test
    fun `the time picked becomes the morning, as HH MM`() = runTest {
        answersWith(ownValues.copy(morningTime = "06:05"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setMorningTime(6, 5)
        advanceUntilIdle()

        assertEquals("""{"morningTime":"06:05"}""", bodies.single().toString())
        assertEquals("06:05", viewModel.uiState.value.tasks.settings?.morningTime)
        assertTrue(viewModel.uiState.value.tasks.saved)
    }

    @Test
    fun `a refused save shows the server's reason and goes back to the server's values`() = runTest {
        coEvery { apiService.updateTaskSettings(any()) } throws
            ApiRefusalException(400, "alertSchedule.critical.daysBefore must be a list of whole numbers from 1 to 30.")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleAlertDay("critical", 7)
        advanceUntilIdle()

        val tasks = viewModel.uiState.value.tasks
        assertEquals("alertSchedule.critical.daysBefore must be a list of whole numbers from 1 to 30.", tasks.error)
        assertFalse(tasks.saved)
        assertEquals(listOf(1), tasks.settings?.alertSchedule?.get("critical")?.daysBefore)
    }

    @Test
    fun `a save that fails otherwise says it could not save, and goes back`() = runTest {
        coEvery { apiService.updateTaskSettings(any()) } throws IllegalStateException("timeout")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setMorningTime(9, 0)
        advanceUntilIdle()

        val tasks = viewModel.uiState.value.tasks
        assertEquals("Impossible d'enregistrer les réglages des tâches", tasks.error)
        assertEquals("07:30", tasks.settings?.morningTime)
        assertFalse(tasks.saved)
    }

    @Test
    fun `the alerts' defaults save the schedule and the morning, not the order`() = runTest {
        answersWith(ownValues.copy(alertSchedule = defaultValues.alertSchedule, morningTime = defaultValues.morningTime))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.resetAlertsToDefaults()
        advanceUntilIdle()

        assertEquals(
            """{"alertSchedule":{"critical":{"daysBefore":[3,1],"dueDay":true,"overdueEveryDays":1},""" +
                """"high":{"daysBefore":[1],"dueDay":true,"overdueEveryDays":1},""" +
                """"normal":{"daysBefore":[],"dueDay":true,"overdueEveryDays":2}},"morningTime":"08:00"}""",
            bodies.single().toString(),
        )
        val tasks = viewModel.uiState.value.tasks
        assertEquals(defaultValues.alertSchedule, tasks.settings?.alertSchedule)
        assertEquals("08:00", tasks.settings?.morningTime)
        assertTrue(tasks.saved)
    }

    @Test
    fun `a slider saves its level, reads the preview again and marks what moved`() = runTest {
        answersWith(ownValues.copy(orderLevels = ownValues.orderLevels + ("priority" to 4)))
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { apiService.getTaskOrderPreview() } returns listOf(preview("t3"), preview("t1"), preview("t2"))

        viewModel.setOrderLevel("priority", 4)
        advanceUntilIdle()

        assertEquals("""{"orderLevels":{"priority":4}}""", bodies.single().toString())
        val tasks = viewModel.uiState.value.tasks
        assertEquals(4, tasks.settings?.orderLevels?.get("priority"))
        assertTrue(tasks.saved)
        assertEquals(listOf("t3", "t1", "t2"), tasks.preview.map { it.id })
        assertEquals(mapOf("t3" to TaskOrderMove.UP, "t1" to TaskOrderMove.DOWN, "t2" to TaskOrderMove.DOWN), tasks.moves)
    }

    @Test
    fun `a slider that changes nothing in the order keeps the arrows shown`() = runTest {
        answersWith(ownValues.copy(orderLevels = ownValues.orderLevels + ("priority" to 4)))
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { apiService.getTaskOrderPreview() } returns listOf(preview("t3"), preview("t1"), preview("t2"))
        viewModel.setOrderLevel("priority", 4)
        advanceUntilIdle()

        answersWith(ownValues.copy(orderLevels = ownValues.orderLevels + mapOf("priority" to 4, "criticality" to 1)))
        viewModel.setOrderLevel("criticality", 1)
        advanceUntilIdle()

        assertEquals(TaskOrderMove.UP, viewModel.uiState.value.tasks.moves["t3"])
        coVerify(exactly = 3) { apiService.getTaskOrderPreview() }
    }

    @Test
    fun `the Mercure echo landing before the save's own refresh keeps the arrows`() = runTest {
        val updates = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.TASK_SETTINGS)) } returns updates
        val answer = CompletableDeferred<TaskSettings>()
        coEvery { apiService.updateTaskSettings(any()) } coAnswers { answer.await() }
        val viewModel = viewModel()
        advanceUntilIdle()
        runCurrent()
        val raised = ownValues.copy(orderLevels = ownValues.orderLevels + ("priority" to 4))
        coEvery { apiService.getTaskSettings() } returns taskSettings(raised)
        coEvery { apiService.getTaskOrderPreview() } returns listOf(preview("t3"), preview("t1"), preview("t2"))

        viewModel.setOrderLevel("priority", 4)
        updates.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()
        assertEquals(TaskOrderMove.UP, viewModel.uiState.value.tasks.moves["t3"])

        answer.complete(taskSettings(raised))
        advanceUntilIdle()

        val tasks = viewModel.uiState.value.tasks
        assertEquals(listOf("t3", "t1", "t2"), tasks.preview.map { it.id })
        assertEquals(mapOf("t3" to TaskOrderMove.UP, "t1" to TaskOrderMove.DOWN, "t2" to TaskOrderMove.DOWN), tasks.moves)
        assertTrue(tasks.saved)
    }

    @Test
    fun `a re-read during a save does not become what a failed save goes back to`() = runTest {
        val updates = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.TASK_SETTINGS)) } returns updates
        val answer = CompletableDeferred<TaskSettings>()
        coEvery { apiService.updateTaskSettings(any()) } coAnswers { answer.await() }
        val viewModel = viewModel()
        advanceUntilIdle()
        runCurrent()

        viewModel.setMorningTime(9, 0)
        coEvery { apiService.getTaskSettings() } returns taskSettings(ownValues.copy(morningTime = "05:00"))
        updates.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()
        assertEquals("09:00", viewModel.uiState.value.tasks.settings?.morningTime)

        answer.completeExceptionally(IllegalStateException("timeout"))
        advanceUntilIdle()

        assertEquals("07:30", viewModel.uiState.value.tasks.settings?.morningTime)
    }

    @Test
    fun `the order's defaults save the levels only and read the preview again`() = runTest {
        coEvery { apiService.getTaskSettings() } returns taskSettings(ownValues.copy(orderLevels = mapOf("timePressure" to 0, "criticality" to 4, "priority" to 1)))
        answersWith(ownValues)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.resetOrderToDefaults()
        advanceUntilIdle()

        assertEquals("""{"orderLevels":{"timePressure":2,"criticality":2,"priority":2}}""", bodies.single().toString())
        assertEquals(defaultValues.orderLevels, viewModel.uiState.value.tasks.settings?.orderLevels)
        coVerify(exactly = 2) { apiService.getTaskOrderPreview() }
    }

    @Test
    fun `a change published on the task settings topic is read and shown with a fresh preview`() = runTest {
        val updates = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.TASK_SETTINGS)) } returns updates
        val viewModel = viewModel()
        advanceUntilIdle()
        runCurrent()

        coEvery { apiService.getTaskSettings() } returns taskSettings(ownValues.copy(morningTime = "09:15"))
        coEvery { apiService.getTaskOrderPreview() } returns listOf(preview("t2"), preview("t1"), preview("t3"))
        updates.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        val tasks = viewModel.uiState.value.tasks
        assertEquals("09:15", tasks.settings?.morningTime)
        assertEquals(listOf("t2", "t1", "t3"), tasks.preview.map { it.id })
        assertEquals(mapOf("t2" to TaskOrderMove.UP, "t1" to TaskOrderMove.DOWN), tasks.moves)
        coVerify(exactly = 2) { apiService.getTaskSettings() }
    }

    @Test
    fun `the echo of a slider's own save keeps its arrows`() = runTest {
        val updates = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.TASK_SETTINGS)) } returns updates
        answersWith(ownValues.copy(orderLevels = ownValues.orderLevels + ("priority" to 4)))
        val viewModel = viewModel()
        advanceUntilIdle()
        runCurrent()
        coEvery { apiService.getTaskOrderPreview() } returns listOf(preview("t3"), preview("t1"), preview("t2"))
        viewModel.setOrderLevel("priority", 4)
        advanceUntilIdle()

        coEvery { apiService.getTaskSettings() } returns taskSettings(ownValues.copy(orderLevels = ownValues.orderLevels + ("priority" to 4)))
        updates.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals(TaskOrderMove.UP, viewModel.uiState.value.tasks.moves["t3"])
        assertEquals(4, viewModel.uiState.value.tasks.settings?.orderLevels?.get("priority"))
    }
}
