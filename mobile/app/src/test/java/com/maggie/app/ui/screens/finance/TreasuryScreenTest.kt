package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.centerRight
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.AdviceAlternative
import com.maggie.app.data.model.BalanceBeforeSalary
import com.maggie.app.data.model.TransactionPrefill
import com.maggie.app.data.model.TreasuryAdvice
import com.maggie.app.data.model.TreasuryDay
import com.maggie.app.data.model.TreasuryMovement
import com.maggie.app.data.model.TreasuryOutlook
import com.maggie.app.data.model.TreasuryOverdraft
import com.maggie.app.data.model.TreasurySalary
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.repository.TreasuryRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TreasuryScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val late = TreasuryOutlook(
        today = "2026-10-13",
        salary = TreasurySalary(isConfigured = true, expectedOn = "2026-10-10", isLate = true, delayDays = 3),
        balanceCents = -12000,
        projection = listOf(
            TreasuryDay("2026-10-13", -12000),
            TreasuryDay("2026-10-14", -70000),
            TreasuryDay("2026-10-24", -70000),
        ),
        balanceBeforeSalary = BalanceBeforeSalary("2026-10-24", -70000),
        overdraft = TreasuryOverdraft(isForecast = true, onDate = "2026-10-14", worstBalanceCents = -70000, limitCents = 0),
        advice = TreasuryAdvice(
            amountCents = 90000,
            sourceAccountId = "s1",
            sourceAccountName = "Livret A",
            sourceBalanceCents = 50000,
            sourceAmountCents = 50000,
            alternatives = listOf(AdviceAlternative("s2", "Matelas", 30000, 30000, isCushion = true)),
            uncoveredCents = 10000,
            touchesCushion = true,
        ),
        outstandingAdvancesCents = 0,
    )

    private lateinit var repository: TreasuryRepository

    private fun open(
        outlook: TreasuryOutlook,
        onPrepare: (String, String, TransactionPrefill) -> Unit = { _, _, _ -> },
    ): TreasuryViewModel {
        repository = mockk<TreasuryRepository>()
        coEvery { repository.getOutlook(any()) } returns Result.success(outlook)
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns MutableSharedFlow<MercureEvent>()
        val auth = mockk<AuthRepository>()
        coEvery { auth.getUserId() } returns "user-1"
        val viewModel = TreasuryViewModel(repository, mercure, auth)

        compose.setContent {
            TreasuryScreen(viewModel = viewModel, onBack = {}, onPrepareAdvance = onPrepare)
        }

        return viewModel
    }

    @Test
    fun `a salary day not set asks for it and announces nothing else`() {
        open(TreasuryOutlook())

        compose.onNodeWithText("Quel jour le salaire est-il attendu ?").assertIsDisplayed()
        compose.onNodeWithText("Avance conseillée").assertDoesNotExist()
        compose.onNodeWithText("Solde projeté").assertDoesNotExist()
        compose.onNodeWithText("Découvert autorisé").assertDoesNotExist()
    }

    @Test
    fun `a late salary says how many days, then the overdraft and the advance`() {
        open(late)

        compose.onNodeWithText("Salaire en retard de 3 jours").assertIsDisplayed()
        compose.onNodeWithText("Solde des comptes courants : ${formatCents(-12000)}").assertIsDisplayed()
        compose.onNodeWithText("Zone de découvert : solde sous ${formatCents(0)} (trait en pointillés, jours en carrés)")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Découvert prévu le 14 octobre : ${formatCents(-70000)}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Avancer ${formatCents(90000)} depuis Livret A (${formatCents(50000)} sur ce compte)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Matelas — ${formatCents(30000)} (matelas)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("il manque encore ${formatCents(10000)}", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("entame le matelas, il se reconstituera quand tu rendras l'avance", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the recurring charges after the salary day are listed with their day and amount`() {
        open(
            late.copy(
                projection = late.projection + TreasuryDay(
                    "2026-10-26",
                    -167700,
                    listOf(TreasuryMovement("s1", "Taxe foncière", -97700, "expected", source = "recurring")),
                ),
            ),
        )

        compose.onNodeWithText("Mouvements prévus").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("26 octobre · Taxe foncière (récurrent)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(formatCents(-97700)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the horizon choice asks for that many months and keeps the page`() {
        open(late)
        coEvery { repository.getOutlook(12) } returns Result.success(late.copy(balanceCents = -99000))

        compose.onNodeWithText("Fin du mois suivant").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 an").performScrollTo().performClick()
        compose.waitForIdle()

        coVerify { repository.getOutlook(12) }
        compose.onNodeWithText("Solde des comptes courants : ${formatCents(-99000)}").assertIsDisplayed()
        compose.onNodeWithText("Salaire en retard de 3 jours").assertIsDisplayed()
    }

    @Test
    fun `the cumulated inflows and outflows are drawn under the balance, with no verdict figure`() {
        open(
            late.copy(
                projection = late.projection.mapIndexed { i, day ->
                    day.copy(cumulativeInflowCents = i * 1000, cumulativeOutflowCents = i * 5000)
                },
            ),
        )

        compose.onNodeWithText("Entrées et sorties prévues, cumulées").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TREASURY_FLOWS_CHART).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Entrées cumulées (trait plein) · Sorties cumulées (pointillés)").assertIsDisplayed()
    }

    @Test
    fun `touching a day of the curves spells out its date, balance and movements`() {
        open(
            late.copy(
                projection = listOf(
                    TreasuryDay("2026-10-13", -12000),
                    TreasuryDay(
                        "2026-10-26",
                        -167700,
                        cumulativeInflowCents = 0,
                        cumulativeOutflowCents = 97700,
                        movements = listOf(TreasuryMovement("s1", "Taxe foncière", -97700, "expected", source = "recurring")),
                    ),
                ),
            ),
        )

        compose.onNodeWithTag(UiTags.TREASURY_CHART_DETAIL).performScrollTo().assertTextEquals("Touchez un jour pour le détail.")
        compose.onNodeWithTag(UiTags.TREASURY_CHART).performScrollTo().performTouchInput { click(centerRight) }
        compose.onNodeWithTag(UiTags.TREASURY_CHART_DETAIL).performScrollTo().assertTextEquals(
            "26 octobre : solde ${formatCents(-167700)} — Taxe foncière (récurrent) (${formatCents(-97700)})",
        )

        compose.onNodeWithTag(UiTags.TREASURY_FLOWS_CHART).performScrollTo().performTouchInput { click(centerRight) }
        compose.onNodeWithTag(UiTags.TREASURY_FLOWS_DETAIL).performScrollTo().assertTextEquals(
            "26 octobre : entrées cumulées ${formatCents(0)}, sorties cumulées ${formatCents(97700)}" +
                " — Taxe foncière (récurrent) (${formatCents(-97700)})",
        )
    }

    @Test
    fun `without a forecast overdraft the period is said clear in neutral words`() {
        open(late.copy(overdraft = TreasuryOverdraft(), advice = null))

        compose.onNodeWithText("Aucun découvert prévu sur la période projetée.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a salary received says so and has no advance to prepare`() {
        open(
            late.copy(
                salary = late.salary.copy(isLate = false, isReceived = true, delayDays = 0),
                overdraft = TreasuryOverdraft(),
                advice = null,
            ),
        )

        compose.onNodeWithText("Salaire reçu").assertIsDisplayed()
        compose.onNodeWithText("Salaire en retard", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TREASURY_PREPARE_ADVANCE).assertDoesNotExist()
    }

    @Test
    fun `preparing the advance hands over the source account and the prefilled transaction`() {
        var prepared: Triple<String, String, TransactionPrefill>? = null
        open(late) { id, name, prefill -> prepared = Triple(id, name, prefill) }

        compose.onNodeWithTag(UiTags.TREASURY_PREPARE_ADVANCE).performScrollTo().performClick()

        assertEquals(
            Triple("s1", "Livret A", TransactionPrefill("Avance vers le compte courant", -50000, "spent", "2026-10-13")),
            prepared,
        )
    }

    @Test
    fun `a negative overdraft limit is refused with its message`() {
        open(late)

        compose.onNodeWithTag(UiTags.TREASURY_OVERDRAFT_LIMIT).performScrollTo().performTextClearance()
        compose.onNodeWithTag(UiTags.TREASURY_OVERDRAFT_LIMIT).performTextInput("-5")
        compose.onNodeWithText("Enregistrer").performScrollTo().performClick()

        compose.onNodeWithText("La limite ne peut pas être négative").assertIsDisplayed()
    }
}
