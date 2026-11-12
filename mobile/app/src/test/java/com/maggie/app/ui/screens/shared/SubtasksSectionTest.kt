package com.maggie.app.ui.screens.shared

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Task
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtasksSectionTest {

    @get:Rule
    val compose = ScreenRule()

    private val truck = Task(id = "s1", title = "Réserver le camion", parent = "/api/tasks/p1", position = "0")
    private val bank = Task(id = "s2", title = "Changer d'adresse à la banque", parent = "/api/tasks/p1", position = "1")
    private val deleted = mutableListOf<String>()

    private fun show() {
        compose.setContent {
            SubtasksSection(
                state = SubtasksUiState(parentId = "p1", subtasks = listOf(truck, bank)),
                actions = SubtaskActions(onDelete = { deleted += it.id }),
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `eachRowHasADeleteIcon`() {
        show()
        assertEquals(2, compose.onAllNodesWithContentDescription("Supprimer la sous-tâche").fetchSemanticsNodes().size)
    }

    @Test
    fun `confirmingDeleteCallsOnDelete`() {
        show()
        compose.onAllNodesWithContentDescription("Supprimer la sous-tâche")[0].performClick()
        compose.onNodeWithTag(UiTags.TASK_DELETE_CONFIRM).performClick()
        assertEquals(listOf("s1"), deleted)
    }

    @Test
    fun `cancellingDeleteDeletesNothing`() {
        show()
        compose.onAllNodesWithContentDescription("Supprimer la sous-tâche")[0].performClick()
        compose.onNodeWithTag(UiTags.TASK_DELETE_CANCEL).performClick()
        assertEquals(emptyList<String>(), deleted)
    }
}
