package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.BankAuthorization
import com.maggie.app.data.model.BankConnection
import com.maggie.app.data.model.BankSyncAccountResult
import com.maggie.app.data.model.BankSyncResult
import com.maggie.app.data.repository.BankConnectionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BankConnectionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: BankConnectionRepository
    private lateinit var viewModel: BankConnectionViewModel

    private val connections = listOf(
        BankConnection(id = "conn-2", bankName = "Mock Bank", status = "active"),
        BankConnection(
            id = "conn-1",
            bankName = "Autre Banque",
            status = "expired",
            needsReconnecting = true,
        ),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        coEvery { repository.getConnections() } returns Result.success(connections)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load lists the connections by bank name`() = runTest {
        viewModel = BankConnectionViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Autre Banque", "Mock Bank"), state.connections.map { it.bankName })
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `a load that fails surfaces the error and stops the spinner`() = runTest {
        coEvery { repository.getConnections() } returns Result.failure(RuntimeException("boom"))
        viewModel = BankConnectionViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("boom", state.error)
        assertFalse(state.isLoading)
    }

    @Test
    fun `a fetch reports what it brought and reads the list again`() = runTest {
        coEvery { repository.sync() } returns Result.success(
            BankSyncResult(
                imported = 2,
                skipped = 1,
                accounts = listOf(BankSyncAccountResult(status = "synced", imported = 2, skipped = 1)),
            ),
        )
        viewModel = BankConnectionViewModel(repository)
        advanceUntilIdle()

        viewModel.sync()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSyncing)
        assertEquals("2 opération(s) importée(s), 1 déjà présente(s).", state.message)
        // The dates and the statuses moved with the fetch.
        coVerify(atLeast = 2) { repository.getConnections() }
    }

    @Test
    fun `a fetch that fails surfaces the error and stops the spinner`() = runTest {
        coEvery { repository.sync() } returns Result.failure(RuntimeException("502"))
        viewModel = BankConnectionViewModel(repository)
        advanceUntilIdle()

        viewModel.sync()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("502", state.error)
        assertFalse(state.isSyncing)
        assertNull(state.message)
    }

    /** Every fetch spends part of the bank's daily allowance. */
    @Test
    fun `a second fetch while one is running is not sent`() = runTest {
        coEvery { repository.sync() } returns Result.success(BankSyncResult())
        viewModel = BankConnectionViewModel(repository)
        advanceUntilIdle()

        viewModel.sync()
        viewModel.sync()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.sync() }
    }

    @Test
    fun `reconnecting hands back the bank's own page to open`() = runTest {
        coEvery { repository.reconnect("conn-1") } returns Result.success(
            BankAuthorization(authorizationUrl = "https://bank.example/consent"),
        )
        viewModel = BankConnectionViewModel(repository)
        advanceUntilIdle()

        viewModel.reconnect("conn-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("https://bank.example/consent", state.authorizationUrl)
        assertEquals(
            "Autorisez l'accès chez votre banque, puis revenez et actualisez.",
            state.message,
        )
        assertNull(state.reconnectingId)
    }

    @Test
    fun `the URL is dropped once the browser has been handed it`() = runTest {
        coEvery { repository.reconnect("conn-1") } returns Result.success(
            BankAuthorization(authorizationUrl = "https://bank.example/consent"),
        )
        viewModel = BankConnectionViewModel(repository)
        advanceUntilIdle()

        viewModel.reconnect("conn-1")
        advanceUntilIdle()
        viewModel.consumeAuthorizationUrl()

        assertNull(viewModel.uiState.value.authorizationUrl)
    }

    @Test
    fun `a reconnection that fails opens nothing and says why`() = runTest {
        coEvery { repository.reconnect("conn-1") } returns Result.failure(RuntimeException("refusé"))
        viewModel = BankConnectionViewModel(repository)
        advanceUntilIdle()

        viewModel.reconnect("conn-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.authorizationUrl)
        assertNull(state.reconnectingId)
        assertEquals("refusé", state.error)
    }

    @Test
    fun `a message and an error are each dropped once shown`() = runTest {
        coEvery { repository.sync() } returns Result.success(BankSyncResult())
        viewModel = BankConnectionViewModel(repository)
        advanceUntilIdle()

        viewModel.sync()
        advanceUntilIdle()
        viewModel.clearMessage()
        viewModel.clearError()

        val state = viewModel.uiState.value
        assertNull(state.message)
        assertNull(state.error)
    }
}
