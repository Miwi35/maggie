package com.maggie.app.ui.screens.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.AlertRule
import com.maggie.app.data.model.TaskOrderPreviewItem
import com.maggie.app.data.model.TaskSettings
import com.maggie.app.data.model.TaskSettingsValues
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Réglages › Tâches and « Ce qui passe en premier » (MAG-407): what the screens show from the
 * state, and which ViewModel call each control makes. The saving itself is `SettingsViewModelTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class TaskSettingsScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val values = TaskSettingsValues(
        orderLevels = mapOf("timePressure" to 2, "criticality" to 3, "priority" to 0),
        alertSchedule = mapOf(
            "critical" to AlertRule(daysBefore = listOf(3, 1), dueDay = true, overdueEveryDays = 1),
            "high" to AlertRule(daysBefore = listOf(1), dueDay = false, overdueEveryDays = 5),
            "normal" to AlertRule(daysBefore = emptyList(), dueDay = true, overdueEveryDays = null),
        ),
        morningTime = "08:00",
        eveningTime = "21:00",
    )

    private val settings = TaskSettings(
        id = "ts1",
        orderLevels = values.orderLevels,
        alertSchedule = values.alertSchedule,
        morningTime = values.morningTime,
        eveningTime = values.eveningTime,
        defaults = values,
    )

    private val loaded = TaskSettingsUiState(settings = settings)

    private fun viewModelOver(tasks: TaskSettingsUiState) =
        mockk<SettingsViewModel>(relaxed = true).also { viewModel ->
            every { viewModel.uiState } returns MutableStateFlow(SettingsUiState(tasks = tasks))
        }

    private fun open(tasks: TaskSettingsUiState, section: String = UiTags.SETTINGS_TASKS): SettingsViewModel {
        val viewModel = viewModelOver(tasks)
        compose.setContent { SettingsScreen(viewModel = viewModel, onBack = {}) }
        compose.onNodeWithTag(section).performClick()
        return viewModel
    }

    @Test
    fun `the alerts show the morning, each urgency's chips and when it alerts late, and no help text`() {
        open(loaded)

        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_MORNING).assertTextContains("Heure du matin").assertTextContains("08:00")
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_EVENING).assertTextContains("Heure du soir").assertTextContains("21:00")
        compose.onNodeWithTag(UiTags.settingsTasksChip("critical", 3)).assertIsSelected()
        compose.onNodeWithTag(UiTags.settingsTasksChip("critical", 7)).assertIsNotSelected()
        compose.onNodeWithTag(UiTags.settingsTasksChip("critical", null)).assertIsSelected()
        compose.onNodeWithTag(UiTags.settingsTasksChip("high", null)).performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag(UiTags.settingsTasksOverdue("critical")).assertTextContains("Chaque matin")
        // A value no option offers is shown as it is.
        compose.onNodeWithTag(UiTags.settingsTasksOverdue("high")).performScrollTo().assertTextContains("Tous les 5 jours")
        compose.onNodeWithTag(UiTags.settingsTasksOverdue("normal")).performScrollTo().assertTextContains("Jamais")

        listOf("Vide : jamais", "Enregistrer", "Enregistré").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText("Basse", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a chip switches its day through the ViewModel`() {
        val viewModel = open(loaded)

        compose.onNodeWithTag(UiTags.settingsTasksChip("critical", 7)).performClick()
        compose.onNodeWithTag(UiTags.settingsTasksChip("normal", null)).performScrollTo().performClick()

        verify(exactly = 1) { viewModel.toggleAlertDay("critical", 7) }
        verify(exactly = 1) { viewModel.toggleDueDay("normal") }
    }

    @Test
    fun `En retard offers its choices and sends the one picked`() {
        val viewModel = open(loaded)

        compose.onNodeWithTag(UiTags.settingsTasksOverdue("high")).performScrollTo().performClick()
        compose.onNodeWithText("Chaque semaine").performClick()

        verify(exactly = 1) { viewModel.setOverdueEvery("high", 7) }
    }

    @Test
    fun `the morning opens the time picker and OK sends the time`() {
        val viewModel = open(loaded)

        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_MORNING).performClick()
        compose.onNodeWithTag(UiTags.PICKER_CONFIRM).performClick()

        verify(exactly = 1) { viewModel.setMorningTime(8, 0) }
    }

    @Test
    fun `the evening opens the time picker and OK sends the time (MAG-500)`() {
        val viewModel = open(loaded)

        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_EVENING).performClick()
        compose.onNodeWithTag(UiTags.PICKER_CONFIRM).performClick()

        verify(exactly = 1) { viewModel.setEveningTime(21, 0) }
    }

    @Test
    fun `a save that went through shows Enregistré`() {
        open(loaded.copy(saved = true))
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_SAVED).assertTextContains("Enregistré")
    }

    @Test
    fun `a failed save shows its reason`() {
        open(loaded.copy(error = "Impossible d'enregistrer les réglages des tâches"))
        compose.onNodeWithText("Impossible d'enregistrer les réglages des tâches").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_SAVED).assertDoesNotExist()
    }

    @Test
    fun `Valeurs par défaut asks first, and only Confirmer resets the alerts`() {
        val viewModel = open(loaded)

        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_DEFAULTS).performScrollTo().performClick()
        compose.onNodeWithText("Revenir aux valeurs par défaut ?").assertIsDisplayed()
        compose.onNodeWithText("Annuler").performClick()
        verify(exactly = 0) { viewModel.resetAlertsToDefaults() }

        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_DEFAULTS).performScrollTo().performClick()
        compose.onNodeWithText("Confirmer").performClick()
        verify(exactly = 1) { viewModel.resetAlertsToDefaults() }
        verify(exactly = 0) { viewModel.resetOrderToDefaults() }
    }

    @Test
    fun `settings that could not be read offer to try again`() {
        val viewModel = open(TaskSettingsUiState(error = "offline"))

        compose.onNodeWithText("offline").assertIsDisplayed()
        compose.onNodeWithText("Réessayer").performClick()

        verify(exactly = 1) { viewModel.loadTaskSettings() }
    }

    // --- « Ce qui passe en premier » ---

    private val preview = listOf(
        TaskOrderPreviewItem(id = "t1", title = "Payer le loyer", urgency = "critical"),
        TaskOrderPreviewItem(id = "t2", title = "Rappeler le garage", urgency = "high"),
        TaskOrderPreviewItem(id = "t3", title = "Trier les photos", urgency = "low"),
    )

    @Test
    fun `the order shows each group's level by its word, and the preview with what moved`() {
        open(
            loaded.copy(preview = preview, moves = mapOf("t1" to TaskOrderMove.UP, "t2" to TaskOrderMove.DOWN)),
            section = UiTags.SETTINGS_TASK_ORDER,
        )

        compose.onNodeWithText("L'échéance").assertIsDisplayed()
        compose.onNodeWithText("Normal").assertIsDisplayed()
        compose.onNodeWithText("La gravité").assertIsDisplayed()
        compose.onNodeWithText("Beaucoup").assertIsDisplayed()
        compose.onNodeWithText("Ma priorité").assertIsDisplayed()
        compose.onNodeWithText("Très peu").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SETTINGS_TASK_ORDER_PREVIEW).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Payer le loyer").assertIsDisplayed()
        compose.onNodeWithText("↑").assertIsDisplayed()
        compose.onNodeWithText("↓").assertIsDisplayed()
    }

    @Test
    fun `an empty preview shows nothing`() {
        open(loaded, section = UiTags.SETTINGS_TASK_ORDER)

        compose.onNodeWithTag(UiTags.SETTINGS_TASK_ORDER_PREVIEW).assertDoesNotExist()
    }

    @Test
    fun `a slider sends its level when it is let go`() {
        val viewModel = open(loaded, section = UiTags.SETTINGS_TASK_ORDER)

        compose.onNodeWithTag(UiTags.settingsTaskOrder("timePressure")).performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }

        verify(exactly = 1) { viewModel.setOrderLevel("timePressure", 4) }
    }

    @Test
    fun `the order's Valeurs par défaut resets the levels after Confirmer`() {
        val viewModel = open(loaded, section = UiTags.SETTINGS_TASK_ORDER)

        compose.onNodeWithTag(UiTags.SETTINGS_TASK_ORDER_DEFAULTS).performScrollTo().performClick()
        compose.onNodeWithText("Confirmer").performClick()

        verify(exactly = 1) { viewModel.resetOrderToDefaults() }
        verify(exactly = 0) { viewModel.resetAlertsToDefaults() }
    }
}
