package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A scenario of the projects (MAG-551): a selection and an order of existing projects, simulated early
 * repayments, placements and a monthly saving — never a change to the projects themselves. Read from
 * `GET /api/finance/scenarios` (the summary) and `GET /api/finance/scenario-comparison` (the detail, which
 * adds [curve] and [milestones]).
 *
 * [kind] is `user`, `auto` (Rapide, Équilibré, Qualité de vie: drafts the owner can duplicate or make current)
 * or `advisor` (a draft suggested by Maggie). At most one scenario is [current], chosen by the owner; none
 * is by default. [nDate] is `YYYY-MM` and null when N is not reached within 20 years.
 */
@Serializable
data class Scenario(
    val id: String,
    val name: String,
    val kind: String = KIND_USER,
    val status: String = STATUS_DRAFT,
    val current: Boolean = false,
    val projects: List<ScenarioProject> = emptyList(),
    val repayments: List<ScenarioRepayment> = emptyList(),
    val placements: List<ScenarioPlacement> = emptyList(),
    /** Cents; null: the scenario saves what the planning already counts on. */
    val monthlySaving: Int? = null,
    val nDate: String? = null,
    val metrics: ScenarioMetrics = ScenarioMetrics(),
    /** Detail only: the coverage of the train de vie, month by month. */
    val curve: List<CoveragePoint> = emptyList(),
    /** Detail only: keyed "25", "50", "75", "100". */
    val milestones: Map<String, String?> = emptyMap(),
) {
    val isAuto: Boolean get() = kind == KIND_AUTO
    val isAdvisor: Boolean get() = kind == KIND_ADVISOR
    val isUser: Boolean get() = kind == KIND_USER

    /** The ids of the kept projects, in the scenario's order. */
    val projectIds: List<String> get() = projects.sortedBy { it.position }.map { it.projectId }

    companion object {
        const val KIND_USER = "user"
        const val KIND_AUTO = "auto"
        const val KIND_ADVISOR = "advisor"
        const val STATUS_DRAFT = "draft"
        const val STATUS_SAVED = "saved"
    }
}

@Serializable
data class ScenarioProject(
    val projectId: String,
    val position: Int = 0,
)

/**
 * An early repayment simulated in [month] (`YYYY-MM`); a null [amountCents] repays the whole capital. [effect]
 * (partial only) is `duration` or `payment`; null follows the loan's offer.
 */
@Serializable
data class ScenarioRepayment(
    val id: String? = null,
    val loanId: String,
    val month: String,
    val amountCents: Int? = null,
    val effect: String? = null,
) {
    val isTotal: Boolean get() = amountCents == null

    companion object {
        const val EFFECT_DURATION = "duration"
        const val EFFECT_PAYMENT = "payment"
    }
}

/**
 * What a repayment of a scenario comes to, as `GET /api/finance/scenarios/{id}` figures it (`repaymentEffects`):
 * the capital [immobilizedCents], the IRA ([penaltyCents]), the remaining interest it spares, the [realGainCents]
 * (that interest minus the IRA, negative when it costs more than it saves), the payment freed from the next month
 * ([freedMonthlyCents], added to the planning capacity) and the move of N in months ([nImpactMonths], negative:
 * sooner; null when N is not reached either way). Never computed on the phone.
 */
@Serializable
data class RepaymentFigures(
    val id: String,
    val loanId: String = "",
    val loanName: String = "",
    val month: String = "",
    val immobilizedCents: Int = 0,
    val effect: String? = null,
    val penaltyCents: Int = 0,
    val interestSavedCents: Int = 0,
    val realGainCents: Int = 0,
    val freedMonthlyCents: Int = 0,
    val nImpactMonths: Int? = null,
)

/** The detail of a scenario: only the figures of its repayments are read from it. */
@Serializable
data class ScenarioDetail(
    val repaymentEffects: List<RepaymentFigures> = emptyList(),
)

@Serializable
data class ScenarioPlacement(
    val placementId: String,
)

/** What a scenario gives; months are `YYYY-MM`, [interestPaid] is in cents. */
@Serializable
data class ScenarioMetrics(
    val projectsDoneBy: String? = null,
    val debtsEndDate: String? = null,
    val interestPaid: Int = 0,
    val wellbeing: Int = 0,
)

@Serializable
data class ScenarioList(
    val scenarios: List<Scenario> = emptyList(),
)

/** One line of the comparison: the gap of N between [scenarioId] and [againstId], already worded by the API. */
@Serializable
data class ScenarioDifference(
    val scenarioId: String,
    val againstId: String,
    val months: Int? = null,
    val sentence: String = "",
)

@Serializable
data class ScenarioComparison(
    val referenceId: String,
    val scenarios: List<Scenario> = emptyList(),
    val differences: List<ScenarioDifference> = emptyList(),
) {
    /** The sentence about N between [id] and the reference; null for the reference itself. */
    fun differenceOf(id: String): String? = differences.firstOrNull { it.scenarioId == id }?.sentence?.takeIf { it.isNotBlank() }
}

/** The answer of every write: the scenario it concerns, when it still exists. */
@Serializable
data class ScenarioWriteResult(
    val success: Boolean = false,
    val scenario: Scenario? = null,
)

/** « Prêt auto · remboursement anticipé », or the generic title while the loan's name is not known. */
fun repaymentTitle(figures: RepaymentFigures?): String =
    if (figures == null || figures.loanName.isBlank()) "Remboursement anticipé" else "${figures.loanName} · remboursement anticipé"

/** « Total » or « Partiel 3000,00 € », with what a partial one shortens. */
fun repaymentKindLabel(repayment: ScenarioRepayment, figures: RepaymentFigures?): String {
    if (repayment.isTotal) return "Total"
    val effect = when (figures?.effect ?: repayment.effect) {
        ScenarioRepayment.EFFECT_DURATION -> " · réduire la durée"
        ScenarioRepayment.EFFECT_PAYMENT -> " · réduire la mensualité"
        else -> ""
    }
    return "Partiel ${formatCents(repayment.amountCents ?: 0)}$effect"
}

/** « N − 3 mois », « N + 2 mois », « N inchangée », or that N is out of reach. */
fun nImpactLabel(months: Int?): String = when {
    months == null -> "N non atteinte avant 20 ans"
    months == 0 -> "N inchangée"
    months < 0 -> "N − ${-months} mois"
    else -> "N + $months mois"
}

/** The figures of a repayment on one line: immobilized capital, IRA, real gain, effect on N, freed payment. */
fun repaymentFiguresLine(figures: RepaymentFigures): String =
    "Capital immobilisé ${formatCents(figures.immobilizedCents)} · IRA ${formatCents(figures.penaltyCents)} · " +
        "Gain réel ${formatCents(figures.realGainCents)} · ${nImpactLabel(figures.nImpactMonths)} · " +
        "Mensualité libérée ${formatCents(figures.freedMonthlyCents)}"

private val SCENARIO_MONTH = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.FRENCH)

/** « mars 2041 » from `2041-03`; the text itself when it is not a month. */
fun scenarioMonthLabel(month: String): String =
    runCatching { YearMonth.parse(month).format(SCENARIO_MONTH) }.getOrDefault(month)

private const val WITHIN_20_YEARS = "avant 20 ans"

/** « N : mars 2041 » or « N non atteinte avant 20 ans ». */
fun scenarioNLabel(nDate: String?): String =
    if (nDate == null) "N non atteinte $WITHIN_20_YEARS" else "N : ${scenarioMonthLabel(nDate)}"

/** The head line of the current scenario: « Scénario courant · Équilibré », or that there is none. */
fun scenarioCurrentTitle(current: Scenario?): String =
    if (current == null) "Aucun scénario courant" else "Scénario courant · ${current.name}"

/** The second line of the card: N of the current scenario, or the invitation to pick one. */
fun scenarioCurrentSubtitle(current: Scenario?): String =
    if (current == null) "Choisir un scénario" else scenarioNLabel(current.nDate)

/** « Automatique », « Suggestion du conseiller · brouillon », « Brouillon »; nothing for a saved scenario of the owner. */
fun scenarioKindLabel(scenario: Scenario): String? = when {
    scenario.isAdvisor -> "Suggestion du conseiller · brouillon"
    scenario.isAuto -> "Automatique · brouillon"
    scenario.status == Scenario.STATUS_DRAFT -> "Brouillon"
    else -> null
}

/**
 * The list in the order it is read: the current scenario, then the owner's, then the automatic ones, then
 * the advisor's suggestions — each group in the API's order. No ranking by result: nothing is "better".
 */
fun orderedScenarios(scenarios: List<Scenario>): List<Scenario> {
    fun rank(s: Scenario) = when {
        s.current -> 0
        s.isUser -> 1
        s.isAuto -> 2
        else -> 3
    }
    return scenarios.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
}

/** « Rapide (copie) », not already taken by another scenario: « Rapide (copie 2) ». */
fun scenarioCopyName(name: String, taken: Collection<String>): String {
    val base = "$name (copie"
    if ("$base)" !in taken) return "$base)"
    var n = 2
    while ("$base $n)" in taken) n++
    return "$base $n)"
}

/** One line of the comparison table: a label and one cell per scenario, in the order of the scenarios. */
data class ScenarioTableRow(val label: String, val cells: List<String>)

private const val BEYOND_HORIZON = "Au-delà de l'horizon"

private fun monthCell(month: String?, whenNull: String): String = month?.let(::scenarioMonthLabel) ?: whenNull

/** The figures side by side — dates, interest, wellbeing. Facts only: no column is marked as the best one. */
fun scenarioTableRows(scenarios: List<Scenario>): List<ScenarioTableRow> = listOf(
    ScenarioTableRow("Date N", scenarios.map { monthCell(it.nDate, "Non atteinte") }),
    ScenarioTableRow("Projets bouclés", scenarios.map { monthCell(it.metrics.projectsDoneBy, BEYOND_HORIZON) }),
    ScenarioTableRow("Dettes soldées", scenarios.map { monthCell(it.metrics.debtsEndDate, "—") }),
    ScenarioTableRow("Intérêts payés", scenarios.map { formatCents(it.metrics.interestPaid) }),
    ScenarioTableRow("Bien-être", scenarios.map { it.metrics.wellbeing.toString() }),
)

/** The months the superimposed curves run over: from the earliest first month to the latest last one. */
data class ScenarioCurveSpan(val first: YearMonth, val last: YearMonth) {
    val months: Int get() = (last.year * 12 + last.monthValue) - (first.year * 12 + first.monthValue)

    /** 0..1 along the axis; null for a month that is not one. */
    fun position(month: String): Float? {
        val parsed = runCatching { YearMonth.parse(month) }.getOrNull() ?: return null
        if (months == 0) return 0.5f
        val offset = (parsed.year * 12 + parsed.monthValue) - (first.year * 12 + first.monthValue)
        return (offset.toFloat() / months).coerceIn(0f, 1f)
    }
}

/** Null when no scenario has a curve (nothing measurable: no train de vie). */
fun scenarioCurveSpan(scenarios: List<Scenario>): ScenarioCurveSpan? {
    val months = scenarios.flatMap { s -> s.curve.mapNotNull { runCatching { YearMonth.parse(it.month) }.getOrNull() } }
    if (months.isEmpty()) return null
    return ScenarioCurveSpan(months.min(), months.max())
}

/** What a screen reader says of a curve: where it starts, where it ends, when N is reached. */
fun scenarioCurveDescription(scenario: Scenario): String {
    val first = scenario.curve.firstOrNull() ?: return "${scenario.name} : aucune courbe"
    val last = scenario.curve.last()
    fun percent(point: CoveragePoint) = Math.round(point.coverage * 100)

    return buildString {
        append("${scenario.name} : ${percent(first)} % en ${scenarioMonthLabel(first.month)}, ${percent(last)} % en ${scenarioMonthLabel(last.month)}. ")
        append(scenario.nDate?.let { "100 % atteint en ${scenarioMonthLabel(it)}." } ?: "100 % non atteint $WITHIN_20_YEARS.")
    }
}
