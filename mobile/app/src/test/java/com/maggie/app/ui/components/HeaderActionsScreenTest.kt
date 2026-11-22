package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.fcm.PushInteraction
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.tap
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.interruption.ConfirmationEntry
import com.maggie.app.ui.interruption.InterruptionsUiState
import com.maggie.app.ui.interruption.SettledEntry
import com.maggie.app.ui.interruption.SettledKind
import com.maggie.app.ui.interruption.TripEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The loupe, the bell, its badge and its panel (MAG-712). */
@RunWith(AndroidJUnit4::class)
class HeaderActionsScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun question(id: String, text: String) = PushPayload(
        notificationId = id,
        type = "reminder",
        channel = "reminders",
        title = "Question $id",
        text = text,
        link = null,
        actionLabel = null,
        approvalId = null,
        interaction = PushInteraction(kind = "choice", options = listOf("Oui", "Non")),
    )

    private val trip = TripEntry("n1", "Partez maintenant", 25, "https://maps.example/go")
    private val full = InterruptionsUiState(
        trips = listOf(trip),
        questions = listOf(question("q1", "Je décale ?"), question("q2", "On mange où ?")),
        confirmations = listOf(ConfirmationEntry("a1", "Supprimer la tâche")),
        settled = listOf(
            SettledEntry("n0", SettledKind.TRIP, "Dentiste", "Itinéraire ouvert", "https://maps.example/old"),
            SettledEntry("a0", SettledKind.CONFIRMATION, "Créer l'événement", "Autorisé"),
        ),
    )

    private fun show(state: InterruptionsUiState, actions: InterruptionsActions = InterruptionsActions(), onSearch: () -> Unit = {}) {
        compose.setContent { Row { HeaderActions(state, actions, onSearch) } }
    }

    @Test
    fun `the loupe opens the search`() {
        var searched = 0
        show(InterruptionsUiState(), onSearch = { searched++ })

        compose.onNodeWithTag(UiTags.HEADER_SEARCH).tap()

        assertEquals(1, searched)
    }

    @Test
    fun `the badge counts trips, questions and confirmations and is absent at zero`() {
        show(full)
        compose.onNodeWithTag(UiTags.HEADER_BELL_BADGE, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("4", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `no badge when nothing waits`() {
        show(InterruptionsUiState())
        compose.onNodeWithTag(UiTags.HEADER_BELL_BADGE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the bell opens the panel with the pending section first and the settled one after`() {
        show(full)

        compose.onNodeWithTag(UiTags.HEADER_BELL).tap()

        compose.onNodeWithTag(UiTags.INTERRUPTIONS_PANEL, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("En attente de votre réponse").assertIsDisplayed()
        compose.onNodeWithText("Partez maintenant").assertIsDisplayed()
        compose.onNodeWithText("Maggie a 2 questions").assertIsDisplayed()
        compose.onNodeWithText("Nouveau message de Maggie").assertIsDisplayed()
        compose.onNodeWithText("Réglées").assertIsDisplayed()
        compose.onNodeWithText("Itinéraire ouvert").assertIsDisplayed()
        compose.onNodeWithText("Autorisé").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HEADER_BELL).assertIsDisplayed()
    }

    @Test
    fun `the panel floats 12 dp from both edges, under the bell`() {
        show(full)
        compose.onNodeWithTag(UiTags.HEADER_BELL).tap()

        val panel = compose.onNodeWithTag(UiTags.INTERRUPTIONS_PANEL, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val bell = compose.onNodeWithTag(UiTags.HEADER_BELL).getUnclippedBoundsInRoot()
        val root = compose.onNodeWithTag(UiTags.INTERRUPTIONS_SCRIM).getUnclippedBoundsInRoot()

        assertEquals(12.dp.value, panel.left.value, 0.5f)
        assertEquals((root.width - 12.dp).value, panel.right.value, 0.5f)
        assertTrue(panel.top >= bell.bottom - 4.dp)
    }

    @Test
    fun `the panel reads its sections and rows from top to bottom`() {
        show(full)
        compose.onNodeWithTag(UiTags.HEADER_BELL).tap()

        val tops = listOf(
            compose.onNodeWithTag(UiTags.INTERRUPTIONS_PENDING, useUnmergedTree = true),
            compose.onNodeWithTag(UiTags.interruptionsTrip("n1")),
            compose.onNodeWithTag(UiTags.INTERRUPTIONS_QUESTIONS),
            compose.onNodeWithTag(UiTags.INTERRUPTIONS_CONFIRMATION),
            compose.onNodeWithTag(UiTags.INTERRUPTIONS_SETTLED, useUnmergedTree = true),
            compose.onNodeWithTag(UiTags.interruptionsSettled("n0")),
        ).map { it.getUnclippedBoundsInRoot().top.value }

        assertEquals(tops.sorted(), tops)
        assertEquals(tops.distinct(), tops)
    }

    @Test
    fun `a tap above the panel, on the header's band, closes it too`() {
        show(full)
        compose.onNodeWithTag(UiTags.HEADER_BELL).tap()

        compose.onNodeWithTag(UiTags.INTERRUPTIONS_SCRIM).performTouchInput { click(Offset(centerX, 1f)) }

        compose.onNodeWithTag(UiTags.INTERRUPTIONS_PANEL, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a tap on the scrim closes the panel`() {
        show(full)
        compose.onNodeWithTag(UiTags.HEADER_BELL).tap()

        compose.onNodeWithTag(UiTags.INTERRUPTIONS_SCRIM).tap()

        compose.onNodeWithTag(UiTags.INTERRUPTIONS_PANEL, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a trip opens through the callback and closes the panel`() {
        var opened: TripEntry? = null
        show(full, InterruptionsActions(onTrip = { opened = it }))
        compose.onNodeWithTag(UiTags.HEADER_BELL).tap()

        compose.onNodeWithTag(UiTags.interruptionsTrip("n1")).tap()

        assertEquals(trip, opened)
        compose.onNodeWithTag(UiTags.INTERRUPTIONS_PANEL, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the questions expand in place and a question reopens`() {
        var reopened: PushPayload? = null
        show(full, InterruptionsActions(onQuestion = { reopened = it }))
        compose.onNodeWithTag(UiTags.HEADER_BELL).tap()
        compose.onNodeWithTag(UiTags.interruptionsQuestion("q2")).assertDoesNotExist()

        compose.onNodeWithTag(UiTags.INTERRUPTIONS_QUESTIONS).tap()
        val group = compose.onNodeWithTag(UiTags.INTERRUPTIONS_QUESTIONS).getUnclippedBoundsInRoot()
        val q = compose.onNodeWithTag(UiTags.interruptionsQuestion("q1")).getUnclippedBoundsInRoot()
        assertTrue(q.top >= group.bottom)
        assertTrue(q.left >= group.left + 22.dp)
        compose.onNodeWithTag(UiTags.interruptionsQuestion("q2")).tap()

        assertEquals("q2", reopened?.notificationId)
    }

    @Test
    fun `a confirmation and a settled confirmation go to the chat callbacks`() {
        var confirmation: ConfirmationEntry? = null
        var settled: SettledEntry? = null
        show(full, InterruptionsActions(onConfirmation = { confirmation = it }, onSettled = { settled = it }))

        compose.onNodeWithTag(UiTags.HEADER_BELL).tap()
        compose.onNodeWithTag(UiTags.INTERRUPTIONS_CONFIRMATION).tap()
        assertEquals("a1", confirmation?.id)

        compose.onNodeWithTag(UiTags.HEADER_BELL).tap()
        compose.onNodeWithTag(UiTags.interruptionsSettled("n0")).tap()
        assertEquals("n0", settled?.id)
    }

    @Test
    fun `an empty panel says Maggie waits for nothing`() {
        show(InterruptionsUiState())

        compose.onNodeWithTag(UiTags.HEADER_BELL).tap()

        compose.onNodeWithTag(UiTags.INTERRUPTIONS_EMPTY, useUnmergedTree = true).assertExists()
    }
}
