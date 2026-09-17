package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.CushionConfigRequest
import com.maggie.app.data.model.CushionAccount
import com.maggie.app.data.model.CushionStatus
import com.maggie.app.data.repository.CushionRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class CushionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var cushionRepository: CushionRepository
    private lateinit var viewModel: CushionViewModel

    private val buildingStatus = CushionStatus(
        state = "building",
        targetMonths = 3,
        monthlyNetIncomeCents = 250000,
        targetCents = 750000,
        currentCents = 450000,
        deficitCents = 300000,
        coveragePercent = 60,
        monthsCovered = 1.8,
        rechargeCapCents = 15000,
        monthlyRechargeCents = 15000,
        rechargeMonths = 20,
        isCappedByRechargeCap = true,
        blocksGreenScore = true,
        isConfigured = true,
        accounts = listOf(CushionAccount(id = "acc-1", name = "Livret A", balanceCents = 450000)),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        cushionRepository = mockk()
        coEvery { cushionRepository.getStatus() } returns Result.success(buildingStatus)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load exposes the cushion status`() = runTest {
        viewModel = CushionViewModel(cushionRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(buildingStatus, state.status)
        assertTrue(state.status!!.blocksGreenScore)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `configure keeps the status returned by the API`() = runTest {
        val updated = buildingStatus.copy(targetMonths = 6, targetCents = 1500000)
        coEvery { cushionRepository.configure(any()) } returns Result.success(updated)

        viewModel = CushionViewModel(cushionRepository)
        advanceUntilIdle()

        viewModel.configure(CushionConfigRequest(targetMonths = 6))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(6, state.status?.targetMonths)
        assertEquals("Matelas mis à jour", state.savedMessage)
        assertFalse(state.isSaving)

        coVerify { cushionRepository.configure(CushionConfigRequest(targetMonths = 6)) }

        viewModel.clearSavedMessage()
        assertNull(viewModel.uiState.value.savedMessage)
    }

    @Test
    fun `configure failure surfaces the error and stops the spinner`() = runTest {
        coEvery { cushionRepository.configure(any()) } returns Result.failure(RuntimeException("boom"))

        viewModel = CushionViewModel(cushionRepository)
        advanceUntilIdle()

        viewModel.configure(CushionConfigRequest(targetMonths = 0))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("boom", state.error)
        assertFalse(state.isSaving)
        // The previously loaded status is left alone.
        assertEquals(3, state.status?.targetMonths)
    }

    @Test
    fun `load failure sets error`() = runTest {
        coEvery { cushionRepository.getStatus() } returns Result.failure(RuntimeException("boom"))

        viewModel = CushionViewModel(cushionRepository)
        advanceUntilIdle()

        assertEquals("boom", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.status)
    }
}
