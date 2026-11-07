package com.maggie.app.ui.screens.shared

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.layout.positionInRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** The header of the one event screen (MAG-649): × on the left, the pill on the right, the pill off until the form can be saved. */
@RunWith(AndroidJUnit4::class)
class EventFormScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val agendas = listOf(Agenda(id = "01PERSO", name = "Perso", isDefault = true))

    @Test
    fun `creating shows the title, a Créer pill off until a title is typed, and closes with the cross`() {
        var back = 0
        var created: com.maggie.app.data.api.EventCreateRequest? = null
        compose.setContent {
            EventCreateScreen(
                agendas = agendas,
                initialDate = LocalDate.of(2026, 10, 7),
                onConfirm = { created = it },
                onBack = { back++ },
            )
        }

        compose.onNodeWithText("Nouvel événement").assertIsDisplayed()
        compose.onNodeWithText("Créer").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.EVENT_SAVE).assertIsNotEnabled()

        compose.onNodeWithTag(UiTags.EVENT_TITLE).performTextInput("Dentiste")
        compose.onNodeWithTag(UiTags.EVENT_SAVE).assertIsEnabled().performClick()
        assertEquals("Dentiste", created?.summary)
        assertEquals("/api/agendas/01PERSO", created?.agenda)

        compose.onNodeWithContentDescription("Fermer").performClick()
        assertEquals(1, back)
    }

    @Test
    fun `editing shows the same fields under Modifier l'événement with an Enregistrer pill`() {
        val event = ExpandedEvent(
            id = "e1",
            summary = "Dentiste",
            startAt = "2026-10-07T07:00:00Z",
            endAt = "2026-10-07T08:00:00Z",
            location = "Rennes",
        )
        var patch: kotlinx.serialization.json.JsonObject? = null
        compose.setContent {
            EventEditScreen(event = event, agendas = agendas, onConfirm = { patch = it }, onBack = {})
        }

        compose.onNodeWithText("Modifier l'événement").assertIsDisplayed()
        compose.onNodeWithText("Titre").assertIsDisplayed()
        compose.onNodeWithText("Répéter").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.EVENT_SAVE).assertIsEnabled().performClick()

        assertEquals("\"Dentiste\"", patch?.get("summary").toString())
        assertEquals("\"Rennes\"", patch?.get("location").toString())
        assertNull(patch?.get("rrule"))
    }

    private val occurrence = ExpandedEvent(
        id = "m1__2026-10-12",
        summary = "Sport",
        startAt = "2026-10-12T10:00:00Z",
        endAt = "2026-10-12T11:00:00Z",
        isVirtualOccurrence = true,
        masterEventId = "m1",
        masterRrule = "FREQ=WEEKLY;BYDAY=MO",
    )

    @Test
    fun `an occurrence reads the series rule alone, with no help text under Répéter`() {
        compose.setContent {
            EventEditScreen(event = occurrence, agendas = agendas, onConfirm = {}, onBack = {})
        }

        compose.onNodeWithText("Répéter").assertIsDisplayed()
        compose.onNodeWithText("La règle de la série ne se change pas depuis une occurrence.").assertDoesNotExist()
    }

    @Test
    fun `the labels follow the mock-up from top to bottom, Début and Fin side by side, Rappels in one field`() {
        compose.setContent {
            EventEditScreen(event = occurrence.copy(masterRrule = null), agendas = agendas, onConfirm = {}, onBack = {})
        }

        assertTopToBottom(
            "Titre" to compose.onNodeWithText("Titre"),
            "Toute la journée" to compose.onNodeWithText("Toute la journée"),
            "Début" to compose.onNodeWithText("Début"),
            "Répéter" to compose.onNodeWithText("Répéter"),
            "Rappels" to compose.onNodeWithText("Rappels"),
            "Agenda" to compose.onNodeWithText("Agenda"),
            "Lieu" to compose.onNodeWithText("Lieu"),
        )
        val start = compose.onNodeWithText("Début").fetchSemanticsNode().positionInRoot
        val end = compose.onNodeWithText("Fin").fetchSemanticsNode().positionInRoot
        assertEquals(start.y, end.y, 1f)
        assert(end.x > start.x)
        compose.onAllNodesWithText("Rappels").assertCountEquals(1)
        compose.onNodeWithText("Ajouter un rappel").assertDoesNotExist()
    }

    @Test
    fun `an all-day event hides the times and Rappels, and is saved with no reminder`() {
        var patch: kotlinx.serialization.json.JsonObject? = null
        val withReminder = occurrence.copy(
            masterRrule = null,
            reminders = com.maggie.app.data.model.remindersFrom(listOf(30)),
        )
        compose.setContent {
            EventEditScreen(event = withReminder, agendas = agendas, onConfirm = { patch = it }, onBack = {})
        }
        compose.onNodeWithText("Rappels").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.EVENT_START_TIME).assertIsDisplayed()

        compose.onNodeWithTag(UiTags.EVENT_ALL_DAY).performClick()

        compose.onNodeWithText("Rappels").assertDoesNotExist()
        compose.onNodeWithTag(UiTags.EVENT_START_TIME).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.EVENT_SAVE).performClick()
        assertEquals(kotlinx.serialization.json.JsonNull, patch?.get("reminders"))
    }

    @Test
    fun `the Rappels sheet lists the six options and Personnaliser, and two ticked both go`() {
        var patch: kotlinx.serialization.json.JsonObject? = null
        compose.setContent {
            EventEditScreen(event = occurrence.copy(masterRrule = null), agendas = agendas, onConfirm = { patch = it }, onBack = {})
        }

        compose.onNodeWithTag(UiTags.EVENT_REMINDERS).performClick()
        listOf(
            "5 minutes avant", "10 minutes avant", "15 minutes avant", "30 minutes avant",
            "1 heure avant", "1 jour avant", "Personnaliser…",
        ).forEach { compose.onNodeWithText(it).assertIsDisplayed() }

        compose.onNodeWithText("10 minutes avant").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hasText("10 minutes avant") and isToggleable()).assertIsOn()
        compose.onNodeWithText("1 jour avant").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hasText("10 minutes avant") and isToggleable()).assertIsOn()

        val reminders = com.maggie.app.data.model.EventReminders(
            useDefault = false,
            overrides = listOf(10, 1440).map { com.maggie.app.data.model.EventReminder(minutes = it) },
        )
        compose.onNodeWithTag(UiTags.EVENT_SAVE).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(
            kotlinx.serialization.json.Json.encodeToJsonElement(com.maggie.app.data.model.EventReminders.serializer(), reminders),
            patch?.get("reminders"),
        )
    }
}
