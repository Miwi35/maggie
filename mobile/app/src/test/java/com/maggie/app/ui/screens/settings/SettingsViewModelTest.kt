package com.maggie.app.ui.screens.settings

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.User
import com.maggie.app.data.model.UserPreference
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.voice.VoiceManager
import com.maggie.app.voice.WakeWordManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNotNull
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

    private val perso = Agenda(id = "a1", name = "Perso", isDefault = true)
    private val concerts = Agenda(id = "a2", name = "Concerts")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        apiService = mockk()
        authRepository = mockk()
        userPreferenceRepository = mockk()
        agendaRepository = mockk()
        mercureService = mockk()

        coEvery { apiService.getMe() } returns User(id = "u1")
        coEvery { apiService.getTtsVoices() } returns emptyList()
        coEvery { apiService.getTtsVoice() } returns "fr-FR-DeniseNeural"
        every { userPreferenceRepository.preference } returns MutableStateFlow<UserPreference?>(null)
        coEvery { userPreferenceRepository.refresh() } returns Result.failure(IllegalStateException("no preference"))
        coEvery { authRepository.getUserId() } returns "u1"
        coEvery { agendaRepository.getAgendas() } returns listOf(perso, concerts)
        every { mercureService.subscribe(any()) } returns emptyFlow()
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
        mockk<WakeWordManager>(relaxed = true),
    )

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
}
