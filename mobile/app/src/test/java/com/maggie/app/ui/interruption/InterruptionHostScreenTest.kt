package com.maggie.app.ui.interruption

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.fcm.PushActionHandler
import com.maggie.app.data.fcm.PushOutcome
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.data.interruption.InterruptionAlert
import com.maggie.app.data.interruption.InterruptionCenter
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

        compose.onNodeWithTag(UiTags.INTERRUPTION_ACTION).performClick()
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
}
