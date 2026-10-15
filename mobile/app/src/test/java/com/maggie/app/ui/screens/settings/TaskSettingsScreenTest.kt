package com.maggie.app.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.AlertRule
import com.maggie.app.data.model.TaskSettings
import com.maggie.app.data.model.TaskSettingsValues
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Réglages › Tâches (MAG-407): the form reads the settings, edits go to the ViewModel, its errors show. */
@RunWith(AndroidJUnit4::class)
class TaskSettingsScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val values = TaskSettingsValues(
        orderWeights = mapOf(
            "timePressure" to mapOf("overdue" to 65, "today" to 55),
            "criticality" to mapOf("critical" to 30),
            "priority" to mapOf("high" to 6),
        ),
        alertSchedule = mapOf(
            "critical" to AlertRule(daysBefore = listOf(3, 1), dueDay = true, overdueEveryDays = 1),
            "high" to AlertRule(daysBefore = listOf(1), dueDay = true, overdueEveryDays = 1),
            "normal" to AlertRule(daysBefore = emptyList(), dueDay = true, overdueEveryDays = null),
        ),
        morningTime = "08:00",
    )

    private val settings = TaskSettings(
        id = "ts1",
        orderWeights = values.orderWeights,
        alertSchedule = values.alertSchedule,
        morningTime = values.morningTime,
        defaults = values,
    )

    private fun stateWith(tasks: TaskSettingsUiState) = MutableStateFlow(SettingsUiState(tasks = tasks))

    private fun viewModelOver(state: MutableStateFlow<SettingsUiState>) =
        mockk<SettingsViewModel>(relaxed = true).also { viewModel ->
            every { viewModel.uiState } returns state
            // The edits are applied as the ViewModel does, so the screen redraws what was typed.
            val change = slot<(TaskSettingsForm) -> TaskSettingsForm>()
            every { viewModel.editTaskSettings(capture(change)) } answers {
                val tasks = state.value.tasks
                state.value = state.value.copy(tasks = tasks.copy(form = change.captured(tasks.form!!)))
            }
        }

    private fun openTasks() {
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS).performClick()
    }

    @Test
    fun `the section shows the settings read from the server`() {
        val state = stateWith(TaskSettingsUiState(settings = settings, form = TaskSettingsForm.from(values)))
        compose.setContent { SettingsScreen(viewModel = viewModelOver(state), onBack = {}) }

        openTasks()

        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_MORNING).assertTextContains("08:00")
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_CRITICAL_DAYS).assertTextContains("3, 1")
        compose.onNodeWithText("Basse : jamais d'alerte, seulement l'affichage.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pression du temps").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `typing edits the form and Enregistrer saves it`() {
        val state = stateWith(TaskSettingsUiState(settings = settings, form = TaskSettingsForm.from(values)))
        val viewModel = viewModelOver(state)
        compose.setContent { SettingsScreen(viewModel = viewModel, onBack = {}) }
        openTasks()

        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_MORNING).performTextReplacement("07:30")
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_CRITICAL_DAYS).performTextReplacement("1")
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_SAVE).performScrollTo().performClick()

        assertEquals("07:30", state.value.tasks.form?.morningTime)
        assertEquals("1", state.value.tasks.form?.alertSchedule?.get("critical")?.daysBefore)
        verify(exactly = 1) { viewModel.saveTaskSettings() }
    }

    @Test
    fun `Valeurs par défaut asks the ViewModel for the defaults`() {
        val state = stateWith(TaskSettingsUiState(settings = settings, form = TaskSettingsForm.from(values)))
        val viewModel = viewModelOver(state)
        compose.setContent { SettingsScreen(viewModel = viewModel, onBack = {}) }
        openTasks()

        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_DEFAULTS).performScrollTo().performClick()

        verify(exactly = 1) { viewModel.resetTaskSettingsToDefaults() }
    }

    @Test
    fun `a refused save shows the reason`() {
        val state = stateWith(
            TaskSettingsUiState(settings = settings, form = TaskSettingsForm.from(values), error = "morningTime must be HH:MM."),
        )
        compose.setContent { SettingsScreen(viewModel = viewModelOver(state), onBack = {}) }
        openTasks()

        compose.onNodeWithText("morningTime must be HH:MM.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `settings that could not be read offer to try again`() {
        val state = stateWith(TaskSettingsUiState(error = "offline"))
        val viewModel = viewModelOver(state)
        compose.setContent { SettingsScreen(viewModel = viewModel, onBack = {}) }
        openTasks()

        compose.onNodeWithText("offline").assertIsDisplayed()
        compose.onNodeWithText("Réessayer").performClick()

        verify(exactly = 1) { viewModel.loadTaskSettings() }
    }
}
