package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ScoreReason(
    val code: String,
    val categoryName: String? = null,
    val amountCents: Int? = null,
)

@Serializable
data class ScoreBudget(
    val totalBudgetedCents: Int = 0,
    val totalConsumedCents: Int = 0,
    val totalPlannedCents: Int = 0,
    val totalAvailableCents: Int = 0,
    val overspentCategories: List<String> = emptyList(),
)

@Serializable
data class ScoreCushion(
    val state: String = "building",
    val blocksGreenScore: Boolean = true,
)

@Serializable
data class ScoreComparison(
    val thisMonthCents: Int = 0,
    val sameMonthLastYearCents: Int = 0,
    val differenceCents: Int = 0,
    val isBetter: Boolean = false,
)

@Serializable
data class DailyScore(
    val score: String = "neutral",
    val year: Int = 0,
    val month: Int = 0,
    val reasons: List<ScoreReason> = emptyList(),
    val budget: ScoreBudget = ScoreBudget(),
    val cushion: ScoreCushion = ScoreCushion(),
    val comparison: ScoreComparison = ScoreComparison(),
)

/** Human-readable French label for a score code. */
fun scoreLabel(score: String): String = when (score) {
    "green" -> "Dans le vert"
    "neutral" -> "Dans les clous"
    "orange" -> "Attention"
    "red" -> "Dépassement"
    else -> score
}

/** Turns a reason code into the sentence a person reads. */
fun reasonText(reason: ScoreReason): String {
    val amount = formatCents(reason.amountCents ?: 0)

    return when (reason.code) {
        "mandatory_category_exceeded" ->
            "${reason.categoryName} (obligatoire) dépassée de $amount"
        "optional_category_exceeded" -> "${reason.categoryName} dépassée de $amount"
        "plans_exceed_category_budget" ->
            "Les dépenses planifiées feraient dépasser ${reason.categoryName} de $amount"
        "total_budget_exceeded" -> "Budget du mois dépassé de $amount"
        "cushion_incomplete" -> "Matelas incomplet : il manque $amount"
        "below_last_year" -> "$amount de moins qu'à la même période l'an dernier"
        "above_last_year" -> "$amount de plus qu'à la même période l'an dernier"
        "no_budget" -> "Aucune enveloppe sur cette période : rien à comparer"
        else -> reason.code
    }
}

@Serializable
data class DashboardAccount(
    val id: String,
    val name: String,
    val type: String = "checking",
    val balanceCents: Int = 0,
    val currency: String = "EUR",
    val isCushion: Boolean = false,
)

@Serializable
data class DashboardBalance(
    val totalCents: Int = 0,
    val cushionCents: Int = 0,
    val availableCents: Int = 0,
    val accounts: List<DashboardAccount> = emptyList(),
)

@Serializable
data class MonthlyFlow(
    val month: String,
    val incomeCents: Int = 0,
    val expenseCents: Int = 0,
    val netCents: Int = 0,
)

@Serializable
data class TopPost(
    val categoryId: String? = null,
    val categoryName: String? = null,
    val spentCents: Int = 0,
    val previousMonthCents: Int = 0,
    val changeCents: Int = 0,
)

@Serializable
data class IndependenceMilestone(
    val percent: Int = 0,
    val isReached: Boolean = false,
    val monthlyIncomeNeededCents: Int = 0,
)

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
    val loanPaymentsCents: Int = 0,
    val monthlyNeedCents: Int = 0,
    val coverageWithDebtPercent: Int = 0,
    val nextMilestonePercent: Int? = null,
    val nextMilestoneGapCents: Int? = null,
    val milestones: List<IndependenceMilestone> = emptyList(),
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

@Serializable
data class FinanceDashboard(
    val year: Int = 0,
    val month: Int = 0,
    val score: DailyScore = DailyScore(),
    val balance: DashboardBalance = DashboardBalance(),
    val monthlyFlows: List<MonthlyFlow> = emptyList(),
    val budgets: List<BudgetLine> = emptyList(),
    val topPosts: List<TopPost> = emptyList(),
    val savingCapacity: SavingCapacity = SavingCapacity(),
    val independence: IndependenceCounter = IndependenceCounter(),
)

/** How a post moved against last month, said plainly. */
fun postChangeLabel(post: TopPost): String = when {
    post.previousMonthCents == 0 -> "nouveau ce mois-ci"
    post.changeCents > 0 -> "+${formatCents(post.changeCents)} vs mois dernier"
    post.changeCents < 0 -> "${formatCents(post.changeCents)} vs mois dernier"
    else -> "stable"
}
