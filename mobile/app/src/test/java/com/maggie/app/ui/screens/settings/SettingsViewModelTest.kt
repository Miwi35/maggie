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
        orderWeights = mapOf(
            "timePressure" to mapOf("overdue" to 65, "today" to 55, "later" to 0),
            "criticality" to mapOf("critical" to 30, "low" to 0),
            "priority" to mapOf("high" to 6, "low" to 0),
        ),
        alertSchedule = mapOf(
            "critical" to AlertRule(daysBefore = listOf(3, 1), dueDay = true, overdueEveryDays = 1),
            "high" to AlertRule(daysBefore = listOf(1), dueDay = true, overdueEveryDays = 1),
            "normal" to AlertRule(daysBefore = emptyList(), dueDay = true, overdueEveryDays = 2),
        ),
        morningTime = "08:00",
    )

    private fun taskSettings(values: TaskSettingsValues = defaultValues) = TaskSettings(
        id = "ts1",
        orderWeights = values.orderWeights,
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
    }

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

    // --- Réglages › Tâches (MAG-407) ---

    @Test
    fun `the task settings load into the state and fill the form`() = runTest {
        val gate = CompletableDeferred<TaskSettings>()
        coEvery { apiService.getTaskSettings() } coAnswers { gate.await() }
        val viewModel = viewModel()
        runCurrent()

        assertTrue(viewModel.uiState.value.tasks.isLoading)
        assertNull(viewModel.uiState.value.tasks.form)

        gate.complete(taskSettings(ownValues))
        advanceUntilIdle()

        val tasks = viewModel.uiState.value.tasks
        assertFalse(tasks.isLoading)
        assertEquals("07:30", tasks.form?.morningTime)
        assertEquals(AlertRuleForm(daysBefore = "1", dueDay = false, overdueEveryDays = ""), tasks.form?.alertSchedule?.get("critical"))
        assertEquals("3, 1", TaskSettingsForm.from(tasks.settings!!.defaults).alertSchedule["critical"]?.daysBefore)
        assertEquals("65", tasks.form?.orderWeights?.get("timePressure")?.get("overdue"))
    }

    @Test
    fun `task settings that cannot be read say so, apart from the profile`() = runTest {
        coEvery { apiService.getTaskSettings() } throws IllegalStateException("offline")
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("offline", state.tasks.error)
        assertFalse(state.tasks.isLoading)
        assertNull(state.tasks.form)
        assertNull(state.error)
    }

    @Test
    fun `an edit changes the form and sends nothing`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.editTaskSettings { it.copy(morningTime = "06:45") }
        viewModel.editTaskSettings { form -> form.withAlert("high") { it.copy(daysBefore = "2, 1") } }
        viewModel.editTaskSettings { it.withWeight("priority", "high", "9") }

        val tasks = viewModel.uiState.value.tasks
        assertEquals("06:45", tasks.form?.morningTime)
        assertEquals("2, 1", tasks.form?.alertSchedule?.get("high")?.daysBefore)
        assertEquals("9", tasks.form?.orderWeights?.get("priority")?.get("high"))
        assertEquals("07:30", tasks.settings?.morningTime)
        coVerify(exactly = 0) { apiService.updateTaskSettings(any()) }
    }

    @Test
    fun `saving sends the whole form and takes the server's answer`() = runTest {
        // The server sorts the days from the furthest and drops the repeats.
        val kept = ownValues.copy(
            morningTime = "06:45",
            alertSchedule = ownValues.alertSchedule +
                ("high" to AlertRule(daysBefore = listOf(3, 1), dueDay = true, overdueEveryDays = null)),
        )
        val answer = CompletableDeferred<TaskSettings>()
        coEvery { apiService.updateTaskSettings(any()) } coAnswers { answer.await() }
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.editTaskSettings { it.copy(morningTime = "06:45") }
        viewModel.editTaskSettings { form -> form.withAlert("high") { it.copy(daysBefore = "1, 3, 1", overdueEveryDays = " ") } }
        viewModel.saveTaskSettings()
        runCurrent()
        assertTrue(viewModel.uiState.value.tasks.isSaving)
        answer.complete(taskSettings(kept))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            apiService.updateTaskSettings(
                ownValues.copy(
                    morningTime = "06:45",
                    alertSchedule = ownValues.alertSchedule +
                        ("high" to AlertRule(daysBefore = listOf(1, 3, 1), dueDay = true, overdueEveryDays = null)),
                ),
            )
        }
        val tasks = viewModel.uiState.value.tasks
        assertFalse(tasks.isSaving)
        assertTrue(tasks.saved)
        assertNull(tasks.error)
        assertEquals("3, 1", tasks.form?.alertSchedule?.get("high")?.daysBefore)
        assertEquals("06:45", tasks.settings?.morningTime)
    }

    @Test
    fun `a refused save shows the server's reason and keeps the edit`() = runTest {
        coEvery { apiService.updateTaskSettings(any()) } throws
            ApiRefusalException(400, "alertSchedule.critical.daysBefore must be a list of whole numbers from 1 to 30.")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.editTaskSettings { form -> form.withAlert("critical") { it.copy(daysBefore = "30") } }
        viewModel.saveTaskSettings()
        advanceUntilIdle()

        val tasks = viewModel.uiState.value.tasks
        assertEquals("alertSchedule.critical.daysBefore must be a list of whole numbers from 1 to 30.", tasks.error)
        assertFalse(tasks.isSaving)
        assertFalse(tasks.saved)
        assertEquals("30", tasks.form?.alertSchedule?.get("critical")?.daysBefore)
        assertEquals(listOf(1), tasks.settings?.alertSchedule?.get("critical")?.daysBefore)
    }

    @Test
    fun `a form that does not read is not sent and says which field`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.editTaskSettings { it.copy(morningTime = "7h30") }
        viewModel.saveTaskSettings()
        advanceUntilIdle()
        assertEquals("Heure du matin : au format HH:MM, par exemple 08:00.", viewModel.uiState.value.tasks.error)

        viewModel.editTaskSettings { form -> form.copy(morningTime = "07:30").withAlert("critical") { it.copy(daysBefore = "3, demain") } }
        viewModel.saveTaskSettings()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.tasks.error!!.startsWith("Critique, jours avant l'échéance"))
        assertEquals("3, demain", viewModel.uiState.value.tasks.form?.alertSchedule?.get("critical")?.daysBefore)

        coVerify(exactly = 0) { apiService.updateTaskSettings(any()) }
    }

    @Test
    fun `the defaults fill the form without saving`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.resetTaskSettingsToDefaults()

        val tasks = viewModel.uiState.value.tasks
        assertEquals(TaskSettingsForm.from(defaultValues), tasks.form)
        assertEquals("07:30", tasks.settings?.morningTime)
        coVerify(exactly = 0) { apiService.updateTaskSettings(any()) }
    }

    @Test
    fun `a change published on the task settings topic reads them again`() = runTest {
        val updates = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.TASK_SETTINGS)) } returns updates
        val viewModel = viewModel()
        advanceUntilIdle()
        runCurrent()

        coEvery { apiService.getTaskSettings() } returns taskSettings(ownValues.copy(morningTime = "09:15"))
        updates.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        val tasks = viewModel.uiState.value.tasks
        assertEquals("09:15", tasks.settings?.morningTime)
        assertEquals("09:15", tasks.form?.morningTime)
        coVerify(exactly = 2) { apiService.getTaskSettings() }
    }

    @Test
    fun `a change published while the user edits keeps the edit`() = runTest {
        val updates = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.TASK_SETTINGS)) } returns updates
        val viewModel = viewModel()
        advanceUntilIdle()
        runCurrent()

        viewModel.editTaskSettings { it.copy(morningTime = "06:00") }
        coEvery { apiService.getTaskSettings() } returns taskSettings(ownValues.copy(morningTime = "09:15"))
        updates.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        val tasks = viewModel.uiState.value.tasks
        assertEquals("09:15", tasks.settings?.morningTime)
        assertEquals("06:00", tasks.form?.morningTime)
    }
}
