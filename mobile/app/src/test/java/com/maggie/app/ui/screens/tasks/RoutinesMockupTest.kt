package com.maggie.app.ui.screens.tasks

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Routine
import com.maggie.app.data.model.RoutineDay
import com.maggie.app.data.model.RoutineRecurrence
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Routines screen as the `Routines` mock-up draws it (MAG-935). */
@RunWith(AndroidJUnit4::class)
class RoutinesMockupTest {

    @get:Rule
    val compose = ScreenRule()

    private fun routine(checked: Boolean = false) = Routine(
        id = "r2",
        title = "Sortir les poubelles",
        anchor = Routine.ANCHOR_AFTER_DINNER,
        time = "20:00",
        recurrence = RoutineRecurrence(RoutineRecurrence.WEEKLY, weekday = 2),
        createdAt = "2026-08-03T09:00:00+02:00",
        checkedToday = checked,
        dueToday = true,
        totalCount = 1,
        recentDays = List(7) { RoutineDay("2026-10-1$it", due = it == 6, checked = false) },
    )

    private fun viewModel(): RoutinesViewModel {
        val viewModel = mockk<RoutinesViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(RoutinesUiState(routines = listOf(routine())))
        return viewModel
    }

    @Test
    fun `the add button is the module's plus, said Nouvelle routine`() {
        val viewModel = viewModel()
        compose.setContent { RoutinesScreen(viewModel) }

        compose.onNodeWithTag(UiTags.ROUTINES_ADD).assertExists()
        compose.onNodeWithContentDescription("Nouvelle routine").assertExists()
        compose.onNodeWithText("Nouvelle routine").assertDoesNotExist()
        compose.onNodeWithTag(UiTags.ROUTINES_ADD).performClick()
        verify { viewModel.openNew() }
    }

    @Test
    fun `a group heading is written in capitals`() {
        val viewModel = viewModel()
        compose.setContent { RoutinesScreen(viewModel) }

        compose.onNodeWithText("CHAQUE SEMAINE").assertExists()
    }

    @Test
    fun `the week and the total sit at the right of the title, the total under the dots`() {
        compose.setContent {
            RoutineCard(routine = routine(), pending = false, animate = false, onToggle = {}, onOpen = {})
        }

        val title = compose.onNodeWithText("Sortir les poubelles").getBoundsInRoot()
        val total = compose.onNodeWithTag(UiTags.routineTotal("r2")).getBoundsInRoot()
        assertTrue(total.left >= title.right)
    }

    @Test
    fun `an unticked round is off`() {
        compose.setContent {
            RoutineCard(routine = routine(checked = false), pending = false, animate = false, onToggle = {}, onOpen = {})
        }
        compose.onNodeWithTag(UiTags.routineCheck("r2")).assertIsOff()
    }

    @Test
    fun `a ticked round is on`() {
        compose.setContent {
            RoutineCard(routine = routine(checked = true), pending = false, animate = false, onToggle = {}, onOpen = {})
        }
        compose.onNodeWithTag(UiTags.routineCheck("r2")).assertIsOn()
    }

    @Test
    fun `today's dot of a routine still to tick is a pale green`() {
        val color = recentDayColorOf(RoutineDay("d", due = true, checked = false), isToday = true, grey = Color.Gray)
        assertEquals(RoutineGreen.copy(alpha = 0.35f), color)
    }

    @Test
    fun `a past day due and not done is grey, done is green, not due is transparent`() {
        val grey = Color.Gray
        assertEquals(grey, recentDayColorOf(RoutineDay("d", due = true, checked = false), isToday = false, grey = grey))
        assertEquals(RoutineGreen, recentDayColorOf(RoutineDay("d", due = true, checked = true), isToday = false, grey = grey))
        assertEquals(Color.Transparent, recentDayColorOf(RoutineDay("d", due = false, checked = false), isToday = false, grey = grey))
    }
}
