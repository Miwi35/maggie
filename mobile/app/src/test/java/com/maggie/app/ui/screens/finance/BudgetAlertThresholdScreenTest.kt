package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.BudgetLine
import com.maggie.app.data.model.BudgetStatus
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.repository.BudgetRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.TransactionRepository
import com.maggie.app.screentest.ScreenRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The alert threshold of an envelope: shown on the phone, set on the tablet (MAG-49). */
@RunWith(AndroidJUnit4::class)
class BudgetAlertThresholdScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val budgetRepository = mockk<BudgetRepository>()

    private fun open() {
        coEvery { budgetRepository.getBudgetStatus(any(), any()) } returns Result.success(
            BudgetStatus(
                year = 2026,
                month = 10,
                budgets = listOf(
                    BudgetLine(
                        id = "env-1",
                        categoryId = "cat-1",
                        categoryName = "Loisirs",
                        amountCents = 30000,
                        year = 2026,
                        month = 10,
                        consumedCents = 25000,
                        remainingCents = 5000,
                        availableCents = 5000,
                    ),
                ),
            ),
        )
        coEvery { budgetRepository.setAlertThreshold(any(), any()) } returns Result.success(Envelope(id = "env-1"))
        val categoryRepository = mockk<CategoryRepository>()
        coEvery { categoryRepository.getCategories() } returns Result.success(emptyList())

        val authRepository = mockk<AuthRepository>()
        coEvery { authRepository.getUserId() } returns "user-1"
        val mercureService = mockk<MercureService>()
        every { mercureService.subscribe(any()) } returns emptyFlow()
        val viewModel = BudgetViewModel(
            budgetRepository, categoryRepository, mockk<TransactionRepository>(), mercureService, authRepository,
            LocalDate.of(2026, 10, 15),
        )
        compose.setContent { BudgetScreen(viewModel = viewModel, onBack = {}) }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the phone shows the threshold without offering to change it`() {
        open()

        compose.onNodeWithText("Alerte à 80 % puis à 100 %").assertIsDisplayed()
        compose.onNodeWithTag("budget_alert_threshold").assertHasNoClickAction()
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `the tablet sets the threshold from the envelope`() {
        open()

        compose.onNodeWithTag("budget_alert_threshold").assertHasClickAction().performClick()
        compose.onNodeWithText("Alerte de Loisirs").assertIsDisplayed()

        compose.onNodeWithTag("alert_threshold_field").performTextClearance()
        compose.onNodeWithTag("alert_threshold_field").performTextInput("100")
        compose.onNodeWithText("Enregistrer").assertIsNotEnabled()

        compose.onNodeWithTag("alert_threshold_field").performTextClearance()
        compose.onNodeWithTag("alert_threshold_field").performTextInput("90")
        compose.onNodeWithText("Enregistrer").performClick()
        compose.waitForIdle()

        coVerify { budgetRepository.setAlertThreshold("env-1", 90) }
    }
}
