package com.maggie.app.ui.screens.shared

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.ACTIVITY_FINANCE_REVIEW
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The detail of the review appointment (MAG-456): its month's title, and « Lancer » that opens the activity. */
@RunWith(AndroidJUnit4::class)
class EventDetailScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val appointment = ExpandedEvent(
        id = "e1",
        summary = "Revue du mois",
        startAt = "2026-10-25T18:00:00Z",
        endAt = "2026-10-25T19:00:00Z",
        activity = ACTIVITY_FINANCE_REVIEW,
    )

    @Test
    fun `the review appointment is titled after its month and offers Lancer`() {
        var launched = 0
        compose.setContent { EventDetailContent(event = appointment, onEdit = {}, onDelete = {}, onLaunch = { launched++ }) }

        compose.onNodeWithText("Revue d'octobre").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.EVENT_LAUNCH_ACTIVITY).assertIsDisplayed().performClick()

        assertEquals(1, launched)
    }

    @Test
    fun `a plain event has no Lancer`() {
        val event = appointment.copy(summary = "Dentiste", activity = null)
        compose.setContent { EventDetailContent(event = event, onEdit = {}, onDelete = {}, onLaunch = {}) }

        compose.onNodeWithText("Dentiste").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.EVENT_LAUNCH_ACTIVITY).assertDoesNotExist()
    }
}
