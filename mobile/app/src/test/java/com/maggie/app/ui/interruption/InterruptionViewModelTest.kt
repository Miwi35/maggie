package com.maggie.app.ui.interruption

import app.cash.turbine.test
import com.maggie.app.data.fcm.PushActionHandler
import com.maggie.app.data.fcm.PushActionKind
import com.maggie.app.data.fcm.PushOutcome
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.data.interruption.InterruptionCenter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/** One test per answer of the toaster: what it does to the queue, the request and the screen (MAG-314). */
@OptIn(ExperimentalCoroutinesApi::class)
class InterruptionViewModelTest {

    private val center = InterruptionCenter()
    private val handler = mockk<PushActionHandler>()
    private val postponed = mutableListOf<PushPayload>()
    private val dismissed = mutableListOf<String>()
    private lateinit var viewModel: InterruptionViewModel

    private fun message(id: String, type: String = "reminder", link: String? = null) = PushPayload.from(
        buildMap {
            put("notificationId", id)
            put("type", type)
            put("title", "Titre $id")
            link?.let { put("link", it) }
        },
    )!!

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = InterruptionViewModel(center, handler, dismiss = { dismissed += it.notificationId }) { postponed += it }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun shown() = viewModel.uiState.value.payload?.notificationId

    @Test
    fun `the state follows the queue`() {
        assertNull(viewModel.uiState.value.payload)

        center.offer(message("a"))
        assertEquals("a", shown())

        center.close("a")
        assertNull(shown())
    }

    @Test
    fun `OK marks it read through the handler and shows the next one`() = runTest {
        coEvery { handler.handle(PushActionKind.OK, any(), any(), any()) } returns PushOutcome.Closed
        center.offer(message("a"))
        center.offer(message("b"))

        viewModel.answer(PushActionKind.OK)

        coVerify { handler.handle(PushActionKind.OK, match { it.notificationId == "a" }, any(), any()) }
        assertEquals("b", shown())
    }

    @Test
    fun `a failed answer keeps it on screen with the reason, and can be pressed again`() {
        coEvery { handler.handle(PushActionKind.OK, any(), any(), any()) } returns PushOutcome.Retry("Maggie est injoignable.")
        center.offer(message("a"))

        viewModel.answer(PushActionKind.OK)

        assertEquals("a", shown())
        assertEquals("Maggie est injoignable.", viewModel.uiState.value.note)
        assertFalse(viewModel.uiState.value.busy)

        coEvery { handler.handle(PushActionKind.OK, any(), any(), any()) } returns PushOutcome.Closed
        viewModel.answer(PushActionKind.OK)
        assertNull(shown())
    }

    @Test
    fun `a request answered elsewhere is closed`() {
        coEvery { handler.handle(PushActionKind.APPROVE, any(), any(), any()) } returns PushOutcome.Settled("Plus en attente.")
        center.offer(message("a", type = "approval").copy(approvalId = "ap-1"))

        viewModel.answer(PushActionKind.APPROVE)

        assertNull(shown())
    }

    @Test
    fun `buttons wait while an answer is on its way, and a second press sends nothing`() {
        val gate = CompletableDeferred<PushOutcome>()
        coEvery { handler.handle(PushActionKind.DONE, any(), any(), any()) } coAnswers { gate.await() }
        center.offer(message("a", type = "task_due"))

        viewModel.answer(PushActionKind.DONE)
        assertTrue(viewModel.uiState.value.busy)
        viewModel.answer(PushActionKind.DONE)
        gate.complete(PushOutcome.Closed)

        coVerify(exactly = 1) { handler.handle(PushActionKind.DONE, any(), any(), any()) }
        assertNull(shown())
    }

    @Test
    fun `Plus tard postpones it locally and lets the next one through`() {
        center.offer(message("a"))
        center.offer(message("b"))

        viewModel.answer(PushActionKind.LATER)

        assertEquals(listOf("a"), postponed.map { it.notificationId })
        assertEquals("b", shown())
        assertFalse("not before its time", center.offer(message("a")))
        assertTrue("it comes back with its alarm", center.offer(message("a"), reshow = true))
    }

    @Test
    fun `Y aller closes it and asks the screen to open its link`() = runTest {
        val payload = message("a", link = "maggie://event/e-1")
        center.offer(payload)

        viewModel.events.test {
            viewModel.answer(PushActionKind.GO)

            assertEquals(InterruptionEvent.OpenLink(payload), awaitItem())
        }
        assertNull(shown())
    }

    @Test
    fun `Répondre closes it and opens the chat`() = runTest {
        center.offer(message("a"))

        viewModel.events.test {
            viewModel.answer(PushActionKind.REPLY)

            assertEquals(InterruptionEvent.OpenChat, awaitItem())
        }
        assertNull(shown())
    }

    @Test
    fun `thirty seconds without an answer closes it and does not mark it read`() {
        center.offer(message("a"))
        center.offer(message("b"))

        viewModel.timedOut("a")

        assertEquals("b", shown())
        coVerify(exactly = 0) { handler.handle(any(), any(), any(), any()) }
    }

    @Test
    fun `an interruption rings once, however many times the screen comes back`() {
        assertTrue(viewModel.announce("a"))
        assertFalse(viewModel.announce("a"))
        assertTrue(viewModel.announce("b"))
    }

    @Test
    fun `an answer takes down the notification a push left in the tray`() = runTest {
        coEvery { handler.handle(PushActionKind.OK, any(), any(), any()) } returns PushOutcome.Closed
        center.offer(message("a"))

        viewModel.answer(PushActionKind.OK)

        assertEquals(listOf("a"), dismissed)
    }

    @Test
    fun `Plus tard also takes it down from the tray, its alarm brings it back`() {
        center.offer(message("a"))

        viewModel.answer(PushActionKind.LATER)

        assertEquals(listOf("a"), dismissed)
        assertEquals(listOf("a"), postponed.map { it.notificationId })
    }

    @Test
    fun `answering with nothing on screen does nothing`() {
        viewModel.answer(PushActionKind.OK)

        coVerify(exactly = 0) { handler.handle(any(), any(), any(), any()) }
    }
}
