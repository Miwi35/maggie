package com.maggie.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Task
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskCardTest {

    @get:Rule
    val compose = ScreenRule()

    private val garage = Task(
        id = "t-1",
        title = "Ranger le garage",
        description = "- [ ] Balayer\n- [ ] Trier\n- [x] Sortir les cartons\n- [ ] A\n- [ ] B\n- [ ] C",
        dueDate = "2026-11-12",
    )

    @Test
    fun `the card shows the title, the due day and the count of boxes`() {
        compose.setContent { TaskCard(garage.copy(description = "- [ ] a\n- [ ] b\n- [ ] c\n- [ ] d\n- [ ] e\n- [ ] f"), onOpen = {}) }

        compose.onNodeWithText("Ranger le garage").assertIsDisplayed()
        compose.onNodeWithText("Pour le 12 nov.").assertIsDisplayed()
        compose.onNodeWithText("0/6").assertIsDisplayed()
    }

    @Test
    fun `the count follows the boxes already ticked`() {
        compose.setContent { TaskCard(garage, onOpen = {}) }

        compose.onNodeWithText("1/6").assertIsDisplayed()
    }

    @Test
    fun `a task without boxes shows no count and never its notes`() {
        compose.setContent { TaskCard(garage.copy(description = "Penser aux sacs"), onOpen = {}) }

        compose.onNodeWithText("Penser aux sacs").assertDoesNotExist()
        compose.onNodeWithText("0/0").assertDoesNotExist()
    }

    @Test
    fun `the list itself is never shown`() {
        compose.setContent { TaskCard(garage, onOpen = {}) }

        compose.onNodeWithText("Balayer", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a planned day wins over the due day, with its time`() {
        compose.setContent {
            TaskCard(garage.copy(scheduledDate = "2026-11-09", scheduledMoment = "time", scheduledTime = "14:30"), onOpen = {})
        }

        compose.onNodeWithText("Prévue le 9 nov. à 14:30").assertIsDisplayed()
        compose.onNodeWithText("Pour le 12 nov.").assertDoesNotExist()
    }

    @Test
    fun `a planned moment of the day is spelled out`() {
        compose.setContent { TaskCard(garage.copy(scheduledDate = "2026-11-09", scheduledMoment = "evening"), onOpen = {}) }

        compose.onNodeWithText("Prévue le 9 nov. · soir").assertIsDisplayed()
    }

    @Test
    fun `a tap opens the task`() {
        var opened = 0
        compose.setContent { TaskCard(garage, onOpen = { opened++ }) }

        compose.onNodeWithTag(UiTags.taskCard("t-1")).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `the block card loads its task by id and shows it`() {
        val asked = mutableListOf<String>()
        compose.setContent { TaskBlockCard("t-1", load = { asked += it; garage }, onOpen = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("Ranger le garage").assertIsDisplayed()
        assertEquals(listOf("t-1"), asked)
    }

    @Test
    fun `the block card shows nothing when the task cannot be read`() {
        compose.setContent { TaskBlockCard("t-1", load = { null }, onOpen = {}) }
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.taskCard("t-1")).assertDoesNotExist()
    }

    @Test
    fun `checklistCount reads dashes, stars and capital X`() {
        assertEquals("1/3", checklistCount("- [X] a\n* [ ] b\n  - [ ] c"))
        assertNull(checklistCount("- a\n- b"))
        assertNull(checklistCount(null))
    }
}
