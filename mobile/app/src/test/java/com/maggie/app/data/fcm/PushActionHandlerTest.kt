package com.maggie.app.data.fcm

import com.maggie.app.data.model.ShortageAlertAnswer
import com.maggie.app.data.model.ShortageAlert
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.ApprovalDecisionException
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Reward
import com.maggie.app.data.model.TaskReminder
import com.maggie.app.data.model.TaskReminderAnswer
import com.maggie.app.data.model.TaskReminderNotification
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What each button of a notification does, and what the notification becomes after it. */
@RunWith(AndroidJUnit4::class)
class PushActionHandlerTest {

    private val api = mockk<MaggieApiService>(relaxed = true)
    private val handler = PushActionHandler(api, replyBudgetMs = 200)

    private fun payload(type: String = "reminder", approvalId: String? = null) = PushPayload(
        notificationId = "n-1",
        type = type,
        channel = PushChannels.forType(type),
        title = "Titre",
        text = "Texte",
        link = null,
        actionLabel = null,
        approvalId = approvalId,
        interaction = null,
    )

    @Test
    fun `OK and Fait mark the notification read, which closes the request everywhere`() = runBlocking {
        assertEquals(PushOutcome.Closed, handler.handle(PushActionKind.OK, payload()))
        assertEquals(PushOutcome.Closed, handler.handle(PushActionKind.DONE, payload("task_due")))

        coVerify(exactly = 2) { api.markNotificationRead("n-1") }
    }

    @Test
    fun `C'est noté on a notification with a link marks it read and opens nothing`() = runBlocking {
        val withLink = payload().copy(link = "maggie://event/e-1", actionLabel = "Voir l'événement")

        assertEquals(PushOutcome.Closed, handler.handle(PushActionKind.OK, withLink))

        coVerify(exactly = 1) { api.markNotificationRead("n-1") }
    }

    @Test
    fun `Plus tard answers nothing, the notification comes back later`() = runBlocking {
        assertEquals(PushOutcome.Postponed, handler.handle(PushActionKind.LATER, payload()))

        coVerify(exactly = 0) { api.markNotificationRead(any()) }
    }

    @Test
    fun `Autoriser approves the held action then marks the notification read`() = runBlocking {
        val outcome = handler.handle(PushActionKind.APPROVE, payload("approval", approvalId = "ap-1"))

        assertEquals(PushOutcome.Closed, outcome)
        coVerifyOrder {
            api.approve("ap-1", "notification")
            api.markNotificationRead("n-1")
        }
    }

    @Test
    fun `Refuser denies the held action`() = runBlocking {
        val outcome = handler.handle(PushActionKind.DENY, payload("approval", approvalId = "ap-1"))

        assertEquals(PushOutcome.Closed, outcome)
        coVerify { api.deny("ap-1", "notification") }
        coVerify(exactly = 0) { api.approve(any(), any()) }
    }

    @Test
    fun `a decision that did not get through leaves the notification and the buttons`() = runBlocking {
        coEvery { api.approve("ap-1", "notification") } throws ApprovalDecisionException(500)

        val outcome = handler.handle(PushActionKind.APPROVE, payload("approval", approvalId = "ap-1"))

        assertTrue(outcome is PushOutcome.Retry)
        coVerify(exactly = 0) { api.markNotificationRead(any()) }
    }

    @Test
    fun `a request already answered elsewhere settles the notification`() = runBlocking {
        coEvery { api.deny("ap-1", "notification") } throws ApprovalDecisionException(409)

        val outcome = handler.handle(PushActionKind.DENY, payload("approval", approvalId = "ap-1"))

        assertTrue(outcome is PushOutcome.Settled)
        coVerify { api.markNotificationRead("n-1") }
    }

    @Test
    fun `an approval without the held action's id cannot be answered`() = runBlocking {
        val outcome = handler.handle(PushActionKind.APPROVE, payload("approval"))

        assertTrue(outcome is PushOutcome.Settled)
        coVerify(exactly = 0) { api.approve(any(), any()) }
    }

    @Test
    fun `a decision made stays made when marking the notification read fails`() = runBlocking {
        coEvery { api.markNotificationRead("n-1") } throws IllegalStateException("offline")

        val outcome = handler.handle(PushActionKind.APPROVE, payload("approval", approvalId = "ap-1"))

        assertEquals(PushOutcome.Closed, outcome)
    }

    @Test
    fun `Répondre sends the typed reply to the chat`() = runBlocking {
        val outcome = handler.handle(PushActionKind.REPLY, payload("proaction"), reply = "  Oui, merci  ")

        assertEquals(PushOutcome.Closed, outcome)
        coVerify { api.sendChat("Oui, merci") }
    }

    @Test
    fun `Répondre marks the message read, so it is answered everywhere (MAG-682)`() = runBlocking {
        handler.handle(PushActionKind.REPLY, payload("proaction"), reply = "Oui, merci")

        coVerifyOrder {
            api.sendChat("Oui, merci")
            api.markNotificationRead("n-1")
        }
    }

    @Test
    fun `Marquer comme lu marks the notification read and sends nothing (MAG-682)`() = runBlocking {
        val outcome = handler.handle(PushActionKind.MARK_AS_READ, payload("proaction"))

        assertEquals(PushOutcome.Closed, outcome)
        coVerify { api.markNotificationRead("n-1") }
        coVerify(exactly = 0) { api.sendChat(any(), any(), any()) }
    }

    @Test
    fun `Marquer comme lu that does not get through keeps the conversation, to be tried again (MAG-682)`() = runBlocking {
        coEvery { api.markNotificationRead("n-1") } throws java.io.IOException("offline")

        assertTrue(handler.handle(PushActionKind.MARK_AS_READ, payload("proaction")) is PushOutcome.Retry)
    }

    @Test
    fun `Répondre with nothing typed sends nothing`() = runBlocking {
        val outcome = handler.handle(PushActionKind.REPLY, payload("proaction"), reply = "   ")

        assertTrue(outcome is PushOutcome.Retry)
        coVerify(exactly = 0) { api.sendChat(any(), any(), any()) }
    }

    @Test
    fun `an answer that cannot reach Maggie keeps the notification so the button can be pressed again`() = runBlocking {
        coEvery { api.markNotificationRead(any()) } throws java.io.IOException("offline")

        val outcome = handler.handle(PushActionKind.DONE, payload("task_due"))

        assertTrue(outcome is PushOutcome.Retry)
    }

    @Test
    fun `a reply sent from the notification marks it read so the request closes everywhere`() = runBlocking {
        val outcome = handler.handle(PushActionKind.REPLY, payload("proaction"), reply = "Oui, merci")

        assertEquals(PushOutcome.Closed, outcome)
        coVerifyOrder {
            api.sendChat("Oui, merci", any(), any())
            api.markNotificationRead("n-1")
        }
    }

    @Test
    fun `a reply that cannot be sent leaves the notification unread`() = runBlocking {
        coEvery { api.sendChat(any(), any(), any()) } throws java.io.IOException("offline")

        handler.handle(PushActionKind.REPLY, payload("proaction"), reply = "Oui, merci")

        coVerify(exactly = 0) { api.markNotificationRead(any()) }
    }

    @Test
    fun `a reply that cannot be sent comes back with its text so it is not lost`() = runBlocking {
        coEvery { api.sendChat(any(), any(), any()) } throws java.io.IOException("offline")

        val outcome = handler.handle(PushActionKind.REPLY, payload("proaction"), reply = "Oui, merci")

        assertTrue(outcome is PushOutcome.Retry)
        assertTrue((outcome as PushOutcome.Retry).message.contains("Oui, merci"))
    }

    @Test
    fun `a reply Maggie is still answering closes the notification instead of asking to send it twice`() = runBlocking {
        coEvery { api.sendChat(any(), any(), any()) } coAnswers {
            delay(1_000)
            mockk(relaxed = true)
        }

        val outcome = handler.handle(PushActionKind.REPLY, payload("proaction"), reply = "Oui, merci")

        assertEquals(PushOutcome.Closed, outcome)
        coVerify(exactly = 1) { api.sendChat("Oui, merci", any(), any()) }
    }

    @Test
    fun `a slow reply that ends up failing tells the notification afterwards`() = runBlocking {
        coEvery { api.sendChat(any(), any(), any()) } coAnswers {
            delay(500)
            throw java.io.IOException("offline")
        }
        val late = mutableListOf<PushOutcome.Retry>()

        val outcome = handler.handle(PushActionKind.REPLY, payload("proaction"), reply = "Oui, merci") { late += it }

        assertEquals(PushOutcome.Closed, outcome)
        val deadline = System.currentTimeMillis() + 3_000
        while (late.isEmpty() && System.currentTimeMillis() < deadline) delay(20)
        assertEquals(1, late.size)
        assertTrue(late.single().message.contains("Oui, merci"))
    }

    // MAG-510: the buttons of a task reminder answer it, each with its own effect.

    private val reminder = payload("task_nudge").copy(reminderId = "r-1", taskId = "t-1", reminderActions = listOf("start", "in10", "tonight"))

    private fun answer(reward: Reward? = null, notification: TaskReminderNotification? = null) = TaskReminderAnswer(
        reminder = TaskReminder(id = "r-1", kind = "nudge", subjectId = "t-1", scheduledFor = "2026-10-19T08:30:00+00:00", snoozed = false),
        reward = reward,
        notification = notification,
    )

    private val reward = Reward(
        id = "rw-1",
        subjectType = "task",
        subjectId = "t-1",
        level = "standard",
        messageKey = "standard.1",
        message = "Et une de moins. Bravo.",
        star = true,
        earnedAt = "2026-10-19T08:31:00+00:00",
    )

    @Test
    fun `Je m'y mets answers the reminder, the Focus opening by itself`() = runBlocking {
        coEvery { api.answerTaskReminder("r-1", "start") } returns answer()

        assertEquals(PushOutcome.Closed, handler.handle(PushActionKind.START, reminder))

        coVerify(exactly = 1) { api.answerTaskReminder("r-1", "start") }
        coVerify(exactly = 0) { api.markNotificationRead(any()) }
    }

    @Test
    fun `Dans 10 min answers the reminder and closes the notification, the server sends the next`() = runBlocking {
        coEvery { api.answerTaskReminder("r-1", "in10") } returns answer()

        assertEquals(PushOutcome.Closed, handler.handle(PushActionKind.IN_TEN, reminder))

        coVerify(exactly = 1) { api.answerTaskReminder("r-1", "in10") }
    }

    @Test
    fun `Ce soir answers the reminder and closes the notification`() = runBlocking {
        coEvery { api.answerTaskReminder("r-1", "tonight") } returns answer()

        assertEquals(PushOutcome.Closed, handler.handle(PushActionKind.TONIGHT, reminder))

        coVerify(exactly = 1) { api.answerTaskReminder("r-1", "tonight") }
    }

    @Test
    fun `Demain answers the reminder and closes the notification`() = runBlocking {
        coEvery { api.answerTaskReminder("r-1", "tomorrow") } returns answer()

        assertEquals(PushOutcome.Closed, handler.handle(PushActionKind.TOMORROW, reminder))

        coVerify(exactly = 1) { api.answerTaskReminder("r-1", "tomorrow") }
    }

    @Test
    fun `Fait ticks the task and the notification says the reward's message`() = runBlocking {
        coEvery { api.answerTaskReminder("r-1", "done") } returns
            answer(reward = reward, notification = TaskReminderNotification(id = "n-1", title = reward.message))

        assertEquals(PushOutcome.Rewarded("Et une de moins. Bravo."), handler.handle(PushActionKind.TICK, reminder))

        coVerify(exactly = 1) { api.answerTaskReminder("r-1", "done") }
    }

    @Test
    fun `Fait without a reward says the notification's new words, else that it is done`() = runBlocking {
        coEvery { api.answerTaskReminder("r-1", "done") } returns answer(notification = TaskReminderNotification(id = "n-1", title = "Bien joué."))
        assertEquals(PushOutcome.Rewarded("Bien joué."), handler.handle(PushActionKind.TICK, reminder))

        coEvery { api.answerTaskReminder("r-1", "done") } returns answer()
        assertEquals(PushOutcome.Rewarded("C'est fait."), handler.handle(PushActionKind.TICK, reminder))
    }

    @Test
    fun `a reminder already answered settles the notification in French`() = runBlocking {
        coEvery { api.answerTaskReminder("r-1", "in10") } throws
            ApiRefusalException(409, "This reminder was already answered: \"start\".")

        assertEquals(PushOutcome.Settled("Ce rappel a déjà eu sa réponse."), handler.handle(PushActionKind.IN_TEN, reminder))
    }

    @Test
    fun `a refused Ce soir or Demain says why, and a reminder gone says so`() = runBlocking {
        coEvery { api.answerTaskReminder("r-1", "tonight") } throws ApiRefusalException(409, "Tonight has passed: put it in tomorrow instead.")
        coEvery { api.answerTaskReminder("r-1", "tomorrow") } throws ApiRefusalException(409, "Tomorrow already holds 5 tasks.")
        coEvery { api.answerTaskReminder("r-1", "start") } throws ApiRefusalException(404, "Reminder not found.")

        assertEquals(PushOutcome.Settled("La soirée est passée : mettez-la plutôt à demain."), handler.handle(PushActionKind.TONIGHT, reminder))
        assertEquals(PushOutcome.Settled("Demain est déjà plein."), handler.handle(PushActionKind.TOMORROW, reminder))
        assertEquals(PushOutcome.Settled("Ce rappel n'existe plus."), handler.handle(PushActionKind.START, reminder))
    }

    @Test
    fun `a reminder answer that cannot reach Maggie keeps the buttons to press again`() = runBlocking {
        coEvery { api.answerTaskReminder("r-1", "done") } throws java.io.IOException("offline")
        coEvery { api.answerTaskReminder("r-1", "tonight") } throws ApiRefusalException(503, "Service Unavailable")

        assertEquals(PushOutcome.Retry("Maggie est injoignable. Réessayez."), handler.handle(PushActionKind.TICK, reminder))
        assertEquals(PushOutcome.Retry("Maggie est injoignable. Réessayez."), handler.handle(PushActionKind.TONIGHT, reminder))
    }

    @Test
    fun `a reminder button without its reminder answers nothing`() = runBlocking {
        val outcome = handler.handle(PushActionKind.IN_TEN, payload("task_nudge"))

        assertTrue(outcome is PushOutcome.Settled)
        coVerify(exactly = 0) { api.answerTaskReminder(any(), any()) }
    }

    @Test
    fun `Je m'y mets from the app answers in the background`() = runBlocking {
        coEvery { api.answerTaskReminder("r-1", "start") } returns answer()

        handler.answerInBackground(PushActionKind.START, reminder)

        coVerify(timeout = 3_000) { api.answerTaskReminder("r-1", "start") }
    }

    @Test
    fun `Voir answers the routine reminder and closes the notification, the app opening the tasks (MAG-512)`() = runBlocking {
        val routine = payload("routine").copy(reminderId = "r-9", reminderActions = listOf("open", "in10"))
        coEvery { api.answerTaskReminder("r-9", "open") } returns answer()

        assertEquals(PushOutcome.Closed, handler.handle(PushActionKind.OPEN, routine))

        coVerify(exactly = 1) { api.answerTaskReminder("r-9", "open") }
    }

    // MAG-570: « Ajouter à la liste » answers a shortage alert.

    private val shortage = payload("grocery").copy(shortageId = "01HZX3K9P2M7Q4R8S5T6V0W1AB", reminderActions = listOf("add"))

    @Test
    fun `Ajouter à la liste answers the shortage alert and closes the notification`() = runBlocking {
        coEvery { api.answerShortageAlert("01HZX3K9P2M7Q4R8S5T6V0W1AB", "add") } returns
            ShortageAlertAnswer(ShortageAlert(id = "01HZX3K9P2M7Q4R8S5T6V0W1AB", name = "parmesan", resolved = true))

        assertEquals(PushOutcome.Closed, handler.handle(PushActionKind.ADD_TO_LIST, shortage))

        coVerify(exactly = 1) { api.answerShortageAlert("01HZX3K9P2M7Q4R8S5T6V0W1AB", "add") }
        coVerify(exactly = 0) { api.markNotificationRead(any()) }
    }

    @Test
    fun `a shortage already resolved, unknown or refused settles the notification in French`() = runBlocking {
        for (status in listOf(400, 404, 409)) {
            coEvery { api.answerShortageAlert(any(), "add") } throws ApiRefusalException(status, "refused")

            assertEquals(PushOutcome.Settled("Ce manquant n'est plus à signaler."), handler.handle(PushActionKind.ADD_TO_LIST, shortage))
        }
    }

    @Test
    fun `a shortage answer that cannot reach Maggie keeps the button to press again`() = runBlocking {
        coEvery { api.answerShortageAlert(any(), "add") } throws java.io.IOException("offline")
        assertEquals(PushOutcome.Retry("Maggie est injoignable. Réessayez."), handler.handle(PushActionKind.ADD_TO_LIST, shortage))

        coEvery { api.answerShortageAlert(any(), "add") } throws ApiRefusalException(503, "Service Unavailable")
        assertEquals(PushOutcome.Retry("Maggie est injoignable. Réessayez."), handler.handle(PushActionKind.ADD_TO_LIST, shortage))
    }

    @Test
    fun `Ajouter à la liste without its shortage answers nothing`() = runBlocking {
        val outcome = handler.handle(PushActionKind.ADD_TO_LIST, payload("grocery"))

        assertTrue(outcome is PushOutcome.Settled)
        coVerify(exactly = 0) { api.answerShortageAlert(any(), any()) }
    }

    @Test
    fun `Plus tard on a shortage alert stays local`() = runBlocking {
        assertEquals(PushOutcome.Postponed, handler.handle(PushActionKind.LATER, shortage))

        coVerify(exactly = 0) { api.answerShortageAlert(any(), any()) }
    }

    @Test
    fun `Déjà en route closes the departure alert and opens nothing (MAG-658)`() = runBlocking {
        val departure = payload().copy(type = "departure", link = "https://waze.com/ul?navigate=yes")

        assertEquals(PushOutcome.Closed, handler.handle(PushActionKind.ALREADY_ON_THE_WAY, departure))
        assertEquals(PushOutcome.Closed, handler.handle(PushActionKind.GO, departure))

        coVerify(exactly = 0) { api.markNotificationRead(any()) }
    }
}
