package com.maggie.app.ui.interruption

import com.maggie.app.data.fcm.PushActionKind
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.fcm.PushActionHandler
import com.maggie.app.data.fcm.PushOutcome
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.data.interruption.InterruptionAlert
import com.maggie.app.data.interruption.InterruptionCenter
import com.maggie.app.data.interruption.Interruptions
import com.maggie.app.data.model.Notification
import com.maggie.app.data.model.NotificationInteraction
import com.maggie.app.data.model.Task
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The host over the app: what is shown, how long, how often it rings, and what its buttons do (MAG-314). */
@RunWith(AndroidJUnit4::class)
class InterruptionHostScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val center = InterruptionCenter()
    private val handler = mockk<PushActionHandler>()
    private val postponed = mutableListOf<PushPayload>()
    private var rings = 0
    private val links = mutableListOf<PushPayload>()
    private var chats = 0

    private fun message(id: String, type: String = "proaction", link: String? = null) = PushPayload.from(
        buildMap {
            put("notificationId", id)
            put("type", type)
            put("title", "Titre $id")
            link?.let { put("link", it) }
        },
    )!!

    private fun host() {
        val viewModel = InterruptionViewModel(center, handler) { postponed += it }
        compose.setContent {
            InterruptionHost(
                onOpenLink = { links += it },
                onOpenChat = { chats++ },
                viewModel = viewModel,
                alert = InterruptionAlert { rings++ },
            )
        }
    }

    @Test
    fun `nothing is shown when Maggie has nothing to say`() {
        host()

        compose.onNodeWithTag(UiTags.INTERRUPTION).assertDoesNotExist()
    }

    @Test
    fun `a message raised while the app is open is shown and rings once`() {
        host()

        center.offer(message("a"))
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.INTERRUPTION).assertIsDisplayed()
        assertEquals(1, rings)
    }

    @Test
    fun `Plus tard closes it, postpones it and shows the next one`() {
        host()
        center.offer(message("a"))
        center.offer(message("b"))
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.INTERRUPTION_LATER).performClick()
        compose.waitForIdle()

        assertEquals(listOf("a"), postponed.map { it.notificationId })
        compose.onNodeWithTag(UiTags.INTERRUPTION).assertIsDisplayed()
        assertEquals("b", center.current.value?.notificationId)
        assertEquals(2, rings)
    }

    @Test
    fun `the action opens what it announces`() {
        host()
        center.offer(message("a", type = "finance", link = "maggie://finance/accounts"))
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.INTERRUPTION_SECOND).performClick()
        compose.waitForIdle()

        assertEquals(listOf("a"), links.map { it.notificationId })
        compose.onNodeWithTag(UiTags.INTERRUPTION).assertDoesNotExist()
    }

    @Test
    fun `an answer goes through the handler and closes it`() {
        coEvery { handler.handle(any(), any(), any(), any()) } returns PushOutcome.Closed
        host()
        center.offer(message("a", type = "reminder"))
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.INTERRUPTION_ACTION).performClick()
        compose.waitForIdle()

        coVerify { handler.handle(any(), match { it.notificationId == "a" }, any(), any()) }
        compose.onNodeWithTag(UiTags.INTERRUPTION).assertDoesNotExist()
    }

    @Test
    fun `thirty seconds without an answer closes it and it stays unread`() {
        host()
        center.offer(message("a"))
        compose.waitForIdle()

        compose.mainClock.advanceTimeBy(INTERRUPTION_AUTO_CLOSE_MS + 1_000)
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.INTERRUPTION).assertDoesNotExist()
        coVerify(exactly = 0) { handler.handle(any(), any(), any(), any()) }
        assertEquals(emptyList<PushPayload>(), postponed)
    }

    @Test
    fun `a push tap reopens the toaster on the message`() {
        host()

        center.reopen(message("a"))
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.INTERRUPTION).assertIsDisplayed()
    }

    @Test
    fun `a shortage alert shows Ajouter à la liste and Plus tard, and the first answers it (MAG-570)`() {
        coEvery { handler.handle(any(), any(), any(), any()) } returns PushOutcome.Closed
        host()
        center.offer(message("a", type = "grocery").copy(shortageId = "01HZX3K9P2M7Q4R8S5T6V0W1AB", reminderActions = listOf("add")))
        compose.waitForIdle()

        compose.onNodeWithText("Ajouter à la liste").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INTERRUPTION_LATER).assertIsDisplayed()

        compose.onNodeWithTag(UiTags.INTERRUPTION_ACTION).performClick()
        compose.waitForIdle()

        coVerify { handler.handle(PushActionKind.ADD_TO_LIST, match { it.shortageId == "01HZX3K9P2M7Q4R8S5T6V0W1AB" }, any(), any()) }
        compose.onNodeWithTag(UiTags.INTERRUPTION).assertDoesNotExist()
    }

    private fun question(multiple: Boolean = true) = Interruptions.of(
        Notification(
            id = "n-q",
            type = "question",
            title = "Quels jours ?",
            interaction = NotificationInteraction("choice", listOf("Lundi", "Mardi"), multiple = multiple, questionId = "q-1"),
        ),
    )!!

    @Test
    fun `a question shows its boxes, Valider waits for a tick and sends the choices (MAG-717)`() {
        coEvery { handler.answerQuestion(any(), any()) } returns PushOutcome.Closed
        host()
        center.offer(question())
        compose.waitForIdle()

        compose.onNodeWithText("Quels jours ?").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INTERRUPTION_ACTION).assertIsNotEnabled()

        compose.onNodeWithText("Lundi").performClick()
        compose.onNodeWithText("Mardi").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.INTERRUPTION_ACTION).assertIsEnabled().performClick()
        compose.waitForIdle()

        coVerify { handler.answerQuestion(match { it.notificationId == "n-q" }, listOf("Lundi", "Mardi")) }
        compose.onNodeWithTag(UiTags.INTERRUPTION).assertDoesNotExist()
    }

    @Test
    fun `Plus tard on a question postpones it (MAG-717)`() {
        host()
        center.offer(question())
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.INTERRUPTION_LATER).performClick()
        compose.waitForIdle()

        assertEquals(listOf("n-q"), postponed.map { it.notificationId })
    }

    @Test
    fun `a task reminder shows the sentence, the task card and Ouvrir la tâche (MAG-717)`() {
        val task = Task(id = "01HX", title = "Ranger le garage", description = "- [ ] a\n- [ ] b", dueDate = "2026-11-12")
        val viewModel = InterruptionViewModel(center, handler, postpone = { postponed += it }, loadTask = { task })
        compose.setContent {
            InterruptionHost(onOpenLink = { links += it }, onOpenChat = {}, viewModel = viewModel, alert = InterruptionAlert { })
        }
        center.offer(
            Interruptions.of(
                Notification(id = "n-t", type = "task_due", title = "C'est l'heure", body = "Ranger le garage · 14:30", relatedEntityIri = "/api/tasks/01HX"),
            )!!,
        )
        compose.waitForIdle()

        compose.onNodeWithText("C'est l'heure").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.taskCard("01HX")).assertIsDisplayed()
        compose.onNodeWithText("0/2").assertIsDisplayed()

        compose.onNodeWithText("Ouvrir la tâche").performClick()
        compose.waitForIdle()

        assertEquals(listOf("n-t"), links.map { it.notificationId })
        compose.onNodeWithTag(UiTags.INTERRUPTION).assertDoesNotExist()
    }
}
