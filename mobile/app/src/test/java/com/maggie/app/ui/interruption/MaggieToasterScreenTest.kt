package com.maggie.app.ui.interruption

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.fcm.PushActionKind
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The toaster Maggie speaks through while the app is open: forms, answers, reduced motion, auto-close (MAG-314). */
@RunWith(AndroidJUnit4::class)
class MaggieToasterScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val answers = mutableListOf<PushActionKind>()

    private fun message(
        type: String = "proaction",
        interaction: String? = null,
        link: String? = null,
        actionLabel: String? = null,
        approvalId: String? = null,
        text: String? = "Un plat ce soir ?",
    ) = PushPayload.from(
        buildMap {
            put("notificationId", "n-1")
            put("type", type)
            put("title", "Maggie a une idée")
            text?.let { put("body", it) }
            interaction?.let { put("interaction", """{"kind":"$it"}""") }
            link?.let { put("link", it) }
            actionLabel?.let { put("actionLabel", it) }
            approvalId?.let { put("approvalId", it) }
        },
    )!!

    private fun show(payload: PushPayload, busy: Boolean = false, note: String? = null, reducedMotion: Boolean = false) {
        compose.setContent {
            MaggieToaster(payload, busy, note, reducedMotion, onAction = { answers += it })
        }
    }

    @Test
    fun `Maggie introduces herself and says the message`() {
        show(message())

        compose.onNodeWithTag(UiTags.INTERRUPTION).assertIsDisplayed()
        compose.onNodeWithText("MAGGIE · maintenant").assertIsDisplayed()
        compose.onNodeWithText("Maggie a une idée").assertIsDisplayed()
        compose.onNodeWithText("Un plat ce soir ?").assertIsDisplayed()
    }

    @Test
    fun `an action form offers C'est noté, its link by the words the server gave, and Plus tard`() {
        show(message(interaction = "action", link = "maggie://event/e-1", actionLabel = "Voir l'événement"))

        compose.onNodeWithTag(UiTags.INTERRUPTION_ACTION).assertIsDisplayed()
        compose.onNodeWithText("C'est noté").performClick()
        compose.onNodeWithText("Voir l'événement").performClick()
        compose.onNodeWithTag(UiTags.INTERRUPTION_LATER).performClick()

        assertEquals(listOf(PushActionKind.OK, PushActionKind.GO, PushActionKind.LATER), answers)
    }

    @Test
    fun `an ack form is acknowledged with C'est noté`() {
        show(message(type = "reminder", interaction = "ack"))

        compose.onNodeWithText("C'est noté").performClick()

        assertEquals(listOf(PushActionKind.OK), answers)
    }

    @Test
    fun `a completable ack offers Fait`() {
        show(
            PushPayload.from(
                mapOf("notificationId" to "n-1", "type" to "task_due", "title" to "Linge", "interaction" to """{"kind":"ack","completable":true}"""),
            )!!,
        )

        compose.onNodeWithText("Fait").performClick()

        assertEquals(listOf(PushActionKind.DONE), answers)
    }

    @Test
    fun `a confirm form asks to authorize or refuse, in that order`() {
        show(message(type = "approval", interaction = "confirm", approvalId = "ap-1"))

        compose.onNodeWithTag(UiTags.INTERRUPTION_ACTION).performClick()
        compose.onNodeWithTag(UiTags.INTERRUPTION_SECOND).performClick()

        assertEquals(listOf(PushActionKind.APPROVE, PushActionKind.DENY), answers)
        compose.onNodeWithText("Autoriser").assertIsDisplayed()
        compose.onNodeWithText("Refuser").assertIsDisplayed()
    }

    // The reporting phone: 1080 × 2340 px at xxhdpi is 360 dp wide. Native graphics: the legacy mode measures
    // one pixel per character, so no button would ever wrap.
    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `an approval keeps Refuser and Autoriser side by side, Plus tard under them, on a 360 dp phone (MAG-696)`() =
        assertApprovalActionsGrouped()

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `an approval keeps Refuser and Autoriser side by side, Plus tard under them, on a 411 dp phone (MAG-696)`() =
        assertApprovalActionsGrouped()

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `an approval keeps its actions grouped inside a bubble that does not stretch on a tablet (MAG-696)`() {
        assertApprovalActionsGrouped()

        val bubble = compose.onNodeWithTag(UiTags.INTERRUPTION).fetchSemanticsNode().boundsInRoot
        assertTrue("the bubble stretches over the tablet: ${bubble.width} px", bubble.width <= 480 * 2f)
    }

    private fun assertApprovalActionsGrouped() {
        show(message(type = "approval", interaction = "confirm", approvalId = "ap-1"))

        val bubble = compose.onNodeWithTag(UiTags.INTERRUPTION).fetchSemanticsNode().boundsInRoot
        val deny = compose.onNodeWithTag(UiTags.INTERRUPTION_SECOND).fetchSemanticsNode().boundsInRoot
        val allow = compose.onNodeWithTag(UiTags.INTERRUPTION_ACTION).fetchSemanticsNode().boundsInRoot
        val later = compose.onNodeWithTag(UiTags.INTERRUPTION_LATER).fetchSemanticsNode().boundsInRoot
        val gap = 16 * ApplicationProvider.getApplicationContext<Application>().resources.displayMetrics.density

        compose.onNodeWithText("Refuser").assertIsDisplayed()
        compose.onNodeWithText("Autoriser").assertIsDisplayed()
        compose.onNodeWithText("Plus tard").assertIsDisplayed()
        assertEquals("Refuser and Autoriser are not on one line", deny.top, allow.top, 1f)
        assertTrue("Refuser must come first, Autoriser after it", deny.right <= allow.left)
        assertTrue("Refuser and Autoriser are not grouped: ${allow.left - deny.right} px apart", allow.left - deny.right <= gap)
        assertTrue("Plus tard must sit under the pair", later.top >= maxOf(deny.bottom, allow.bottom))
        assertTrue("an action leaves the bubble", listOf(deny, allow, later).all { it.left >= bubble.left && it.right <= bubble.right })
    }

    @Test
    fun `a choice has no endpoint yet, so it offers to answer in the chat`() {
        show(message(interaction = "choice"))

        compose.onNodeWithText("Répondre").performClick()

        assertEquals(listOf(PushActionKind.REPLY), answers)
    }

    @Test
    fun `buttons are locked while an answer is on its way`() {
        show(message(interaction = "ack"), busy = true)

        compose.onNodeWithTag(UiTags.INTERRUPTION_ACTION).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.INTERRUPTION_LATER).assertIsNotEnabled()
    }

    @Test
    fun `an answer that did not get through says why`() {
        show(message(interaction = "ack"), note = "Maggie est injoignable. Réessayez.")

        compose.onNodeWithText("Maggie est injoignable. Réessayez.").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INTERRUPTION_ACTION).assertIsEnabled()
    }

    @Test
    fun `with reduced animations it fades in and stays usable`() {
        show(message(interaction = "ack"), reducedMotion = true)

        compose.onNodeWithTag(UiTags.INTERRUPTION).assertIsDisplayed()
        compose.onNodeWithText("C'est noté").performClick()

        assertEquals(listOf(PushActionKind.OK), answers)
    }

    @Test
    fun `a message without text still shows`() {
        show(message(text = null))

        compose.onNodeWithText("Maggie a une idée").assertIsDisplayed()
        assertTrue(answers.isEmpty())
    }
}
