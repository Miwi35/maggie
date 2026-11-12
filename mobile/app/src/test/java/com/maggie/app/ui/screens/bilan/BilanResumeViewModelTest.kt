package com.maggie.app.ui.screens.bilan

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import com.maggie.app.data.model.UserPreference
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** The Bilan tile (MAG-751, MAG-826): « Reprendre » once started, « Ouvrir » from the review time, nothing after « Bonne nuit ». */
@OptIn(ExperimentalCoroutinesApi::class)
class BilanResumeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // Sunday 8 November 2026, 21:40 in Paris: past the default 21:30.
    private var now = Instant.parse("2026-11-08T20:40:00Z")
    private val mercure = mockk<MercureService> { every { subscribe(any()) } returns MutableSharedFlow<MercureEvent>() }
    private val auth = mockk<AuthRepository> { coEvery { getUserId() } returns "u-1" }
    private val repository = mockk<ActivityStatusRepository>()
    private val preferences = MutableStateFlow<UserPreference?>(UserPreference())
    private val preferenceRepository = mockk<UserPreferenceRepository> { every { preference } returns preferences }
    private val doneStore = FakeBilanDoneStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.get(BILAN_ACTIVITY) } returns Result.success(null)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun serve(day: String, step: String) {
        coEvery { repository.get(BILAN_ACTIVITY) } returns Result.success(
            ActivityStatus("bilan", ActivityOrigin("day", day), Json.parseToJsonElement("""{"step":"$step"}""").jsonObject, "", ""),
        )
    }

    private fun TestScope.tile(): BilanTile? {
        val vm = BilanResumeViewModel(repository, mercure, auth, preferenceRepository, doneStore) { now }
        vm.tile.onEach { }.launchIn(backgroundScope)
        advanceUntilIdle()
        return vm.tile.value
    }

    @Test
    fun `a bilan of today is resumed at its step`() = runTest(testDispatcher) {
        serve("2026-11-08", "finance")
        assertEquals(BilanStep.FINANCE, tile()?.session?.step)
    }

    @Test
    fun `the review time reached with no answer, the tile offers Ouvrir`() = runTest(testDispatcher) {
        val shown = tile()
        assertNotNull(shown)
        assertNull(shown?.session)
    }

    @Test
    fun `before the review time there is no tile`() = runTest(testDispatcher) {
        now = Instant.parse("2026-11-08T18:00:00Z")
        assertNull(tile())
    }

    @Test
    fun `the review time set later in the settings is the one that counts`() = runTest(testDispatcher) {
        preferences.value = UserPreference(eveningReviewTime = "23:00")
        assertNull(tile())
    }

    @Test
    fun `a review turned off shows no tile`() = runTest(testDispatcher) {
        preferences.value = UserPreference(eveningReviewEnabled = false)
        assertNull(tile())
    }

    @Test
    fun `Bonne nuit said today, the tile is gone`() = runTest(testDispatcher) {
        doneStore.day.value = LocalDate.of(2026, 11, 8)
        assertNull(tile())
    }

    @Test
    fun `the next morning there is no tile`() = runTest(testDispatcher) {
        serve("2026-11-08", "finance")
        now = Instant.parse("2026-11-09T06:00:00Z")
        assertNull(tile())
    }
}
