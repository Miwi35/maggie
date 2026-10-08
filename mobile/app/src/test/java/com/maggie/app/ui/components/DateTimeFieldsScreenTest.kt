package com.maggie.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class DateTimeFieldsScreenTest {

    @get:Rule
    val compose = ScreenRule()

    // The picker's day cells are named by their full date, in French.
    private fun chooseDay(description: String) {
        compose.onNode(hasText(description, substring = true) and hasClickAction()).performClick()
    }

    @Test
    fun `a date is written in French, with the day of the week`() {
        assertEquals("jeu. 8 oct. 2026", formatFrenchDate(LocalDate.of(2026, 10, 8)))
        assertEquals("dim. 1 mars 2026", formatFrenchDate(LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun `a time is written on 24 hours`() {
        assertEquals("19:00", formatFrenchTime(LocalTime.of(19, 0)))
        assertEquals("00:05", formatFrenchTime(LocalTime.of(0, 5)))
    }

    @Test
    fun `the date field shows the date in French`() {
        compose.setContent {
            DateField(label = "Date de début", value = LocalDate.of(2026, 10, 8), onValueChange = {}, tag = "start")
        }

        compose.onNodeWithText("jeu. 8 oct. 2026").assertIsDisplayed()
        compose.onNodeWithText("Date de début").assertIsDisplayed()
    }

    @Test
    fun `the time field shows the time on 24 hours`() {
        compose.setContent {
            TimeField(label = "Heure de début", value = LocalTime.of(19, 0), onValueChange = {}, tag = "time")
        }

        compose.onNodeWithText("19:00").assertIsDisplayed()
    }

    @Test
    fun `touching the date field and choosing a day changes the date`() {
        var date by mutableStateOf(LocalDate.of(2026, 10, 7))
        compose.setContent {
            DateField(label = "Date de début", value = date, onValueChange = { date = it }, tag = "start")
        }

        compose.onNodeWithTag("start").performClick()
        chooseDay("jeudi 8 octobre 2026")
        compose.onNodeWithTag(UiTags.PICKER_CONFIRM).performClick()

        assertEquals(LocalDate.of(2026, 10, 8), date)
        compose.onNodeWithText("jeu. 8 oct. 2026").assertIsDisplayed()
    }

    @Test
    fun `cancelling the date picker keeps the date`() {
        var date by mutableStateOf(LocalDate.of(2026, 10, 7))
        compose.setContent {
            DateField(label = "Date de début", value = date, onValueChange = { date = it }, tag = "start")
        }

        compose.onNodeWithTag("start").performClick()
        chooseDay("jeudi 8 octobre 2026")
        compose.onNodeWithText("Annuler").performClick()

        assertEquals(LocalDate.of(2026, 10, 7), date)
    }

    @Test
    fun `the clear button of an optional date empties it`() {
        var date by mutableStateOf<LocalDate?>(LocalDate.of(2026, 10, 7))
        compose.setContent {
            DateField(label = "Date d'échéance", value = date, onValueChange = { date = it }, onClear = { date = null })
        }

        compose.onNodeWithContentDescription("Effacer : Date d'échéance").performClick()

        assertNull(date)
    }

    @Test
    fun `touching the time field and confirming returns the time of the clock`() {
        var time by mutableStateOf(LocalTime.of(19, 0))
        compose.setContent {
            TimeField(label = "Heure de début", value = time, onValueChange = { time = it }, tag = "time")
        }

        compose.onNodeWithTag("time").performClick()
        compose.onNodeWithTag(UiTags.PICKER_CONFIRM).performClick()

        assertEquals(LocalTime.of(19, 0), time)
    }
}
