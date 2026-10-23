package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A project of the owner (MAG-519): what it costs, how much it counts for his wellbeing, when it
 * should happen, and the projects it waits for. Read from `GET /api/finance/projects`, ordered by
 * [position] (0 first, the priority order the owner sets by dragging).
 *
 * [dependsOn] holds IRIs on the API Platform resource (`/api/finance/projects/{id}`) and plain ids
 * in the answer of `POST /api/finance/projects/reorder`: [dependencyIds] reads both.
 * [unmetDependencies] are plain ids — the projects this one waits for that come after it.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val amountCents: Int = 0,
    val currency: String = "EUR",
    val wellbeing: String = ProjectWellbeing.MEDIUM.key,
    val wellbeingScore: Int = 0,
    val position: Int = 0,
    val dateMode: String = ProjectDateMode.NONE.key,
    val targetOn: String? = null,
    val dependsOn: List<String> = emptyList(),
    val note: String? = null,
    val unmetDependencies: List<String> = emptyList(),
    /** Where the money comes from besides the salary (MAG-524/525), in the order the API stores them. */
    val fundingSources: List<ProjectFundingSource> = emptyList(),
    /** What is left on the salary: the amount minus the sources, never below 0. */
    val salaryCents: Int = 0,
    /** Set when the project is a rented purchase (MAG-557): its rent, its loan and its planned resale. Null for a plain project. */
    val investment: ProjectInvestment? = null,
) {
    val dependencyIds: List<String> get() = dependsOn.map { it.substringAfterLast('/') }

    val loanSource: ProjectFundingSource? get() = fundingSources.firstOrNull { it.kind == FundingKind.LOAN.key }

    /** What the resales of other projects bring to this one: they are posed by those investments, never edited here. */
    val resaleCents: Int get() = fundingSources.filter { it.kind == FundingKind.RESALE.key }.sumOf { it.amountCents }
}

/**
 * What makes a project an investment (MAG-557), as `investment` on the project. The price is the project's
 * amount; [downPaymentCents] is what the loan leaves to pay; [monthlyBalanceCents] is the rent less the
 * loan's payment, negative when the box costs more than it brings. Written from the admin and by Maggie,
 * read here.
 */
@Serializable
data class ProjectInvestment(
    val projectId: String = "",
    val priceCents: Int = 0,
    val downPaymentCents: Int = 0,
    val loanId: String? = null,
    val loanPaymentCents: Int = 0,
    val monthlyRentCents: Int = 0,
    val monthlyBalanceCents: Int = 0,
    val resale: ProjectResale? = null,
)

/** The planned resale: the month (`YYYY-MM`), what is left once the loan is repaid, and the project it is set aside for (none: it speeds up the next ones). */
@Serializable
data class ProjectResale(
    val month: String = "",
    val netAmountCents: Int = 0,
    val targetProjectId: String? = null,
)

/**
 * One source of a project's money: [accountId] for savings and capital, [placementId] for the simulated
 * placement a capital source leaves (MAG-556), [loanId] for the projected loan, [sourceProjectId] for the
 * resale of another project (MAG-557).
 */
@Serializable
data class ProjectFundingSource(
    val kind: String,
    val amountCents: Int,
    val accountId: String? = null,
    val placementId: String? = null,
    val loanId: String? = null,
    val sourceProjectId: String? = null,
)

/**
 * The kinds of source, in the order a project names them. A [RESALE] is posed by the investment it comes
 * from: the phone and the tablet show it and never edit it ([editable]).
 */
enum class FundingKind(val key: String, val label: String, val summary: String, val editable: Boolean = true) {
    SAVINGS("savings", "Économies", "économies"),
    CAPITAL("capital", "Capital", "capital"),
    LOAN("loan", "Emprunt", "emprunt"),
    RESALE("resale", "Revente", "revente", editable = false),
    ;

    companion object {
        fun of(key: String): FundingKind? = entries.firstOrNull { it.key == key }
    }
}

/** « économies + emprunt + salaire »: the kinds present, in a fixed order, then the salary when it still pays; null when nothing funds the project. */
fun projectFundingSummary(project: Project): String? {
    val kinds = FundingKind.entries.filter { kind -> project.fundingSources.any { it.kind == kind.key } }
    val parts = kinds.map { it.summary } + if (project.salaryCents > 0) listOf("salaire") else emptyList()
    return parts.joinToString(" + ").ifEmpty { null }
}

/** One priced way to pay a project (cash, loan, partial), as `GET /api/finance/projects/{id}/options` sends it. */
@Serializable
data class ProjectOption(
    val principalCents: Int = 0,
    val downPaymentCents: Int = 0,
    val interestCents: Int = 0,
    val insuranceCents: Int = 0,
    val feesCents: Int = 0,
    val yieldCents: Int = 0,
    val totalCostCents: Int = 0,
    val netCostCents: Int = 0,
    val monthlyPaymentCents: Int = 0,
    val durationMonths: Int = 0,
    val availableInMonths: Int? = null,
    val capitalKeptCents: Int = 0,
    val nImpactMonths: Int? = null,
)

@Serializable
data class ProjectOptions(
    val projectId: String,
    val amountCents: Int = 0,
    val currency: String = "EUR",
    val netCapacityCents: Int = 0,
    val loanRateBasisPoints: Int = 0,
    val loanMonths: Int = 0,
    val downPaymentCents: Int = 0,
    val yieldBasisPoints: Int = 0,
    val cash: ProjectOption = ProjectOption(),
    val loan: ProjectOption = ProjectOption(),
    val partial: ProjectOption = ProjectOption(),
)

/** How much a project counts for the owner's wellbeing, with the score the API derives from it. */
enum class ProjectWellbeing(val key: String, val label: String, val score: Int) {
    ESSENTIAL("essential", "Indispensable", 4),
    HIGH("high", "Élevé", 3),
    MEDIUM("medium", "Moyen", 2),
    COMFORT("comfort", "Confort", 1),
    ;

    companion object {
        fun of(key: String): ProjectWellbeing = entries.firstOrNull { it.key == key } ?: MEDIUM
    }
}

/** No date, a locked date, or a date it must be done by. */
enum class ProjectDateMode(val key: String, val label: String) {
    NONE("none", "Sans date"),
    FIXED("fixed", "Date fixe"),
    DEADLINE("deadline", "Date limite"),
    ;

    val needsDate: Boolean get() = this != NONE

    companion object {
        fun of(key: String): ProjectDateMode = entries.firstOrNull { it.key == key } ?: NONE
    }
}

fun projectIri(id: String): String = "/api/finance/projects/$id"

private val PROJECT_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE)

/** « Sans date », « Date fixe · 1 mars 2027 », « Date limite · 15 janvier 2027 ». */
fun projectDateLabel(project: Project): String {
    val mode = ProjectDateMode.of(project.dateMode)
    val date = project.targetOn?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    if (mode == ProjectDateMode.NONE || date == null) return mode.label
    return "${mode.label} · ${PROJECT_DATE.format(date)}"
}

/** « après Déménagement », « après Déménagement, Voiture »; null for a project that waits for none. */
fun projectDependenciesLabel(project: Project, all: List<Project>): String? {
    val names = project.dependencyIds.mapNotNull { id -> all.firstOrNull { it.id == id }?.name }
    return if (names.isEmpty()) null else "après ${names.joinToString(", ")}"
}

private val RESALE_MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE)

/** « juin 2028 » from `2028-06`; the month as it came when it does not parse. */
fun investmentMonthLabel(month: String): String =
    runCatching { YearMonth.parse(month.take(7)).format(RESALE_MONTH) }.getOrDefault(month)

/** « Loyer perçu : 160,00 EUR par mois ». */
fun investmentRentLabel(investment: ProjectInvestment, currency: String = "EUR"): String =
    "Loyer perçu : ${formatCents(investment.monthlyRentCents, currency)} par mois"

/** « Mensualité du prêt : 141,08 EUR par mois »; null without a loan. */
fun investmentLoanLabel(investment: ProjectInvestment, currency: String = "EUR"): String? =
    if (investment.loanPaymentCents > 0) "Mensualité du prêt : ${formatCents(investment.loanPaymentCents, currency)} par mois" else null

/** « Solde : +18,92 EUR par mois » — the rent less the loan's payment; « −… » when the box costs more than it brings. */
fun investmentBalanceLabel(investment: ProjectInvestment, currency: String = "EUR"): String {
    val balance = investment.monthlyBalanceCents
    val sign = if (balance > 0) "+" else if (balance < 0) "−" else ""
    return "Solde : $sign${formatCents(kotlin.math.abs(balance), currency)} par mois"
}

/**
 * « Revendre en juin 2028 pour 10000,00 EUR net, pour l'apport de Japon » — or, with no target, « …, pour
 * avancer les projets suivants ». Null while no resale is planned. A target that is no longer in [all]
 * reads as no target.
 */
fun investmentResaleLabel(investment: ProjectInvestment, all: List<Project>, currency: String = "EUR"): String? {
    val resale = investment.resale ?: return null
    val target = resale.targetProjectId?.let { id -> all.firstOrNull { it.id == id } }
    val destination = if (target != null) "pour l'apport de ${target.name}" else "pour avancer les projets suivants"
    return "Revendre en ${investmentMonthLabel(resale.month)} pour ${formatCents(resale.netAmountCents, currency)} net, $destination"
}
