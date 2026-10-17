package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.AccountsTile
import com.maggie.app.data.model.BalanceBeforeSalary
import com.maggie.app.data.model.BudgetTile
import com.maggie.app.data.model.ExpectedSalary
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.FinanceTiles
import com.maggie.app.data.model.FinanceTodo
import com.maggie.app.data.model.PlanningTile
import com.maggie.app.data.model.SavingCapacity
import com.maggie.app.data.model.TileAccount
import com.maggie.app.data.model.TileCushion
import com.maggie.app.data.model.TileEnvelope
import com.maggie.app.data.model.formatCents
import com.maggie.app.screentest.FakeMonthBalance
import com.maggie.app.screentest.FakeUpcoming
import com.maggie.app.screentest.FakeFinanceDashboard
import com.maggie.app.screentest.FakeLoadedFinanceDashboard
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The three tiles of the finance dashboard (MAG-420): each carries its figures and leads to its menu.
 *
 * What the figures are is the API's (`GetFinanceDashboardTest`); what is checked here is that they are
 * drawn, that the unknown ones say so instead of showing 0, and that a tap asks for the right route.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1400dp-xxhdpi")
class FinanceDashboardTilesScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val tiles = FinanceTiles(
        accounts = AccountsTile(
            totalCents = 604550,
            cushionCents = 420000,
            availableCents = 184550,
            accounts = listOf(
                TileAccount(id = "a", name = "Compte courant", bank = "Boursorama", balanceCents = 184550),
                TileAccount(id = "b", name = "Livret A", balanceCents = 420000, isCushion = true),
            ),
        ),
        budget = BudgetTile(
            envelopes = listOf(
                TileEnvelope("e1", "Courses", budgetCents = 20000, consumedCents = 12000),
                TileEnvelope("e2", "Loisirs", budgetCents = 10000, consumedCents = 13000, isOverspent = true),
                TileEnvelope("e3", "Sorties", budgetCents = 10000, consumedCents = 2000, isOvercommitted = true),
            ),
            cushion = TileCushion(
                state = "recharging",
                targetCents = 700000,
                currentCents = 420000,
                coveragePercent = 60,
                isConfigured = true,
            ),
            expectedSalary = ExpectedSalary(monthlyCents = 235000, source = "declared", expectedOn = "2026-10-28"),
            balanceBeforeSalary = BalanceBeforeSalary(onDate = "2026-10-27", balanceCents = 31000),
        ),
        planning = PlanningTile(
            savingCapacity = SavingCapacity(
                monthlyNetIncomeCents = 235000,
                loanPaymentsCents = 21000,
                estimatedLifestyleCents = 16666,
                netCapacityCents = 197334,
                isIncomeKnown = true,
            ),
        ),
    )

    private fun open(
        tiles: FinanceTiles = this.tiles,
        todo: FinanceTodo = FinanceTodo(),
        onOpenIncidents: (TileAccount) -> Unit = {},
        onOpen: (String) -> Unit = {},
    ) {
        compose.setContent {
            FinanceDashboardScreen(
                monthBalanceViewModel = FakeMonthBalance().viewModel,
                upcomingViewModel = FakeUpcoming().viewModel,
                viewModel = FakeLoadedFinanceDashboard(FinanceDashboard(year = 2026, month = 10, tiles = tiles, todo = todo)).viewModel,
                onBack = {},
                onOpen = onOpen,
                onOpenIncidents = onOpenIncidents,
            )
        }
    }

    private val busy = FinanceTodo(toSortCount = 3, rejectionsToSettleCount = 1, reviewReady = true, advanceToRepayCents = 80000, owedToMeCents = 20000, iOweCents = 28000)

    private val tilesWithRejection = tiles.copy(
        accounts = tiles.accounts.copy(
            accounts = tiles.accounts.accounts.map { if (it.id == "a") it.copy(rejectionsToSettleCount = 1) else it },
        ),
    )

    @Test
    fun `the three tiles are drawn`() {
        open()

        compose.onNodeWithTag(UiTags.FINANCE_TILE_ACCOUNTS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_TILE_BUDGET).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_TILE_PLANNING).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the accounts tile adds the accounts up, sets the cushion aside and marks it`() {
        open()

        compose.onNodeWithText(formatCents(604550)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("dont ${formatCents(420000)} de matelas — ${formatCents(184550)} disponibles")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Boursorama · EUR · Compte courant").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("EUR · Livret A · matelas").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the budget tile draws one bar per envelope, the cushion, the salary and the balance before it`() {
        open()

        listOf("e1", "e2", "e3").forEach { id ->
            compose.onNodeWithTag(UiTags.financeTileEnvelope(id), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Matelas : en recharge · 60 %").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${formatCents(235000)} · le 28 octobre").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${formatCents(31000)} · le 27 octobre").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a budget tile without envelope or cushion says so`() {
        open(FinanceTiles())

        compose.onNodeWithText("Aucune enveloppe sur ce mois.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Matelas non configuré").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an undeclared salary and an unknown balance say so and show no amount`() {
        var opened: String? = null
        open(FinanceTiles(budget = BudgetTile(expectedSalary = null, balanceBeforeSalary = null))) { opened = it }

        compose.onNodeWithText("salaire non déclaré").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("solde attendu inconnu").performScrollTo().assertIsDisplayed()
        compose.onNode(
            hasTestTag(UiTags.FINANCE_TILE_BALANCE_BEFORE_SALARY) and
                hasAnyDescendant(hasText(formatCents(0), substring = true)),
            useUnmergedTree = true,
        ).assertDoesNotExist()

        // The link to fill the salary in wins over the tile's own tap.
        compose.onNodeWithTag(UiTags.REFERENCE_SALARY_LINK).performScrollTo().performClick()
        assertEquals(Screen.Cushion.route, opened)
    }

    @Test
    fun `the planning tile gives the independence and the net saving capacity`() {
        open()

        compose.onNodeWithText("Indépendance financière").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Aucune catégorie déclarée comme rente").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Capacité d'épargne nette").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${formatCents(197334)} / mois").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "${formatCents(235000)} de revenu − ${formatCents(21000)} de mensualités − " +
                "${formatCents(16666)} de train de vie",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an unknown income asks to be declared instead of showing a capacity`() {
        open(FinanceTiles())

        compose.onNodeWithText("Renseignez votre revenu dans le matelas pour l'obtenir.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a tap on a tile asks for its menu`() {
        val opened = mutableListOf<String>()
        open { opened += it }

        compose.onNodeWithTag(UiTags.FINANCE_TILE_ACCOUNTS).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.FINANCE_TILE_BUDGET).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.FINANCE_TILE_PLANNING).performScrollTo().performClick()

        assertEquals(listOf(Screen.AccountList.route, Screen.BudgetList.route, Screen.Treasury.route), opened)
    }

    @Test
    fun `the next project is a fixed example, drawn and not clickable`() {
        val opened = mutableListOf<String>()
        open { opened += it }

        compose.onNodeWithTag(UiTags.FINANCE_NEXT_PROJECT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Vélo électrique").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("dans 4 mois").assertIsDisplayed()
        compose.onNodeWithText("${formatCents(NEXT_PROJECT_EXAMPLE.savedCents)} sur ${formatCents(NEXT_PROJECT_EXAMPLE.targetCents)}")
            .assertIsDisplayed()
        compose.onNodeWithText(NEXT_PROJECT_EXAMPLE.negativeReturn).assertIsDisplayed()

        compose.onNodeWithTag(UiTags.FINANCE_NEXT_PROJECT).performClick()

        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun `a month the server could not compute draws no tile and no example project`() {
        compose.setContent {
            FinanceDashboardScreen(
                monthBalanceViewModel = FakeMonthBalance().viewModel,
                upcomingViewModel = FakeUpcoming().viewModel,
                viewModel = FakeFinanceDashboard(error = "Service indisponible").viewModel,
                onBack = {},
                onOpen = {},
            )
        }

        compose.onNodeWithText("Service indisponible").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_TILE_ACCOUNTS).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.FINANCE_NEXT_PROJECT).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `on a wide window the tiles keep their menus`() {
        val opened = mutableListOf<String>()
        open { opened += it }

        compose.onNodeWithTag(UiTags.FINANCE_TILE_ACCOUNTS).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_TILE_ACCOUNTS).performClick()

        assertEquals(listOf(Screen.AccountList.route), opened)
    }

    @Test
    fun `the accounts tile says what is to sort and what was rejected`() {
        open(tilesWithRejection, busy)

        compose.onNodeWithTag(UiTags.FINANCE_PILL_TO_SORT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3 à trier").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_PILL_REJECTIONS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 rejet").assertIsDisplayed()
    }

    @Test
    fun `several rejections are said in the plural`() {
        open(tilesWithRejection, busy.copy(rejectionsToSettleCount = 2))

        compose.onNodeWithText("2 rejets").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the to-sort pill opens the to-sort screen and not the accounts menu`() {
        val opened = mutableListOf<String>()
        open(tilesWithRejection, busy) { opened += it }

        compose.onNodeWithTag(UiTags.FINANCE_PILL_TO_SORT).performScrollTo().performClick()

        assertEquals(listOf(Screen.TransactionsToSort.route), opened)
    }

    @Test
    fun `the rejections pill opens the incidents of the account that has them`() {
        val incidents = mutableListOf<String>()
        val routes = mutableListOf<String>()
        open(tilesWithRejection, busy, onOpenIncidents = { incidents += it.id }) { routes += it }

        compose.onNodeWithTag(UiTags.FINANCE_PILL_REJECTIONS).performScrollTo().performClick()

        assertEquals(listOf("a"), incidents)
        assertEquals(emptyList<String>(), routes)
    }

    @Test
    fun `the pill says what is owed net and leads to the créances screen`() {
        val opened = mutableListOf<String>()
        open(tilesWithRejection, busy) { opened += it }

        compose.onNodeWithTag(UiTags.FINANCE_PILL_OWED).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tu dois ${formatCents(8000)}").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_PILL_OWED).performClick()

        assertEquals(listOf(Screen.PeerDebts.route), opened)
    }

    @Test
    fun `the pill says on te doit when the owner is the one owed`() {
        open(tilesWithRejection, busy.copy(owedToMeCents = 53500, iOweCents = 0))

        compose.onNodeWithText("On te doit ${formatCents(53500)}").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the budget tile says how much is to give back and leads to the advances`() {
        val opened = mutableListOf<String>()
        open(tilesWithRejection, busy) { opened += it }

        compose.onNodeWithTag(UiTags.FINANCE_PILL_ADVANCE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${formatCents(80000)} à remettre").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_PILL_ADVANCE).performClick()

        assertEquals(listOf(Screen.SavingsAdvances.route), opened)
    }

    @Test
    fun `the review shortcut leads to the monthly review when it is ready`() {
        val opened = mutableListOf<String>()
        open(tilesWithRejection, busy) { opened += it }

        compose.onNodeWithTag(UiTags.FINANCE_REVIEW_SHORTCUT).assertIsDisplayed()
        compose.onNodeWithText("Revue ce soir").performClick()

        assertEquals(listOf(Screen.MonthlyReview.route), opened)
    }

    @Test
    fun `with nothing to do there is no pill and no shortcut`() {
        open(tiles, FinanceTodo())

        compose.onNodeWithTag(UiTags.FINANCE_PILL_TO_SORT).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.FINANCE_PILL_REJECTIONS).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.FINANCE_PILL_ADVANCE).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.FINANCE_REVIEW_SHORTCUT).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.FINANCE_PILL_OWED).assertDoesNotExist()
    }

    @Test
    fun `créances and dettes that cancel out leave no pill`() {
        open(tiles, FinanceTodo(owedToMeCents = 20000, iOweCents = 20000))

        compose.onNodeWithTag(UiTags.FINANCE_PILL_OWED).assertDoesNotExist()
    }
}
