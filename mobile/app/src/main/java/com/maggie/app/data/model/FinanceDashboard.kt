package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class IndependenceRente(
    val categoryId: String,
    val categoryName: String,
    val monthlyCents: Int = 0,
    val sharePercent: Int = 0,
)

/**
 * How far the rentes cover the train de vie (MAG-46). Both sides measured over
 * the last complete months; the target date and the curve are Premium, so
 * there is no field for them.
 */
@Serializable
data class IndependenceCounter(
    val coveragePercent: Int = 0,
    val lifestyleCents: Int = 0,
    val passiveIncomeCents: Int = 0,
    val gapCents: Int = 0,
    val sampleMonths: Int = 3,
    val isMeasurable: Boolean = false,
    val hasPassiveIncomeCategories: Boolean = false,
    val isReached: Boolean = false,
    val nextMilestonePercent: Int? = null,
    val nextMilestoneGapCents: Int? = null,
    val byCategory: List<IndependenceRente> = emptyList(),
)

/**
 * The one line the card leads with. Each case is a different thing to say:
 * a percentage, where to start, or that there is nothing to measure yet —
 * never 0 %, which would read as a verdict.
 */
fun independenceSummary(counter: IndependenceCounter): String = when {
    !counter.hasPassiveIncomeCategories ->
        "Aucune catégorie déclarée comme rente"
    !counter.isMeasurable ->
        "Pas encore de train de vie mesuré"
    counter.isReached ->
        "${counter.coveragePercent} % — les rentes couvrent le train de vie"
    else ->
        "${counter.coveragePercent} % du train de vie — il manque ${formatCents(counter.gapCents)} par mois"
}

/** One account of the Comptes tile; [isCushion] marks the safety net, which is not « disponible ». */
@Serializable
data class TileAccount(
    val id: String,
    val name: String,
    val bank: String? = null,
    val balanceCents: Int = 0,
    val currency: String = "EUR",
    val isCushion: Boolean = false,
)

/** The Comptes tile: the total, how much of it is the cushion, and each account. */
@Serializable
data class AccountsTile(
    val totalCents: Int = 0,
    val cushionCents: Int = 0,
    val availableCents: Int = 0,
    val accounts: List<TileAccount> = emptyList(),
)

/** One root envelope of the month, as a bar of the Budget tile. */
@Serializable
data class TileEnvelope(
    val envelopeId: String,
    val name: String,
    val budgetCents: Int = 0,
    val consumedCents: Int = 0,
    val isOverspent: Boolean = false,
    val isOvercommitted: Boolean = false,
)

/** The cushion line of the Budget tile; [state] is `building`, `recharging` or `complete`. */
@Serializable
data class TileCushion(
    val state: String = "building",
    val targetCents: Int = 0,
    val currentCents: Int = 0,
    val coveragePercent: Int = 0,
    val monthsCovered: Double = 0.0,
    val isConfigured: Boolean = false,
)

/** The salary expected this month; [source] is `declared`, `expected` or `measured`. */
@Serializable
data class ExpectedSalary(
    val monthlyCents: Int = 0,
    val source: String = "expected",
    val expectedOn: String? = null,
)

/**
 * The Budget tile. [expectedSalary] is null when no reference salary is known (never a 0 €
 * salary), [balanceBeforeSalary] when the outlook cannot say.
 */
@Serializable
data class BudgetTile(
    val envelopes: List<TileEnvelope> = emptyList(),
    val cushion: TileCushion = TileCushion(),
    val expectedSalary: ExpectedSalary? = null,
    val balanceBeforeSalary: BalanceBeforeSalary? = null,
)

/** The Planification tile: how far the rentes cover the lifestyle, and what is left to save. */
@Serializable
data class PlanningTile(
    val independence: IndependenceCounter = IndependenceCounter(),
    val savingCapacity: SavingCapacity = SavingCapacity(),
)

@Serializable
data class FinanceTiles(
    val accounts: AccountsTile = AccountsTile(),
    val budget: BudgetTile = BudgetTile(),
    val planning: PlanningTile = PlanningTile(),
)

/**
 * `GET /api/finance/dashboard`: the scores behind the rings and the curve (null from a server that
 * does not send them: the screen then draws neither) and the three tiles (MAG-420).
 */
@Serializable
data class FinanceDashboard(
    val year: Int = 0,
    val month: Int = 0,
    val scores: FinanceScores? = null,
    val tiles: FinanceTiles = FinanceTiles(),
)
