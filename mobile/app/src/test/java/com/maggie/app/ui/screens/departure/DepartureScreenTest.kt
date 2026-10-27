package com.maggie.app.ui.screens.departure

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Trip
import com.maggie.app.data.model.TripStep
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

/** « Pars maintenant » (MAG-658, mockup `Depart-Tel`). */
@RunWith(AndroidJUnit4::class)
class DepartureScreenTest {

    @get:Rule
    val screen = ScreenRule()

    private val now = ZonedDateTime.of(2026, 10, 26, 17, 4, 0, 0, ZoneId.of("Europe/Paris"))

    private val trip = Trip(
        id = "t-1",
        event = "/api/events/e-1",
        steps = listOf(TripStep("walk", 3), TripStep("drive", 13), TripStep("walk", 5)),
        durationMinutes = 21,
        departAt = "2026-10-26T17:04:00+01:00",
        navigationUrl = "https://waze.com/ul?navigate=yes",
        originLabel = "École Jean-Moulin",
    )

    private fun show(trip: Trip? = this.trip, onGo: (() -> Unit)? = {}, onAlreadyOnTheWay: () -> Unit = {}) =
        screen.setContent { DepartureScreen("Pars maintenant pour Récupérer Ael", trip, now, onGo, onAlreadyOnTheWay) }

    @Test
    fun `it says the time, who speaks, what to do and how long it takes`() {
        show()

        screen.onNodeWithText("17:04").assertIsDisplayed()
        screen.onNodeWithText("lundi 26 octobre").assertIsDisplayed()
        screen.onNodeWithText("Maggie · 17:04").assertIsDisplayed()
        screen.onNodeWithText("Pars maintenant pour Récupérer Ael").assertIsDisplayed()
        screen.onNodeWithText("École Jean-Moulin · 21 min porte à porte").assertIsDisplayed()
        screen.onNodeWithText("13 min").assertIsDisplayed()
    }

    @Test
    fun `Y aller and Déjà en route each do their own thing`() {
        var went = 0
        var dismissed = 0
        show(onGo = { went++ }, onAlreadyOnTheWay = { dismissed++ })

        screen.onNodeWithTag(UiTags.DEPARTURE_GO).assertHasClickAction().performClick()
        screen.onNodeWithTag(UiTags.DEPARTURE_DISMISS).assertHasClickAction().performClick()

        assertEquals(1, dismissed)
        assertEquals(1, went)
    }

    @Test
    fun `without a navigation link there is no Y aller, only Déjà en route`() {
        show(onGo = null)

        screen.onNodeWithTag(UiTags.DEPARTURE_GO).assertDoesNotExist()
        screen.onNodeWithTag(UiTags.DEPARTURE_DISMISS).assertIsDisplayed()
    }

    @Test
    fun `before the trip is known the screen carries the title and the buttons`() {
        show(trip = null)

        screen.onNodeWithText("Pars maintenant pour Récupérer Ael").assertIsDisplayed()
        screen.onNode(hasText("porte à porte", substring = true)).assertDoesNotExist()
        screen.onNodeWithTag(UiTags.DEPARTURE_GO).assertIsDisplayed()
    }
}
