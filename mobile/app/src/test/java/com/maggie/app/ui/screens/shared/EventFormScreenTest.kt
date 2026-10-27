package com.maggie.app.ui.screens.shared

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.screentest.ScreenRule
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

    @Test
    fun `editing an occurrence keeps Répéter read-only and shows no explanatory text`() {
        val event = ExpandedEvent(
            id = "e1_20261007",
            summary = "Dentiste",
            startAt = "2026-10-07T07:00:00Z",
            endAt = "2026-10-07T08:00:00Z",
            masterRrule = "FREQ=WEEKLY;BYDAY=WE",
        )
        compose.setContent {
            EventEditScreen(event = event, agendas = agendas, onConfirm = {}, onBack = {})
        }

        compose.onNodeWithText("Répéter").assertIsDisplayed()
        compose.onNodeWithText("La règle de la série ne se change pas depuis une occurrence.").assertDoesNotExist()
    }
}
