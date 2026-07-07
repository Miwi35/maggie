package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.AccountCreateRequest
import com.maggie.app.data.model.Account
import com.maggie.app.data.repository.AccountRepository
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
class AccountViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var accountRepository: AccountRepository
    private lateinit var viewModel: AccountViewModel

    private val sampleAccounts = listOf(
        Account(id = "acc-1", name = "Compte courant", type = "checking", balanceCents = 125000),
        Account(id = "acc-2", name = "Livret A", type = "savings", balanceCents = 500000, isCushion = true),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        accountRepository = mockk()
        coEvery { accountRepository.getAccounts() } returns Result.success(sampleAccounts)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load populates accounts sorted by name`() = runTest {
        viewModel = AccountViewModel(accountRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.accounts.size)
        assertEquals("Compte courant", state.accounts[0].name)
        assertEquals("Livret A", state.accounts[1].name)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `createAccount dispatches request and refreshes`() = runTest {
        coEvery { accountRepository.createAccount(any()) } returns Result.success(
            Account(id = "acc-3", name = "Espèces", type = "cash"),
        )
        viewModel = AccountViewModel(accountRepository)
        advanceUntilIdle()

        viewModel.createAccount(AccountCreateRequest(name = "Espèces", type = "cash"))
        advanceUntilIdle()

        coVerify { accountRepository.createAccount(any()) }
        coVerify(atLeast = 2) { accountRepository.getAccounts() }
    }

    @Test
    fun `deleteAccount removes it from state`() = runTest {
        coEvery { accountRepository.deleteAccount("acc-1") } returns Result.success(Unit)
        viewModel = AccountViewModel(accountRepository)
        advanceUntilIdle()

        viewModel.deleteAccount("acc-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.accounts.size)
        assertEquals("acc-2", state.accounts[0].id)
    }

    @Test
    fun `load failure sets error`() = runTest {
        coEvery { accountRepository.getAccounts() } returns Result.failure(RuntimeException("boom"))
        viewModel = AccountViewModel(accountRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("boom", state.error)
        assertFalse(state.isLoading)
    }
}
