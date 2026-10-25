package com.maggie.app.ui.screens.finance

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.AdjustmentLine
import com.maggie.app.data.model.AdjustmentSeason
import com.maggie.app.data.model.AdjustmentSuggestion
import com.maggie.app.data.model.EnvelopeAdjustment
import com.maggie.app.data.model.EnvelopeAdjustments
import com.maggie.app.data.model.EnvelopeAdjustmentsRequest
import com.maggie.app.data.model.EnvelopeAdjustmentsResult
import com.maggie.app.data.repository.BudgetRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.YearMonth

/** « Ajuster » (MAG-461): the figures, the suggestion only where the envelope does not fit, the gestures. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class EnvelopeAdjustmentsScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val repository = mockk<BudgetRepository>()

    private val loisirs = EnvelopeAdjustment(
        envelopeId = "env-loisirs", categoryId = "cat-loisirs", categoryName = "Loisirs", categoryPath = "Loisirs",
        amountCents = 10000, actualCents = 14000, targetEnvelopeId = "env-loisirs-oct", targetAmountCents = 10000,
        suggestion = AdjustmentSuggestion(14000, AdjustmentSuggestion.RAISE, "Dépassée trois mois de suite"),
    )
    private val courses = EnvelopeAdjustment(
        envelopeId = "env-courses", categoryId = "cat-courses", categoryName = "Courses", categoryPath = "Courses",
        amountCents = 40000, actualCents = 39000, targetEnvelopeId = "env-courses-oct", targetAmountCents = 40000,
    )
    private val cinema = EnvelopeAdjustment(
        envelopeId = "env-cinema", categoryId = "cat-cinema", categoryName = "Cinéma", categoryPath = "Cinéma",
        amountCents = 2000, actualCents = 4500, targetAmountCents = 2000,
        suggestion = AdjustmentSuggestion(
            4500, AdjustmentSuggestion.RAISE, "Chaque hiver", AdjustmentSeason(fromMonth = 11, toMonth = 12),
        ),
    )

    private fun open() {
        coEvery { repository.getEnvelopeAdjustments("2026-09") } returns Result.success(
            EnvelopeAdjustments("2026-09", "2026-10", 2, listOf(courses, loisirs, cinema)),
        )
        val viewModel = EnvelopeAdjustmentsViewModel(repository, YearMonth.of(2026, 9))
        compose.setContent {
            EnvelopeAdjustmentsContent(viewModel, SnackbarHostState())
        }
        compose.waitForIdle()
    }

    @Test
    fun `each envelope shows its actual and next month, the suggestion only where it does not fit`() {
        open()

        compose.onNodeWithText("Réel de septembre 2026 : 390,00\u00A0€ sur 400,00\u00A0€").assertExists()
        compose.onNodeWithText("Octobre 2026 : 400,00\u00A0€").assertExists()
        compose.onNodeWithTag(UiTags.BUDGET_ADJUST_SUGGESTION_PREFIX + "Loisirs").assertExists()
        compose.onNodeWithText("Dépassée trois mois de suite").assertExists()
        compose.onNodeWithTag(UiTags.BUDGET_ADJUST_SUGGESTION_PREFIX + "Courses").assertDoesNotExist()
    }

    @Test
    fun `a seasonal suggestion names its season`() {
        open()

        compose.onNodeWithText("Saison : Novembre–décembre").assertExists()
    }

    @Test
    fun `plus moves the amount, enregistrer writes it and nothing is written before`() {
        coEvery { repository.applyEnvelopeAdjustments(any()) } returns Result.success(EnvelopeAdjustmentsResult())
        open()
        compose.onNodeWithTag(UiTags.BUDGET_ADJUST_SAVE).assertIsNotEnabled()

        compose.onNodeWithTag(UiTags.BUDGET_ADJUST_PLUS_PREFIX + "Courses").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Octobre 2026 : 401,00\u00A0€").assertExists()
        compose.onNodeWithTag(UiTags.BUDGET_ADJUST_SAVE).assertIsEnabled()
        coVerify(exactly = 0) { repository.applyEnvelopeAdjustments(any()) }

        compose.onNodeWithTag(UiTags.BUDGET_ADJUST_SAVE).performClick()
        compose.waitForIdle()

        coVerify {
            repository.applyEnvelopeAdjustments(
                EnvelopeAdjustmentsRequest("2026-09", listOf(AdjustmentLine("cat-courses", 40100, false))),
            )
        }
    }

    @Test
    fun `appliquer writes the proposed amount of that envelope`() {
        coEvery { repository.applyEnvelopeAdjustments(any()) } returns Result.success(EnvelopeAdjustmentsResult())
        open()

        compose.onNodeWithTag(UiTags.budgetAdjustApply("Loisirs")).performClick()
        compose.waitForIdle()

        coVerify {
            repository.applyEnvelopeAdjustments(
                EnvelopeAdjustmentsRequest("2026-09", listOf(AdjustmentLine("cat-loisirs", 14000, false))),
            )
        }
    }

    @Test
    fun `tout appliquer writes every suggestion at once`() {
        coEvery { repository.applyEnvelopeAdjustments(any()) } returns Result.success(EnvelopeAdjustmentsResult())
        open()
        compose.onNodeWithTag(UiTags.BUDGET_ADJUST_APPLY_ALL).assertIsDisplayed()

        compose.onNodeWithTag(UiTags.BUDGET_ADJUST_APPLY_ALL).performClick()
        compose.waitForIdle()

        coVerify {
            repository.applyEnvelopeAdjustments(
                EnvelopeAdjustmentsRequest(
                    "2026-09",
                    listOf(AdjustmentLine("cat-loisirs", 14000, false), AdjustmentLine("cat-cinema", 4500, true)),
                ),
            )
        }
    }

    @Test
    fun `a failed read offers to try again and applies nothing`() {
        coEvery { repository.getEnvelopeAdjustments(any()) } returns Result.failure(RuntimeException("réseau"))
        val viewModel = EnvelopeAdjustmentsViewModel(repository, YearMonth.of(2026, 9))
        compose.setContent { EnvelopeAdjustmentsContent(viewModel, SnackbarHostState()) }
        compose.waitForIdle()

        compose.onNodeWithText("Réessayer").assertExists()
        coVerify(exactly = 0) { repository.applyEnvelopeAdjustments(any()) }
    }
}
