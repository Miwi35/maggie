package com.maggie.app.ui.screens.shared

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Task
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Notes and descriptions are Markdown fields (MAG-713): the bar shows when the field has the focus. */
@RunWith(AndroidJUnit4::class)
class MarkdownFormsTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `the task creation notes have the Markdown bar`() {
        compose.setContent { TaskFormScreen(task = null, onCreate = {}, onUpdate = {}, onDelete = {}, onBack = {}) }

        compose.onNodeWithTag(UiTags.MARKDOWN_TOOLBAR).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TASK_NOTES).performClick()
        compose.onNodeWithTag(UiTags.MARKDOWN_TOOLBAR).assertIsDisplayed()
    }

    @Test
    fun `the task edition notes have the Markdown bar`() {
        compose.setContent {
            TaskFormScreen(
                task = Task(id = "t1", title = "Piscine", description = "- [ ] Douche solaire"),
                onCreate = {},
                onUpdate = {},
                onDelete = {},
                onBack = {},
            )
        }

        compose.onNodeWithTag(UiTags.TASK_NOTES).performClick()
        compose.onNodeWithTag(UiTags.MARKDOWN_TOOLBAR).assertIsDisplayed()
    }

    @Test
    fun `the event description has the Markdown bar`() {
        compose.setContent {
            EventCreateScreen(agendas = listOf(Agenda(id = "01PERSO", name = "Perso", isDefault = true)), onConfirm = {}, onBack = {})
        }

        compose.onNodeWithTag(UiTags.EVENT_DESCRIPTION).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.MARKDOWN_TOOLBAR).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `ticking a box in the task's notes hands back the notes with the box ticked`() {
        var saved: String? = null
        compose.setContent {
            TaskDetailContent(
                task = Task(id = "t1", title = "Piscine", description = "- [ ] Douche solaire\n- [ ] Pompe"),
                subtasks = SubtasksUiState(parentId = "t1"),
                onEdit = {},
                onToggleDone = {},
                onDescriptionChange = { saved = it },
            )
        }

        compose.onNodeWithTag(UiTags.markdownCheckbox(0)).performClick()
        assertEquals("- [x] Douche solaire\n- [ ] Pompe", saved)
    }
}
