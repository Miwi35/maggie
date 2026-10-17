package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.ContactBalance
import com.maggie.app.data.model.PeerDebt
import com.maggie.app.data.model.PeerDebtShare
import com.maggie.app.data.model.PeerDebtSummary
import com.maggie.app.data.repository.PeerDebtRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** « Créances et dettes » (MAG-455): what each state of the screen holds. */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerDebtsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var peerDebts: PeerDebtRepository

    private val gite = PeerDebt(
        id = "d-gite",
        `object` = "Gîte de Crozon",
        amountCents = 40000,
        remainingCents = 40000,
        shares = listOf(
            PeerDebtShare("s-1", "c-julie", "Julie", amountCents = 20000, remainingCents = 20000),
            PeerDebtShare("s-2", "c-tom", "Tom", amountCents = 20000, remainingCents = 20000),
        ),
    )
    private val petrol = PeerDebt(
        id = "d-petrol",
        direction = PeerDebt.DIRECTION_I_OWE,
        `object` = "Essence du road trip",
        amountCents = 28000,
        remainingCents = 28000,
        shares = listOf(PeerDebtShare("s-3", "c-tom", "Tom", amountCents = 28000, remainingCents = 28000)),
    )
    private val summary = PeerDebtSummary(
        owedToMeCents = 40000,
        iOweCents = 28000,
        byContact = listOf(
            ContactBalance("c-julie", "Julie", netCents = 20000),
            ContactBalance("c-tom", "Tom", netCents = -8000, compensates = true),
        ),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        peerDebts = mockk()
        coEvery { peerDebts.getSummary() } returns Result.success(summary)
        coEvery { peerDebts.getOverview(status = "open") } returns Result.success(listOf(petrol, gite))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the screen is loading until the server has answered`() = runTest {
        val viewModel = PeerDebtsViewModel(peerDebts)

        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.loaded)
        assertFalse(viewModel.uiState.value.isEmpty)
    }

    @Test
    fun `by person, the server's balances come in its order with the totals`() = runTest {
        val viewModel = PeerDebtsViewModel(peerDebts)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.loaded)
        assertFalse(state.isLoading)
        assertEquals(PeerDebtsTab.BY_PERSON, state.tab)
        assertEquals(40000, state.summary.owedToMeCents)
        assertEquals(28000, state.summary.iOweCents)
        assertEquals(listOf("Julie", "Tom"), state.summary.byContact.map { it.name })
        assertTrue(state.summary.byContact.last().compensates)
        coVerify(exactly = 1) { peerDebts.getOverview(status = "open") }
    }

    @Test
    fun `opening a person lists a line per object, créances and dettes together, and opening them again closes them`() = runTest {
        val viewModel = PeerDebtsViewModel(peerDebts)
        advanceUntilIdle()

        viewModel.toggleContact("c-tom")

        val lines = viewModel.uiState.value.linesOf("c-tom")
        assertEquals(listOf("Gîte de Crozon", "Essence du road trip"), lines.map { it.subject }.sortedByDescending { it == "Gîte de Crozon" })
        assertEquals(setOf(PeerDebt.DIRECTION_OWED_TO_ME, PeerDebt.DIRECTION_I_OWE), lines.map { it.direction }.toSet())
        assertEquals("Tom", viewModel.uiState.value.selectedBalance?.name)

        viewModel.toggleContact("c-tom")
        assertNull(viewModel.uiState.value.selectedContactId)
    }

    @Test
    fun `by object, the debts are listed biggest left first, each with who still owes`() = runTest {
        val viewModel = PeerDebtsViewModel(peerDebts)
        advanceUntilIdle()

        viewModel.selectTab(PeerDebtsTab.BY_OBJECT)

        val state = viewModel.uiState.value
        assertEquals(PeerDebtsTab.BY_OBJECT, state.tab)
        assertEquals(listOf("Gîte de Crozon", "Essence du road trip"), state.debts.map { it.`object` })
        assertEquals(listOf("Julie", "Tom"), state.debts.first().shares.map { it.contactName })

        viewModel.toggleDebt("d-gite")
        assertEquals("d-gite", viewModel.uiState.value.selectedDebt?.id)
    }

    @Test
    fun `a debt with nothing left is not listed`() = runTest {
        coEvery { peerDebts.getOverview(status = "open") } returns Result.success(
            listOf(gite, petrol.copy(remainingCents = 0), gite.copy(id = "d-done", status = "settled")),
        )
        val viewModel = PeerDebtsViewModel(peerDebts)
        advanceUntilIdle()

        assertEquals(listOf("d-gite"), viewModel.uiState.value.debts.map { it.id })
    }

    @Test
    fun `nobody owing anything is an empty screen, not an error`() = runTest {
        coEvery { peerDebts.getSummary() } returns Result.success(PeerDebtSummary())
        coEvery { peerDebts.getOverview(status = "open") } returns Result.success(emptyList())
        val viewModel = PeerDebtsViewModel(peerDebts)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a read that fails says so and a retry loads it`() = runTest {
        coEvery { peerDebts.getSummary() } returns Result.failure(RuntimeException("Hors ligne"))
        val viewModel = PeerDebtsViewModel(peerDebts)
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertFalse(state.loaded)
        assertFalse(state.isLoading)
        assertEquals("Hors ligne", state.error)

        coEvery { peerDebts.getSummary() } returns Result.success(summary)
        viewModel.refresh()
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertTrue(state.loaded)
        assertNull(state.error)
        assertEquals(2, state.summary.byContact.size)
    }

    @Test
    fun `a refresh that fails keeps the figures already shown`() = runTest {
        val viewModel = PeerDebtsViewModel(peerDebts)
        advanceUntilIdle()

        coEvery { peerDebts.getOverview(status = "open") } returns Result.failure(RuntimeException("Hors ligne"))
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.loaded)
        assertEquals("Hors ligne", state.error)
        assertEquals(2, state.summary.byContact.size)

        viewModel.clearError()
        assertNull(viewModel.uiState.value.error)
    }
}
