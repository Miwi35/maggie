package com.maggie.app.data.fcm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.api.ApprovalDecisionException
import com.maggie.app.data.api.MaggieApiService
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
    fun `Plus tard answers nothing, the notification comes back later`() = runBlocking {
        assertEquals(PushOutcome.Postponed, handler.handle(PushActionKind.LATER, payload()))

        coVerify(exactly = 0) { api.markNotificationRead(any()) }
    }

    @Test
    fun `Autoriser approves the held action then marks the notification read`() = runBlocking {
        val outcome = handler.handle(PushActionKind.APPROVE, payload("approval", approvalId = "ap-1"))

        assertEquals(PushOutcome.Closed, outcome)
        coVerifyOrder {
            api.approve("ap-1")
            api.markNotificationRead("n-1")
        }
    }

    @Test
    fun `Refuser denies the held action`() = runBlocking {
        val outcome = handler.handle(PushActionKind.DENY, payload("approval", approvalId = "ap-1"))

        assertEquals(PushOutcome.Closed, outcome)
        coVerify { api.deny("ap-1") }
        coVerify(exactly = 0) { api.approve(any()) }
    }

    @Test
    fun `a decision that did not get through leaves the notification and the buttons`() = runBlocking {
        coEvery { api.approve("ap-1") } throws ApprovalDecisionException(500)

        val outcome = handler.handle(PushActionKind.APPROVE, payload("approval", approvalId = "ap-1"))

        assertTrue(outcome is PushOutcome.Retry)
        coVerify(exactly = 0) { api.markNotificationRead(any()) }
    }

    @Test
    fun `a request already answered elsewhere settles the notification`() = runBlocking {
        coEvery { api.deny("ap-1") } throws ApprovalDecisionException(409)

        val outcome = handler.handle(PushActionKind.DENY, payload("approval", approvalId = "ap-1"))

        assertTrue(outcome is PushOutcome.Settled)
        coVerify { api.markNotificationRead("n-1") }
    }

    @Test
    fun `an approval without the held action's id cannot be answered`() = runBlocking {
        val outcome = handler.handle(PushActionKind.APPROVE, payload("approval"))

        assertTrue(outcome is PushOutcome.Settled)
        coVerify(exactly = 0) { api.approve(any()) }
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
}
