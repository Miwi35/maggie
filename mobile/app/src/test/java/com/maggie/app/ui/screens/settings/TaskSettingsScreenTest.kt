package com.maggie.app.ui.screens.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Réglages › Tâches › Rappels (MAG-718) and « Ce qui passe en premier » (MAG-407): what the screens show from the
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
        rewardAnimation = true,
        rewardVibration = true,
        rewardSound = true,
        tone = "doux",
        dayMoments = mapOf("wake" to "07:30", "afterBreakfast" to "08:30", "afterLunch" to "13:30", "afterDinner" to "20:00"),
        reminderDailyCap = 6,
        quietStart = "21:00",
        quietEnd = "08:00",
    )

    private val settings = TaskSettings(
        id = "ts1",
        orderLevels = values.orderLevels,
        alertSchedule = values.alertSchedule,
        morningTime = values.morningTime,
        eveningTime = values.eveningTime,
        rewardAnimation = values.rewardAnimation,
        rewardVibration = values.rewardVibration,
        rewardSound = values.rewardSound,
        tone = values.tone,
        dayMoments = values.dayMoments,
        reminderDailyCap = values.reminderDailyCap,
        quietStart = values.quietStart,
        quietEnd = values.quietEnd,
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
    fun `the reminders read like the mock-up - breadcrumb, one card per urgency, no help text, no morning hour (MAG-718)`() {
        open(loaded)

        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_BREADCRUMB).assertTextEquals("RÉGLAGES › TÂCHES")
        compose.onNodeWithText("Rappels").assertIsDisplayed()
        listOf("critical" to "Critique", "high" to "Haute", "normal" to "Normale").forEach { (urgency, label) ->
            val card = compose.onNodeWithTag(UiTags.settingsTasksUrgency(urgency)).performScrollTo()
            listOf(label, "À programmer", "Pas faite").forEach { card.assert(hasAnyDescendant(hasText(it))) }
        }
        compose.onAllNodesWithText("À programmer").assertCountEquals(3)
        listOf("J-7", "J-3", "J-2", "J-1", "Jour J").forEach { chip ->
            compose.onAllNodesWithText(chip).assertCountEquals(3)
        }
        compose.onNodeWithTag(UiTags.settingsTasksChip("critical", 3)).assertIsSelected()
        compose.onNodeWithTag(UiTags.settingsTasksChip("critical", 7)).assertIsNotSelected()
        compose.onNodeWithTag(UiTags.settingsTasksChip("critical", null)).assertIsSelected()
        compose.onNodeWithTag(UiTags.settingsTasksChip("high", null)).performScrollTo().assertIsNotSelected()

        listOf(
            "Heure du matin",
            "Lus par le digest du matin",
            "tant que la tâche n'a pas de date programmée",
            "En retard",
            "Vide : jamais",
            "Enregistrer",
            "Enregistré",
        ).forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("Basse", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the cards come first, « Heure du soir » under « Rappels et récompenses » (MAG-718)`() {
        open(loaded)

        val normal = compose.onNodeWithTag(UiTags.settingsTasksUrgency("normal")).performScrollTo().fetchSemanticsNode().boundsInRoot
        val evening = compose.onNodeWithTag(UiTags.SETTINGS_TASKS_EVENING).performScrollTo()
            .assertTextContains("Heure du soir").assertTextContains("21:00").fetchSemanticsNode().boundsInRoot
        assertTrue("« Heure du soir » is under the cards", evening.top >= normal.bottom)
    }

    @Test
    fun `a day switches through the ViewModel`() {
        val viewModel = open(loaded)

        compose.onNodeWithTag(UiTags.settingsTasksChip("critical", 7)).performClick()
        compose.onNodeWithTag(UiTags.settingsTasksChip("normal", null)).performScrollTo().performClick()

        verify(exactly = 1) { viewModel.toggleAlertDay("critical", 7) }
        verify(exactly = 1) { viewModel.toggleDueDay("normal") }
    }

    @Test
    fun `Pas faite is four buttons, the stored one selected, and sends the one picked (MAG-718)`() {
        val viewModel = open(loaded)

        listOf(null to "Jamais", 1 to "Chaque matin", 2 to "Tous les 2 jours", 7 to "Chaque semaine").forEach { (every, text) ->
            compose.onNodeWithTag(UiTags.settingsTasksOverdueOption("normal", every)).performScrollTo().assertTextEquals(text)
        }
        compose.onNodeWithTag(UiTags.settingsTasksOverdueOption("critical", 1)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(UiTags.settingsTasksOverdueOption("critical", null)).assertIsNotSelected()
        compose.onNodeWithTag(UiTags.settingsTasksOverdueOption("normal", null)).performScrollTo().assertIsSelected()
        // A value no option offers is shown as it is, selected.
        compose.onNodeWithTag(UiTags.settingsTasksOverdueOption("high", 5)).performScrollTo()
            .assertTextEquals("Tous les 5 jours").assertIsSelected()

        compose.onNodeWithTag(UiTags.settingsTasksOverdueOption("high", 7)).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.settingsTasksOverdueOption("critical", 1)).performScrollTo().performClick()

        verify(exactly = 1) { viewModel.setOverdueEvery("high", 7) }
        verify(exactly = 0) { viewModel.setOverdueEvery("critical", any()) }
    }

    @Test
    fun `the evening opens the time picker and OK sends the time (MAG-500)`() {
        val viewModel = open(loaded)

        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_EVENING).performClick()
        compose.onNodeWithTag(UiTags.PICKER_CONFIRM).performClick()

        verify(exactly = 1) { viewModel.setEveningTime(21, 0) }
    }

    @Test
    fun `Rappels et récompenses shows the three switches and the tone, each sent through the ViewModel (MAG-508)`() {
        val viewModel = open(loaded.copy(settings = settings.copy(rewardSound = false, tone = "direct")))

        compose.onNodeWithText("Rappels et récompenses").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_REWARD_ANIMATION).performScrollTo().assertIsOn()
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_REWARD_SOUND).performScrollTo().assertIsOff()
        compose.onNodeWithTag(UiTags.settingsTasksTone("direct")).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(UiTags.settingsTasksTone("drole")).assertIsNotSelected()

        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_REWARD_VIBRATION).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_REWARD_SOUND).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.settingsTasksTone("drole")).performScrollTo().performClick()

        verify(exactly = 1) { viewModel.setRewardVibration(false) }
        verify(exactly = 1) { viewModel.setRewardSound(true) }
        verify(exactly = 1) { viewModel.setTone("drole") }
    }

    @Test
    fun `the reminders show the cap, the quiet hours and the four moments, with no help text (MAG-510)`() {
        open(loaded.copy(settings = settings.copy(reminderDailyCap = 4, quietStart = "22:00", quietEnd = "07:00")))

        compose.onNodeWithText("Au plus, par jour").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_REMINDER_CAP_VALUE).performScrollTo().assertTextContains("4")
        compose.onNodeWithText("Calme").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_QUIET_START).performScrollTo().assertTextContains("22:00")
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_QUIET_END).assertTextContains("07:00")
        compose.onNodeWithText("Mes moments").performScrollTo().assertIsDisplayed()
        listOf(
            "wake" to "Réveil" to "07:30",
            "afterBreakfast" to "Après le petit-déjeuner" to "08:30",
            "afterLunch" to "Après le déjeuner" to "13:30",
            "afterDinner" to "Après le dîner" to "20:00",
        ).forEach { (moment, time) ->
            compose.onNodeWithTag(UiTags.settingsTasksMoment(moment.first)).performScrollTo()
                .assertTextContains(moment.second).assertTextContains(time)
        }
    }

    @Test
    fun `the cap's − and + step through the ViewModel, each disabled at its bound (MAG-510)`() {
        val viewModel = open(loaded)

        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_REMINDER_CAP_MINUS).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_REMINDER_CAP_PLUS).performClick()

        verify(exactly = 1) { viewModel.stepReminderDailyCap(-1) }
        verify(exactly = 1) { viewModel.stepReminderDailyCap(1) }
    }

    @Test
    fun `at twelve a day the + is off, the − still on (MAG-510)`() {
        open(loaded.copy(settings = settings.copy(reminderDailyCap = 12)))
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_REMINDER_CAP_PLUS).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_REMINDER_CAP_MINUS).assertIsEnabled()
    }

    @Test
    fun `each end of the quiet hours and each moment opens the clock, OK sends its time (MAG-510)`() {
        val viewModel = open(loaded)

        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_QUIET_START).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.PICKER_CONFIRM).performClick()
        compose.onNodeWithTag(UiTags.SETTINGS_TASKS_QUIET_END).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.PICKER_CONFIRM).performClick()
        compose.onNodeWithTag(UiTags.settingsTasksMoment("afterDinner")).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.PICKER_CONFIRM).performClick()

        verify(exactly = 1) { viewModel.setQuietStart(21, 0) }
        verify(exactly = 1) { viewModel.setQuietEnd(8, 0) }
        verify(exactly = 1) { viewModel.setDayMoment("afterDinner", 20, 0) }
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
