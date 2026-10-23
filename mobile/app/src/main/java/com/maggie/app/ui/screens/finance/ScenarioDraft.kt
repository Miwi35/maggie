package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.ScenarioRepaymentRequest
import com.maggie.app.data.api.ScenarioWriteRequest
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.Placement
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.Scenario
import java.util.Locale

enum class ScenarioField { NAME, SAVING, REPAYMENTS }

/** One early repayment as the editor holds it: [month] as typed (`AAAA-MM`), [euros] unless [total] repays the whole capital. */
data class RepaymentDraft(
    val key: Int,
    val id: String? = null,
    val loanId: String = "",
    val month: String = "",
    val total: Boolean = false,
    val euros: String = "",
) {
    private val cents: Int? get() = parseCents(euros)?.takeIf { it > 0 }

    val isComplete: Boolean
        get() = loanId.isNotBlank() && MONTH.matches(month.trim()) && (total || cents != null)

    fun toRequest(): ScenarioRepaymentRequest? {
        if (!isComplete) return null
        return ScenarioRepaymentRequest(loanId = loanId, month = month.trim(), amountCents = if (total) null else cents, id = id)
    }

    companion object {
        private val MONTH = Regex("""\d{4}-(0[1-9]|1[0-2])""")
    }
}

/**
 * What the editor of a scenario holds while it is filled (MAG-551): text as typed, the kept projects in the
 * order of the scenario. A scenario never edits the projects themselves — only which ones it keeps, and in
 * what order.
 */
data class ScenarioDraft(
    val name: String = "",
    /** The kept projects, in the scenario's order. */
    val projectIds: List<String> = emptyList(),
    /** Euros typed; empty leaves the saving to the planning. */
    val saving: String = "",
    val repayments: List<RepaymentDraft> = emptyList(),
    val placementIds: List<String> = emptyList(),
) {
    private val savingCents: Int? get() = parseCents(saving)?.takeIf { it >= 0 }

    fun errors(): Map<ScenarioField, String> = buildMap {
        if (name.isBlank()) put(ScenarioField.NAME, "Donnez un nom au scénario.")
        if (saving.isNotBlank() && savingCents == null) put(ScenarioField.SAVING, "Indiquez un montant en euros.")
        if (repayments.any { !it.isComplete }) {
            put(ScenarioField.REPAYMENTS, "Chaque remboursement anticipé demande un prêt, un mois (AAAA-MM) et un montant, ou le capital restant.")
        }
    }

    fun toRequest(): ScenarioWriteRequest? {
        if (errors().isNotEmpty()) return null
        return ScenarioWriteRequest.of(
            name = name.trim(),
            projectIds = projectIds,
            repayments = repayments.map { it.toRequest() ?: return null },
            placementIds = placementIds,
            monthlySavingCents = if (saving.isBlank()) null else savingCents,
        )
    }

    /** Keeps the project when it is not kept, drops it when it is; a kept project goes last. */
    fun toggleProject(id: String): ScenarioDraft =
        copy(projectIds = if (id in projectIds) projectIds - id else projectIds + id)

    /** One place up or down among the kept projects. */
    fun moveProject(id: String, offset: Int): ScenarioDraft {
        val from = projectIds.indexOf(id)
        val to = from + offset
        if (from == -1 || to !in projectIds.indices) return this
        return copy(projectIds = projectIds.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun togglePlacement(id: String): ScenarioDraft =
        copy(placementIds = if (id in placementIds) placementIds - id else placementIds + id)

    companion object {
        /** A new scenario starts from every project, in the owner's order. */
        fun starting(projects: List<Project>): ScenarioDraft = ScenarioDraft(projectIds = projects.map { it.id })

        fun of(scenario: Scenario, knownProjectIds: Collection<String>?): ScenarioDraft = ScenarioDraft(
            name = scenario.name,
            projectIds = scenario.projectIds.filter { knownProjectIds == null || it in knownProjectIds },
            saving = scenario.monthlySaving?.let { String.format(Locale.FRANCE, "%.2f", it / 100.0) }.orEmpty(),
            repayments = scenario.repayments.mapIndexed { index, repayment ->
                RepaymentDraft(
                    key = index,
                    id = repayment.id,
                    loanId = repayment.loanId,
                    month = repayment.month,
                    total = repayment.amountCents == null,
                    euros = repayment.amountCents?.let { String.format(Locale.FRANCE, "%.2f", it / 100.0) }.orEmpty(),
                )
            },
            placementIds = scenario.placements.map { it.placementId },
        )
    }
}

/** The scenario on screen in the editor: a new one when [scenarioId] is null. [done] tells the screen to close. */
data class ScenarioEditor(
    val scenarioId: String? = null,
    val draft: ScenarioDraft = ScenarioDraft(),
    /** Set by the first save: an empty field is not an error while the form is still being filled. */
    val showErrors: Boolean = false,
    val isSaving: Boolean = false,
    /** What the server refused, as it said it. */
    val error: String? = null,
    val done: Boolean = false,
) {
    val isNew: Boolean get() = scenarioId == null
    val errors: Map<ScenarioField, String> get() = if (showErrors) draft.errors() else emptyMap()
}

/** What the editor picks from: the projects (the owner's order), the loans to repay early, the placements. */
data class ScenarioOptions(
    val projects: List<Project> = emptyList(),
    val loans: List<Loan> = emptyList(),
    val placements: List<Placement> = emptyList(),
)
