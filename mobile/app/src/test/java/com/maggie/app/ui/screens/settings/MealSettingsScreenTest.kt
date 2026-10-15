package com.maggie.app.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.MealSettings
import com.maggie.app.data.model.MealSettingsValues
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Réglages › Repas (MAG-483): the default number of guests is read, stepped, saved, and its errors show. */
@RunWith(AndroidJUnit4::class)
class MealSettingsScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val settings = MealSettings("ms1", 3, MealSettingsValues(2))

    private fun viewModelOver(state: MealSettingsUiState) = mockk<SettingsViewModel>(relaxed = true).also { viewModel ->
        every { viewModel.uiState } returns MutableStateFlow(SettingsUiState(meals = state))
    }

    private fun openMeals(viewModel: SettingsViewModel) {
        compose.setContent { SettingsScreen(viewModel = viewModel, onBack = {}) }
        compose.onNodeWithTag(UiTags.SETTINGS_MEALS).performClick()
    }

    @Test
    fun `the page opens by reading the settings and shows the default number`() {
        val viewModel = viewModelOver(MealSettingsUiState(settings = settings, guests = 3))

        openMeals(viewModel)

        compose.onNodeWithText("Nombre de convives par défaut").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SETTINGS_MEALS_GUESTS + "_value").assertTextEquals("3")
        verify(exactly = 1) { viewModel.loadMealSettings() }
    }

    @Test
    fun `the buttons step the number and Enregistrer saves it`() {
        val viewModel = viewModelOver(MealSettingsUiState(settings = settings, guests = 3))
        openMeals(viewModel)

        compose.onNodeWithTag(UiTags.SETTINGS_MEALS_GUESTS + "_plus").performClick()
        compose.onNodeWithTag(UiTags.SETTINGS_MEALS_GUESTS + "_minus").performClick()
        compose.onNodeWithTag(UiTags.SETTINGS_MEALS_SAVE).performClick()

        verify(exactly = 1) { viewModel.stepDefaultGuests(1) }
        verify(exactly = 1) { viewModel.stepDefaultGuests(-1) }
        verify(exactly = 1) { viewModel.saveMealSettings() }
    }

    @Test
    fun `a saved page says so`() {
        openMeals(viewModelOver(MealSettingsUiState(settings = settings, guests = 3, saved = true)))

        compose.onNodeWithTag(UiTags.SETTINGS_MEALS_SAVED).assertIsDisplayed()
    }

    @Test
    fun `a refused save shows the reason`() {
        openMeals(viewModelOver(MealSettingsUiState(settings = settings, guests = 3, error = "defaultGuests must be at least 1.")))

        compose.onNodeWithText("defaultGuests must be at least 1.").assertIsDisplayed()
    }

    @Test
    fun `settings that could not be read offer to try again`() {
        val viewModel = viewModelOver(MealSettingsUiState(error = "offline"))
        openMeals(viewModel)

        compose.onNodeWithText("offline").assertIsDisplayed()
        compose.onNodeWithText("Réessayer").performClick()

        verify(exactly = 2) { viewModel.loadMealSettings() }
    }
}
