package com.maggie.app.ui.screens.proactions

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Proaction
import com.maggie.app.data.repository.ProactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProactionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: ProactionRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private val token = MutableStateFlow<String?>("jwt")

    private val first = Proaction(
        id = "01A",
        summary = "Rappel école",
        startAt = "2037-01-15T08:30:00+01:00",
        prompt = "Rappelle de déposer Ael à l'école",
    )
    private val second = Proaction(
        id = "01B",
        summary = "Résumé de la semaine",
        startAt = "2037-01-17T08:30:00+01:00",
        prompt = "Prépare le résumé de la semaine",
        executionStatus = Proaction.STATUS_FAILED,
        error = "Le modèle ne répond pas",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        mercureService = mockk()
        every { mercureService.subscribe(any()) } returns emptyFlow()
        authRepository = mockk(relaxed = true)
        every { authRepository.token } returns token
        coEvery { authRepository.getUserId() } returns "u1"
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ProactionViewModel(repository, mercureService, authRepository)

    @Test
    fun `the list is loaded when the screen opens`() = runTest {
        coEvery { repository.getProactions() } returns Result.success(listOf(first, second))

        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.proactions.isEmpty())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("01A", "01B"), state.proactions.map { it.id })
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `a failing read is reported and keeps what was shown`() = runTest {
        coEvery { repository.getProactions() } returns Result.success(listOf(first))
        val viewModel = viewModel()
        advanceUntilIdle()

        coEvery { repository.getProactions() } returns Result.failure(RuntimeException("API down"))
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("API down", state.error)
        assertFalse(state.isLoading)
        assertEquals(listOf("01A"), state.proactions.map { it.id })
    }

    @Test
    fun `a refresh after a failure clears the error and shows the list`() = runTest {
        coEvery { repository.getProactions() } returns Result.failure(RuntimeException("API down"))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals("API down", viewModel.uiState.value.error)

        coEvery { repository.getProactions() } returns Result.success(listOf(second))
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.error)
        assertEquals(listOf("01B"), state.proactions.map { it.id })
        assertEquals("Échouée", state.proactions.single().statusLabel)
    }

    @Test
    fun `a proaction pushed on Mercure refreshes the list`() = runTest {
        val topic = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.PROACTIONS)) } returns topic
        coEvery { repository.getProactions() } returns Result.success(listOf(first))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(listOf("01A"), viewModel.uiState.value.proactions.map { it.id })

        coEvery { repository.getProactions() } returns Result.success(listOf(first, second))
        topic.emit(MercureEvent())
        advanceTimeBy(600)

        assertEquals(listOf("01A", "01B"), viewModel.uiState.value.proactions.map { it.id })
    }

    @Test
    fun `a deletion pushed on Mercure takes the proaction off the list`() = runTest {
        val topic = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.PROACTIONS)) } returns topic
        coEvery { repository.getProactions() } returns Result.success(listOf(first, second))
        val viewModel = viewModel()
        advanceUntilIdle()

        coEvery { repository.getProactions() } returns Result.success(listOf(second))
        topic.emit(MercureEvent())
        advanceTimeBy(600)

        assertEquals(listOf("01B"), viewModel.uiState.value.proactions.map { it.id })
    }

    @Test
    fun `it subscribes to the proactions once signed in, and not before`() = runTest {
        coEvery { repository.getProactions() } returns Result.success(emptyList())
        token.value = null

        viewModel()
        advanceUntilIdle()
        verify(exactly = 0) { mercureService.subscribe(any()) }

        token.value = "jwt"
        advanceUntilIdle()

        verify(exactly = 1) { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.PROACTIONS)) }
        coVerify(exactly = 1) { repository.getProactions() }
    }
}
