package com.maggie.app.ui.screens.bilan

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import com.maggie.app.data.repository.ActivityStatusRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** « Reprendre » (MAG-751): offered while a bilan of today is open, gone from the next morning. */
@OptIn(ExperimentalCoroutinesApi::class)
class BilanResumeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private var now = Instant.parse("2026-11-08T20:40:00Z")
    private val mercure = mockk<MercureService> { every { subscribe(any()) } returns MutableSharedFlow<MercureEvent>() }
    private val auth = mockk<AuthRepository> { coEvery { getUserId() } returns "u-1" }
    private val repository = mockk<ActivityStatusRepository>()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun serve(day: String, step: String) {
        coEvery { repository.get(BILAN_ACTIVITY) } returns Result.success(
            ActivityStatus("bilan", ActivityOrigin("day", day), Json.parseToJsonElement("""{"step":"$step"}""").jsonObject, "", ""),
        )
    }

    private fun TestScope.resume(): BilanSession? {
        val vm = BilanResumeViewModel(repository, mercure, auth) { now }
        vm.resume.onEach { }.launchIn(backgroundScope)
        advanceUntilIdle()
        return vm.resume.value
    }

    @Test
    fun `a bilan of today is resumed at its step`() = runTest(testDispatcher) {
        serve("2026-11-08", "finance")
        assertEquals(BilanStep.FINANCE, resume()?.step)
    }

    @Test
    fun `the next morning there is no Reprendre`() = runTest(testDispatcher) {
        serve("2026-11-08", "finance")
        now = Instant.parse("2026-11-09T06:00:00Z")
        assertNull(resume())
    }

    @Test
    fun `nothing in progress, nothing to resume`() = runTest(testDispatcher) {
        coEvery { repository.get(BILAN_ACTIVITY) } returns Result.success(null)
        assertNull(resume())
    }
}
