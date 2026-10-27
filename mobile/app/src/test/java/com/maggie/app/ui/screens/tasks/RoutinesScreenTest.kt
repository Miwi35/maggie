package com.maggie.app.ui.screens.tasks

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Routine
import com.maggie.app.data.model.RoutineDay
import com.maggie.app.data.model.RoutineRecurrence
import com.maggie.app.data.model.TaskView
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A routine's card and its sheet (MAG-511), drawn from plain state. */
@RunWith(AndroidJUnit4::class)
class RoutinesScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun routine(due: Boolean = true, checked: Boolean = false) = Routine(
        id = "r2",
        title = "Sortir les poubelles",
        anchor = Routine.ANCHOR_AFTER_DINNER,
        time = "20:00",
        recurrence = RoutineRecurrence(RoutineRecurrence.WEEKLY, weekday = 2),
        createdAt = "2026-08-03T09:00:00+02:00",
        checkedToday = checked,
        dueToday = due,
        totalCount = 1,
        recentDays = List(7) { RoutineDay("2026-10-1$it", due = it == 6, checked = false) },
    )

    @Test
    fun `the Routines entry sits next to Aujourd'hui in the menu, and only when the screen has it`() {
        assertEquals(listOf(TaskView.TODAY, null, TaskView.UPCOMING, TaskView.SOMEDAY, TaskView.DONE), tabsOf(withRoutines = true))
        assertEquals(TaskView.entries.toList(), tabsOf(withRoutines = false))
    }

    @Test
    fun `a card due today ticks from its round and says when and how many times`() {
        var toggled = 0
        compose.setContent {
            RoutineCard(routine = routine(), pending = false, animate = false, onToggle = { toggled++ }, onOpen = {})
        }

        compose.onNodeWithText("le mardi, après le dîner").assertExists()
        compose.onNodeWithTag(UiTags.routineTotal("r2")).assertTextEquals("depuis août : 1 fois")
        compose.onNodeWithTag(UiTags.routineCheck("r2")).assertIsOff().performClick()
        assertEquals(1, toggled)
    }

    @Test
    fun `a ticked card shows its round on, and a tick in flight cannot be repeated`() {
        compose.setContent {
            RoutineCard(routine = routine(checked = true), pending = true, animate = false, onToggle = {}, onOpen = {})
        }

        compose.onNodeWithTag(UiTags.routineCheck("r2")).assertIsOn().assertIsNotEnabled()
    }

    @Test
    fun `a card not due today has no round`() {
        compose.setContent {
            RoutineCard(routine = routine(due = false), pending = false, animate = false, onToggle = {}, onOpen = {})
        }

        compose.onNodeWithTag(UiTags.routineCheck("r2")).assertDoesNotExist()
    }

    @Test
    fun `touching a card opens it`() {
        var opened = 0
        compose.setContent {
            RoutineCard(routine = routine(), pending = false, animate = false, onToggle = {}, onOpen = { opened++ })
        }

        compose.onNodeWithText("Sortir les poubelles").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the sheet of a new routine has no Active and no Supprimer, and names the weekday chosen`() {
        var changed: RoutineDraft? = null
        compose.setContent {
            RoutineEditContent(
                draft = RoutineDraft(title = "Poubelles", kind = RoutineRecurrence.WEEKLY, weekday = 2),
                isSaving = false,
                message = null,
                onChange = { changed = it },
                onSave = {},
                onDelete = {},
            )
        }

        compose.onNodeWithTag(UiTags.ROUTINE_ACTIVE).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.ROUTINE_DELETE).assertDoesNotExist()
        compose.onNodeWithText("Chaque mardi").assertExists()
        compose.onNodeWithTag(UiTags.routineWeekday(4)).performClick()
        assertEquals(4, changed?.weekday)
    }

    @Test
    fun `the sheet of an existing routine confirms before deleting`() {
        var deleted = 0
        compose.setContent {
            RoutineEditContent(
                draft = RoutineDraft(id = "r2", title = "Poubelles"),
                isSaving = false,
                message = null,
                onChange = {},
                onSave = {},
                onDelete = { deleted++ },
            )
        }

        compose.onNodeWithTag(UiTags.ROUTINE_ACTIVE).assertIsOn()
        compose.onNodeWithTag(UiTags.ROUTINE_DELETE).performClick()
        assertEquals(0, deleted)
        compose.onNodeWithTag(UiTags.ROUTINE_DELETE_CONFIRM).performClick()
        assertEquals(1, deleted)
    }
}
