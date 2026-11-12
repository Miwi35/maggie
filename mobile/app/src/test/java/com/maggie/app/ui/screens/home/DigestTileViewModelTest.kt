package com.maggie.app.ui.screens.home

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.AudioAttachment
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.DigestBlock
import com.maggie.app.data.repository.ChatRepository
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** The digest tile of the Accueil (MAG-808): today's, not opened, before 14:00. */
@OptIn(ExperimentalCoroutinesApi::class)
class DigestTileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var now = Instant.parse("2026-11-12T10:00:00Z")
    private val events = MutableSharedFlow<MercureEvent>()
    private val mercure = mockk<MercureService> { every { subscribe(any()) } returns events }
    private val auth = mockk<AuthRepository> { coEvery { getUserId() } returns "u-1" }
    private val chat = mockk<ChatRepository>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun digest(day: String = "2026-11-12", openedAt: String? = null) = ChatMessage(
        id = "m1",
        role = "assistant",
        content = "Votre digest du matin est disponible.",
        audio = AudioAttachment(durationMs = 72_000),
        blocks = listOf(DigestBlock(day = day, text = "Bonjour.", expiresAt = "2026-11-12T14:00:00+01:00", openedAt = openedAt)),
    )

    private fun TestScope.tile(): DigestTileViewModel {
        val vm = DigestTileViewModel(chat, mercure, auth) { now }
        vm.digest.onEach { }.launchIn(backgroundScope)
        runCurrent()
        return vm
    }

    @Test
    fun `today's digest not opened is the tile`() = runTest(dispatcher) {
        coEvery { chat.latestDigest() } returns digest()

        assertEquals("m1", tile().digest.value?.id)
    }

    @Test
    fun `an opened digest is no tile`() = runTest(dispatcher) {
        coEvery { chat.latestDigest() } returns digest(openedAt = "2026-11-12T09:00:00Z")

        assertNull(tile().digest.value)
    }

    @Test
    fun `yesterday's digest is no tile`() = runTest(dispatcher) {
        coEvery { chat.latestDigest() } returns digest(day = "2026-11-11")

        assertNull(tile().digest.value)
    }

    @Test
    fun `after 14 00 there is no tile`() = runTest(dispatcher) {
        now = Instant.parse("2026-11-12T13:30:00Z")
        coEvery { chat.latestDigest() } returns digest()

        assertNull(tile().digest.value)
    }

    @Test
    fun `no digest at all, no tile`() = runTest(dispatcher) {
        coEvery { chat.latestDigest() } returns null

        assertNull(tile().digest.value)
    }

    @Test
    fun `the opening told by Mercure takes the tile away`() = runTest(dispatcher) {
        coEvery { chat.latestDigest() } returns digest()
        val vm = tile()
        assertEquals("m1", vm.digest.value?.id)

        coEvery { chat.latestDigest() } returns digest(openedAt = "2026-11-12T10:01:00Z")
        events.emit(MercureEvent(data = "{}"))
        advanceTimeBy(1_000)
        runCurrent()

        assertNull(vm.digest.value)
    }

    @Test
    fun `the tile goes away by itself at 14 00`() = runTest(dispatcher) {
        coEvery { chat.latestDigest() } returns digest()
        val vm = DigestTileViewModel(chat, mercure, auth) { Instant.parse("2026-11-12T10:00:00Z").plusMillis(currentTime) }
        vm.digest.onEach { }.launchIn(backgroundScope)
        advanceTimeBy(1_000)
        assertEquals("m1", vm.digest.value?.id)

        advanceTimeBy(3 * 3_600_000L)

        assertNull(vm.digest.value)
    }

    @Test
    fun `a read that fails keeps the last answer`() = runTest(dispatcher) {
        coEvery { chat.latestDigest() } returns digest()
        val vm = tile()

        coEvery { chat.latestDigest() } throws RuntimeException("offline")
        events.emit(MercureEvent(data = "{}"))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals("m1", vm.digest.value?.id)
    }
}
