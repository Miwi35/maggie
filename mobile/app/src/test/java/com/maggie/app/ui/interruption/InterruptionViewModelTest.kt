package com.maggie.app.ui.interruption

import app.cash.turbine.test
import com.maggie.app.BuildConfig
import com.maggie.app.data.fcm.PushActionHandler
import com.maggie.app.data.fcm.PushActionKind
import com.maggie.app.data.fcm.PushOutcome
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.data.interruption.InterruptionCenter
import com.maggie.app.data.interruption.Interruptions
import com.maggie.app.data.model.Notification
import com.maggie.app.data.model.NotificationInteraction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    fun `Je m'y mets closes it, answers the reminder and asks the screen to open the task's Focus (MAG-510)`() = runTest {
        val payload = message("a", type = "task_nudge").copy(reminderId = "r-1", taskId = "t-1", reminderActions = listOf("start", "in10"))
        every { handler.answerInBackground(PushActionKind.START, payload) } returns Unit
        center.offer(payload)

        viewModel.events.test {
            viewModel.answer(PushActionKind.START)

            val open = awaitItem() as InterruptionEvent.OpenLink
            assertEquals("${BuildConfig.DEEP_LINK_SCHEME}://task/t-1/focus", open.payload.link)
        }
        assertNull(shown())
        assertEquals(listOf("a"), dismissed)
        verify(exactly = 1) { handler.answerInBackground(PushActionKind.START, payload) }
    }

    @Test
    fun `Fait on a reminder closes it once the reward came back`() = runTest {
        val payload = message("a", type = "task_due").copy(reminderId = "r-1", taskId = "t-1", reminderActions = listOf("done"))
        coEvery { handler.handle(PushActionKind.TICK, any(), any(), any()) } returns PushOutcome.Rewarded("Bravo.")
        center.offer(payload)

        viewModel.answer(PushActionKind.TICK)

        assertNull(shown())
        assertEquals(listOf("a"), dismissed)
    }

    private fun envelopeAlert() = Interruptions.of(
        Notification(
            id = "n-49",
            type = "finance",
            title = "Loisirs : 83 % du budget (250 / 300 €)",
            relatedEntityIri = "/finance/budgets",
        ),
    )!!

    @Test
    fun `an envelope alert answered C'est noté is marked read and closed (MAG-49)`() = runTest {
        coEvery { handler.handle(PushActionKind.OK, any(), any(), any()) } returns PushOutcome.Closed
        center.offer(envelopeAlert())
        assertEquals("n-49", shown())

        viewModel.answer(PushActionKind.OK)

        coVerify { handler.handle(PushActionKind.OK, match { it.notificationId == "n-49" }, any(), any()) }
        assertNull(shown())
    }

    @Test
    fun `an envelope alert answered Voir les enveloppes opens the envelopes (MAG-49)`() = runTest {
        val payload = envelopeAlert()
        center.offer(payload)

        viewModel.events.test {
            viewModel.answer(PushActionKind.GO)

            val event = awaitItem() as InterruptionEvent.OpenLink
            assertEquals("${BuildConfig.DEEP_LINK_SCHEME}://finance/budgets", event.payload.link)
        }
        assertNull(shown())
    }

    @Test
    fun `an envelope alert whose read fails stays with the reason (MAG-49)`() {
        coEvery { handler.handle(PushActionKind.OK, any(), any(), any()) } returns PushOutcome.Retry("Maggie est injoignable.")
        center.offer(envelopeAlert())

        viewModel.answer(PushActionKind.OK)

        assertEquals("n-49", shown())
        assertEquals("Maggie est injoignable.", viewModel.uiState.value.note)
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

    @Test
    fun `Voir closes the routine reminder, answers it and asks the screen to open its link (MAG-512)`() = runTest {
        val payload = message("a", type = "routine", link = "maggie://tasks")
            .copy(reminderId = "r-9", reminderActions = listOf("open", "in10"))
        every { handler.answerInBackground(PushActionKind.OPEN, payload) } returns Unit
        center.offer(payload)

        viewModel.events.test {
            viewModel.answer(PushActionKind.OPEN)

            assertEquals(InterruptionEvent.OpenLink(payload), awaitItem())
        }
        assertNull(shown())
        assertEquals(listOf("a"), dismissed)
        verify(exactly = 1) { handler.answerInBackground(PushActionKind.OPEN, payload) }
    }

    // MAG-570: « Ajouter à la liste » goes through the handler like any answer that waits for the server.

    private fun shortage() = message("a", type = "grocery").copy(shortageId = "01HZX3K9P2M7Q4R8S5T6V0W1AB", reminderActions = listOf("add"))

    @Test
    fun `Ajouter à la liste is busy while it goes, then closes the alert and takes down the tray notification`() {
        val gate = CompletableDeferred<PushOutcome>()
        coEvery { handler.handle(PushActionKind.ADD_TO_LIST, any(), any(), any()) } coAnswers { gate.await() }
        center.offer(shortage())

        viewModel.answer(PushActionKind.ADD_TO_LIST)
        assertTrue(viewModel.uiState.value.busy)
        viewModel.answer(PushActionKind.ADD_TO_LIST)
        gate.complete(PushOutcome.Closed)

        coVerify(exactly = 1) { handler.handle(PushActionKind.ADD_TO_LIST, any(), any(), any()) }
        assertFalse(viewModel.uiState.value.busy)
        assertNull(shown())
        assertEquals(listOf("a"), dismissed)
    }

    @Test
    fun `Ajouter à la liste that fails keeps the alert with the reason, and can be pressed again`() {
        coEvery { handler.handle(PushActionKind.ADD_TO_LIST, any(), any(), any()) } returns PushOutcome.Retry("Maggie est injoignable. Réessayez.")
        center.offer(shortage())

        viewModel.answer(PushActionKind.ADD_TO_LIST)

        assertEquals("a", shown())
        assertEquals("Maggie est injoignable. Réessayez.", viewModel.uiState.value.note)
        assertFalse(viewModel.uiState.value.busy)

        coEvery { handler.handle(PushActionKind.ADD_TO_LIST, any(), any(), any()) } returns PushOutcome.Closed
        viewModel.answer(PushActionKind.ADD_TO_LIST)
        assertNull(shown())
    }

    @Test
    fun `a shortage resolved elsewhere is closed`() {
        coEvery { handler.handle(PushActionKind.ADD_TO_LIST, any(), any(), any()) } returns PushOutcome.Settled("Ce manquant n'est plus à signaler.")
        center.offer(shortage())

        viewModel.answer(PushActionKind.ADD_TO_LIST)

        assertNull(shown())
    }

    @Test
    fun `Plus tard on a shortage alert postpones it and answers nothing`() {
        center.offer(shortage())

        viewModel.answer(PushActionKind.LATER)

        assertEquals(listOf("a"), postponed.map { it.notificationId })
        assertNull(shown())
        coVerify(exactly = 0) { handler.handle(any(), any(), any(), any()) }
    }

    // --- MAG-717: the same question as the chat, ticked in the interruption ---

    private fun question(multiple: Boolean = true, id: String = "n-q") = Interruptions.of(
        Notification(
            id = id,
            type = "question",
            title = "Quels jours ?",
            interaction = NotificationInteraction(kind = "choice", options = listOf("Lundi", "Mardi", "Mercredi"), multiple = multiple, questionId = "q-1"),
        ),
    )!!

    @Test
    fun `ticking boxes on a multiple question keeps them all`() {
        center.offer(question())

        viewModel.toggle("Mercredi")
        viewModel.toggle("Lundi")

        assertEquals(setOf("Lundi", "Mercredi"), viewModel.uiState.value.checked)
    }

    @Test
    fun `a question with one choice keeps only the last tick`() {
        center.offer(question(multiple = false))

        viewModel.toggle("Lundi")
        viewModel.toggle("Mardi")

        assertEquals(setOf("Mardi"), viewModel.uiState.value.checked)
    }

    @Test
    fun `an option the question does not offer is ignored`() {
        center.offer(question())

        viewModel.toggle("Dimanche")

        assertTrue(viewModel.uiState.value.checked.isEmpty())
    }

    @Test
    fun `the ticks do not follow to the next interruption`() {
        center.offer(question())
        center.offer(message("b"))
        viewModel.toggle("Lundi")

        center.close("n-q")

        assertTrue(viewModel.uiState.value.checked.isEmpty())
    }

    @Test
    fun `Valider sends the choices in the order of the question and closes it`() = runTest {
        coEvery { handler.answerQuestion(any(), any()) } returns PushOutcome.Closed
        center.offer(question())
        center.offer(message("b"))
        viewModel.toggle("Mercredi")
        viewModel.toggle("Lundi")

        viewModel.submit()

        coVerify { handler.answerQuestion(match { it.notificationId == "n-q" }, listOf("Lundi", "Mercredi")) }
        assertEquals("b", shown())
        assertEquals(listOf("n-q"), dismissed)
    }

    @Test
    fun `Valider with nothing ticked sends nothing`() = runTest {
        center.offer(question())

        viewModel.submit()

        coVerify(exactly = 0) { handler.answerQuestion(any(), any()) }
        assertEquals("n-q", shown())
    }

    @Test
    fun `a question that could not be sent stays with its ticks and says why`() = runTest {
        coEvery { handler.answerQuestion(any(), any()) } returns PushOutcome.Retry("Maggie est injoignable. Réessayez.")
        center.offer(question())
        viewModel.toggle("Lundi")

        viewModel.submit()

        assertEquals("n-q", shown())
        assertEquals("Maggie est injoignable. Réessayez.", viewModel.uiState.value.note)
        assertEquals(setOf("Lundi"), viewModel.uiState.value.checked)
        assertFalse(viewModel.uiState.value.busy)
    }

    @Test
    fun `Plus tard on a question postpones it without answering`() = runTest {
        center.offer(question())
        viewModel.toggle("Lundi")

        viewModel.answer(PushActionKind.LATER)

        assertEquals(listOf("n-q"), postponed.map { it.notificationId })
        coVerify(exactly = 0) { handler.answerQuestion(any(), any()) }
    }

    @Test
    fun `Ouvrir la tâche of a reminder opens the task and closes the interruption`() = runTest {
        val reminder = Interruptions.of(
            Notification(id = "n-t", type = "task_due", title = "Ranger le garage", body = "C'est l'heure · 14:30", relatedEntityIri = "/api/tasks/01KNZ8J6AGQ0K5D3WXYZ123456"),
        )!!
        center.offer(reminder)

        viewModel.events.test {
            viewModel.answer(PushActionKind.GO)

            val event = awaitItem() as InterruptionEvent.OpenLink
            assertEquals("${BuildConfig.DEEP_LINK_SCHEME}://task/01KNZ8J6AGQ0K5D3WXYZ123456", event.payload.link)
        }
        assertNull(shown())
    }

    @Test
    fun `Plus tard on a reminder postpones it`() = runTest {
        center.offer(
            Interruptions.of(Notification(id = "n-t", type = "task_due", title = "Ranger", relatedEntityIri = "/api/tasks/01KNZ8J6AGQ0K5D3WXYZ123456"))!!,
        )

        viewModel.answer(PushActionKind.LATER)

        assertEquals(listOf("n-t"), postponed.map { it.notificationId })
    }
}
