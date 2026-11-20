package com.maggie.app.ui.screens.shared

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The fields of « Répétition personnalisée » (MAG-887). The dialog is an `AlertDialog`, which hangs
 * Robolectric's idling: the fields are drawn on their own here, the dialog only frames them.
 */
@RunWith(AndroidJUnit4::class)
class RepeatRuleFieldsTest {
    @get:Rule val compose = ScreenRule()

    private val tuesday = LocalDate.of(2026, 11, 24)
    private var rule: RepeatRule? = null

    private fun show(initial: RepeatRule = RepeatRule(RepeatUnit.WEEK, 1, setOf(DayOfWeek.TUESDAY))) {
        compose.setContent { RepeatRuleFields(initial, tuesday, onRuleChange = { rule = it }) }
        compose.waitForIdle()
    }

    @Test
    fun `the interval, the unit and the days build the rule and its words`() {
        show()

        compose.onNodeWithTag(UiTags.TASK_REPEAT_INTERVAL).performTextReplacement("3")
        compose.onNodeWithTag(UiTags.taskRepeatDay("FR")).performClick()
        compose.waitForIdle()

        assertEquals(RepeatRule(RepeatUnit.WEEK, 3, setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY)), rule)
        compose.onNodeWithText("Le mardi et vendredi, toutes les 3 semaines").assertIsDisplayed()
    }

    @Test
    fun `the last day cannot be unchecked`() {
        show()

        compose.onNodeWithTag(UiTags.taskRepeatDay("TU")).performClick()
        compose.waitForIdle()

        assertEquals(setOf(DayOfWeek.TUESDAY), rule?.days)
    }

    @Test
    fun `months hide the days, an empty interval has no rule`() {
        show()

        compose.onNodeWithTag(UiTags.taskRepeatUnit("month")).performClick()
        compose.waitForIdle()
        assertEquals(RepeatRule(RepeatUnit.MONTH, 1), rule)
        compose.onNodeWithText("Le 24 de chaque mois").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.TASK_REPEAT_INTERVAL).performTextReplacement("")
        compose.waitForIdle()
        assertNull(rule)
        compose.onNodeWithText("Indiquez un nombre d’au moins 1").assertIsDisplayed()
    }
}
