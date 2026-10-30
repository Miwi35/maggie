package com.maggie.app.ui.components

import com.maggie.app.data.model.ProviderStatus
import com.maggie.app.data.repository.ProviderStatusRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The banner « Maggie ne peut plus répondre » (MAG-727): read on open, again every 5 minutes while down. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderStatusViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<ProviderStatusRepository>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an empty credit shows the recharge banner`() = runTest(dispatcher) {
        coEvery { repository.get() } returns Result.success(ProviderStatus("credit", "2026-10-30T08:00:00Z"))

        val viewModel = ProviderStatusViewModel(repository)
        runCurrent()

        assertEquals(ProviderBanner.Credit, viewModel.banner.value)
        assertEquals("Crédit Anthropic épuisé", viewModel.banner.value.subtitle)
        assertEquals("Recharger", viewModel.banner.value.action)
        assertEquals("https://console.anthropic.com/settings/billing", viewModel.banner.value.url)
    }

    @Test
    fun `a refused key shows the console banner`() = runTest(dispatcher) {
        coEvery { repository.get() } returns Result.success(ProviderStatus("auth", "2026-10-30T08:00:00Z"))

        val viewModel = ProviderStatusViewModel(repository)
        runCurrent()

        assertEquals(ProviderBanner.Auth, viewModel.banner.value)
        assertEquals("Clé Anthropic refusée", viewModel.banner.value.subtitle)
        assertEquals("Ouvrir la console", viewModel.banner.value.action)
        assertEquals("https://console.anthropic.com/settings/keys", viewModel.banner.value.url)
    }

    @Test
    fun `ok shows no banner`() = runTest(dispatcher) {
        coEvery { repository.get() } returns Result.success(ProviderStatus("ok"))

        val viewModel = ProviderStatusViewModel(repository)
        runCurrent()

        assertEquals(ProviderBanner.Ok, viewModel.banner.value)
    }

    @Test
    fun `while down it asks again every five minutes and the banner goes when the state is ok`() = runTest(dispatcher) {
        coEvery { repository.get() } returnsMany listOf(
            Result.success(ProviderStatus("credit", "2026-10-30T08:00:00Z")),
            Result.success(ProviderStatus("ok")),
        )

        val viewModel = ProviderStatusViewModel(repository)
        runCurrent()
        assertEquals(ProviderBanner.Credit, viewModel.banner.value)

        advanceTimeBy(ProviderStatusViewModel.POLL_MS + 1)
        runCurrent()

        assertEquals(ProviderBanner.Ok, viewModel.banner.value)
    }

    @Test
    fun `while ok it does not poll`() = runTest(dispatcher) {
        coEvery { repository.get() } returns Result.success(ProviderStatus("ok"))

        ProviderStatusViewModel(repository)
        runCurrent()
        advanceTimeBy(ProviderStatusViewModel.POLL_MS * 3)
        runCurrent()

        coVerify(exactly = 1) { repository.get() }
    }

    @Test
    fun `a failed read keeps what the banner said`() = runTest(dispatcher) {
        coEvery { repository.get() } returnsMany listOf(
            Result.success(ProviderStatus("credit", "2026-10-30T08:00:00Z")),
            Result.failure(RuntimeException("offline")),
        )

        val viewModel = ProviderStatusViewModel(repository)
        runCurrent()
        viewModel.refresh()
        runCurrent()

        assertEquals(ProviderBanner.Credit, viewModel.banner.value)
    }
}
