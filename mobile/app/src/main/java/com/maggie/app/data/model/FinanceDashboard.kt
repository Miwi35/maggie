package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Serializable
data class IndependenceRente(
    val categoryId: String,
    val categoryName: String,
    val monthlyCents: Int = 0,
    val sharePercent: Int = 0,
)

/**
 * How far the rentes cover the train de vie (MAG-46). Both sides measured over
 * the last complete months. [projection] carries the date N, the curve and
 * the dated milestones (MAG-549); its default keeps a payload cached before it
 * decoding. [renteSources] says which simulated rents the projection counts (MAG-558), likewise
 * defaulted.
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
    val projection: IndependenceProjection = IndependenceProjection(),
    val renteSources: RenteSources = RenteSources(),
)

/** One month of the curve; [coverage] is 0..1 (the wire may send `1` or `1.0`). */
@Serializable
data class CoveragePoint(
    val month: String,
    val coverage: Double = 0.0,
)

/**
 * When the rentes cover the train de vie (MAG-549). Months are `YYYY-MM`. [nDate] is null when
 * 100 % is not reached within 20 years (or nothing is measurable); [curve] runs monthly from
 * [startMonth] to [nDate] and is empty when no train de vie is measured; [milestones] is keyed
 * by "25", "50", "75", "100": the month a step is reached, [startMonth] when already behind,
 * null when not reached within 20 years. An empty [startMonth] means the server sent none.
 */
@Serializable
data class IndependenceProjection(
    val startMonth: String = "",
    val nDate: String? = null,
    val curve: List<CoveragePoint> = emptyList(),
    val milestones: Map<String, String?> = emptyMap(),
) {
    val isKnown: Boolean get() = startMonth.isNotEmpty()
}

/** One placement whose final capital, withdrawn at 4 % a year, pays a rent from [fromMonth] (`YYYY-MM`). */
@Serializable
data class PlacementRenteItem(
    val placementId: String,
    val name: String,
    val monthlyRentCents: Long = 0,
    val fromMonth: String? = null,
    val coveragePoints: Int? = null,
)

/** One investment project paying a rent from [fromMonth] until [untilMonth] (its resale); null = open. */
@Serializable
data class InvestmentRenteItem(
    val projectId: String,
    val name: String,
    val monthlyRentCents: Long = 0,
    val fromMonth: String? = null,
    val untilMonth: String? = null,
    val coveragePoints: Int? = null,
)

@Serializable
data class PlacementRentes(
    val count: Int = 0,
    val monthlyRentCents: Long = 0,
    val items: List<PlacementRenteItem> = emptyList(),
)

@Serializable
data class InvestmentRentes(
    val count: Int = 0,
    val monthlyRentCents: Long = 0,
    val items: List<InvestmentRenteItem> = emptyList(),
)

/**
 * The simulated rents the projection counts on top of the measured ones (MAG-558): placements
 * withdrawn at [withdrawalRateBasisPoints] a year, and investments' rents. Never part of the
 * measured coverage; always shown as simulations.
 */
@Serializable
data class RenteSources(
    val withdrawalRateBasisPoints: Int = 400,
    val placements: PlacementRentes = PlacementRentes(),
    val investments: InvestmentRentes = InvestmentRentes(),
) {
    val isEmpty: Boolean get() = placements.count <= 0 && investments.count <= 0
}

enum class IndependenceRenteSourceKind { PLACEMENTS, INVESTMENTS }

/** « Placements · 3 · ≈ 315 € / mois à terme » or « Investissements · 1 · ≈ 160 € / mois de loyer ». */
data class IndependenceRenteSourceRow(val kind: IndependenceRenteSourceKind, val text: String)

const val INDEPENDENCE_SOURCES_TITLE = "Sources des rentes à venir"

/** The rows of the section, a source with no item omitted; empty when there is nothing to say. */
fun independenceRenteSourceRows(sources: RenteSources): List<IndependenceRenteSourceRow> = buildList {
    if (sources.placements.count > 0) {
        add(
            IndependenceRenteSourceRow(
                IndependenceRenteSourceKind.PLACEMENTS,
                "Placements · ${sources.placements.count} · ≈ ${formatEuros(sources.placements.monthlyRentCents)} / mois à terme",
            ),
        )
    }
    if (sources.investments.count > 0) {
        add(
            IndependenceRenteSourceRow(
                IndependenceRenteSourceKind.INVESTMENTS,
                "Investissements · ${sources.investments.count} · ≈ ${formatEuros(sources.investments.monthlyRentCents)} / mois de loyer",
            ),
        )
    }
}

/** « Simulation · retrait de 4 % par an sur le capital placé », only when placements are counted. */
fun independenceRenteSourcesCaption(sources: RenteSources): String? {
    if (sources.placements.count <= 0) return null
    val rate = java.math.BigDecimal(sources.withdrawalRateBasisPoints).movePointLeft(2)
        .stripTrailingZeros().toPlainString().replace('.', ',')
    return "Simulation · retrait de $rate % par an sur le capital placé"
}

private val INDEPENDENCE_MONTH = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.FRENCH)

private const val WITHIN_20_YEARS = "d'ici 20 ans"

/** « septembre 2045 » from `2045-09`; the text itself when it is not a month. */
fun independenceMonthLabel(month: String): String =
    runCatching { YearMonth.parse(month).format(INDEPENDENCE_MONTH) }.getOrDefault(month)

/**
 * The date N line, a plain statement: no blame, no advice. Null when there is nothing to say —
 * not measurable, or a payload without projection.
 */
fun independenceDateN(counter: IndependenceCounter): String? = when {
    !counter.isMeasurable || !counter.hasPassiveIncomeCategories -> null
    counter.isReached -> "Les rentes couvrent déjà le train de vie"
    !counter.projection.isKnown -> null
    counter.projection.nDate != null -> "Date N · ${independenceMonthLabel(counter.projection.nDate)}"
    else -> "N non atteinte $WITHIN_20_YEARS"
}

/** « dans 2 ans et 3 mois » from the start month to [IndependenceProjection.nDate]; null without a future N. */
fun independenceRemaining(projection: IndependenceProjection): String? {
    val target = projection.nDate ?: return null
    val total = runCatching {
        ChronoUnit.MONTHS.between(YearMonth.parse(projection.startMonth), YearMonth.parse(target)).toInt()
    }.getOrNull() ?: return null
    if (total <= 0) return null

    val years = total / 12
    val months = total % 12
    val yearsText = if (years == 1) "1 an" else "$years ans"
    return "dans " + when {
        years == 0 -> "$months mois"
        months == 0 -> yearsText
        else -> "$yearsText et $months mois"
    }
}

/** One dated step: « 25 % · mars 2028 », « 50 % · atteint », « 75 % · non atteint d'ici 20 ans ». */
data class IndependenceMilestoneRow(val percent: Int, val label: String) {
    val text: String get() = "$percent % · $label"
}

/** The four steps in order; empty when the server sent no projection. */
fun independenceMilestoneRows(projection: IndependenceProjection): List<IndependenceMilestoneRow> {
    if (!projection.isKnown) return emptyList()

    return listOf(25, 50, 75, 100).map { percent ->
        val month = projection.milestones[percent.toString()]
        IndependenceMilestoneRow(
            percent,
            when {
                month == null -> "non atteint $WITHIN_20_YEARS"
                month == projection.startMonth -> "atteint"
                else -> independenceMonthLabel(month)
            },
        )
    }
}

/** What a screen reader says of the curve: where it starts, where it ends, when N is reached. */
fun independenceCurveDescription(projection: IndependenceProjection): String {
    val first = projection.curve.firstOrNull() ?: return "Aucune courbe de couverture"
    val last = projection.curve.last()
    fun percent(point: CoveragePoint) = Math.round(point.coverage * 100)

    return buildString {
        append("Taux de couverture du train de vie par les rentes : ")
        append("${percent(first)} % en ${independenceMonthLabel(first.month)}, ")
        append("${percent(last)} % en ${independenceMonthLabel(last.month)}. ")
        append(
            projection.nDate?.let { "100 % atteint en ${independenceMonthLabel(it)}." }
                ?: "100 % non atteint $WITHIN_20_YEARS.",
        )
    }
}

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
    val rejectionsToSettleCount: Int = 0,
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

/**
 * What waits for the owner (MAG-421), each count the one the screen it opens shows. Zero or false
 * means nothing to do: the pill or the shortcut is not drawn.
 */
@Serializable
data class FinanceTodo(
    val toSortCount: Int = 0,
    val rejectionsToSettleCount: Int = 0,
    val reviewReady: Boolean = false,
    val advanceToRepayCents: Int = 0,
    val owedToMeCents: Int = 0,
    val iOweCents: Int = 0,
)

/** The account the « N rejets » pill opens: the one with the most rejections to settle, null when none. */
fun AccountsTile.accountWithRejections(): TileAccount? =
    accounts.filter { it.rejectionsToSettleCount > 0 }.maxByOrNull { it.rejectionsToSettleCount }

fun toSortPillText(count: Int): String? = if (count > 0) "$count à trier" else null

fun rejectionsPillText(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 rejet"
    else -> "$count rejets"
}

fun advancePillText(cents: Int): String? = if (cents > 0) "${formatCents(cents)} à remettre" else null

@Serializable
data class FinanceTiles(
    val accounts: AccountsTile = AccountsTile(),
    val budget: BudgetTile = BudgetTile(),
    val planning: PlanningTile = PlanningTile(),
)

/** A project after the next one: its name, its price and the month it falls. */
@Serializable
data class NextProjectFollowing(
    val projectId: String,
    val name: String,
    val amountCents: Int = 0,
    val purchaseMonth: String = "",
)

/** What this month's overspending pushes back: [name] is bought [months] later, in [newPurchaseMonth]. */
@Serializable
data class NextProjectDelay(
    val projectId: String,
    val name: String,
    val months: Int = 0,
    val overspentCents: Int = 0,
    val envelopes: List<String> = emptyList(),
    val newPurchaseMonth: String = "",
)

/** The « Prochain projet » block (MAG-529): [purchaseMonth] is `YYYY-MM`, [following] the two after it. */
@Serializable
data class NextProject(
    val projectId: String,
    val name: String,
    val amountCents: Int = 0,
    val gatheredCents: Int = 0,
    val purchaseMonth: String = "",
    val following: List<NextProjectFollowing> = emptyList(),
    val delay: NextProjectDelay? = null,
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
    val todo: FinanceTodo = FinanceTodo(),
    /** The first project of the planning (MAG-529); null with no project, or none within the 36 months. */
    val nextProject: NextProject? = null,
)
