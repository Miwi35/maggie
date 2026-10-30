package com.maggie.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.util.DateRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `the quick picks set tomorrow or the day after in one touch, and close the picker (MAG-718)`() {
        var date by mutableStateOf<LocalDate?>(null)
        compose.setContent {
            DateField(label = "Programmée", value = date, onValueChange = { date = it }, tag = "day", quickPicks = true)
        }
        val today = DateRanges.todayDate()

        compose.onNodeWithTag("day").performClick()
        compose.onNodeWithText("Demain").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PICKER_TOMORROW).performClick()
        assertEquals(today.plusDays(1), date)
        compose.onNodeWithTag(UiTags.PICKER_CONFIRM).assertDoesNotExist()

        compose.onNodeWithTag("day").performClick()
        compose.onNodeWithText("Après-demain").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PICKER_AFTER_TOMORROW).performClick()
        assertEquals(today.plusDays(2), date)
    }

    // The dialog lays its content in a Box: stacked there, the calendar covered the quick picks on a phone.
    @Test
    fun `the quick picks sit above the calendar, not under it (MAG-718)`() {
        compose.setContent {
            DateField(label = "Programmée", value = null, onValueChange = {}, tag = "day", quickPicks = true)
        }

        compose.onNodeWithTag("day").performClick()

        val chips = compose.onNodeWithTag(UiTags.PICKER_TOMORROW).getUnclippedBoundsInRoot()
        val calendar = compose.onNodeWithText("Sélectionner une date").getUnclippedBoundsInRoot()
        assertTrue("« Demain » ends at ${chips.bottom}, the calendar starts at ${calendar.top}", chips.bottom <= calendar.top)
    }

    @Test
    fun `without quick picks the picker is the calendar alone, as the Agenda has it`() {
        compose.setContent {
            DateField(label = "Date de début", value = LocalDate.of(2026, 10, 8), onValueChange = {}, tag = "start")
        }

        compose.onNodeWithTag("start").performClick()

        compose.onNodeWithTag(UiTags.PICKER_CONFIRM).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PICKER_TOMORROW).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.PICKER_AFTER_TOMORROW).assertDoesNotExist()
    }
}
