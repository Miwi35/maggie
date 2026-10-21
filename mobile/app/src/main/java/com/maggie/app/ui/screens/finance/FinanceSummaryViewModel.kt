package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.BankConnection
import com.maggie.app.data.model.CushionStatus
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ReviewPeriod
import com.maggie.app.data.model.ReviewQueue
import com.maggie.app.data.model.ReviewState
import com.maggie.app.data.model.SavingsAdvances
import com.maggie.app.data.model.TreasuryOutlook
import com.maggie.app.data.model.bankConnectionNeedsAction
import com.maggie.app.data.model.cushionStateLabel
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.reviewMonthOf
import com.maggie.app.data.model.independenceSummary
import com.maggie.app.data.model.lastSyncLabel
import com.maggie.app.data.model.toBanner
import com.maggie.app.data.model.treasuryDate
import com.maggie.app.data.repository.BankConnectionRepository
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.CushionRepository
import com.maggie.app.data.repository.FinanceDashboardRepository
import com.maggie.app.data.repository.LoanRepository
import com.maggie.app.data.repository.ProjectRepository
import com.maggie.app.data.repository.RecurringOperationRepository
import com.maggie.app.data.repository.ReviewRepository
import com.maggie.app.data.repository.SavingsAdvancesRepository
import com.maggie.app.data.repository.TreasuryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** One aspect of a Finance menu that a summary page draws a block for (MAG-426). */
enum class SummaryBlock {
    CUSHION,
    SAVINGS_ADVANCES,
    OUTLOOK,
    REVIEW,
    RULES,
    RECURRING_OPERATIONS,
    PROJECTS,
    LOANS,
    INDEPENDENCE,
    BANKS,
    CATEGORIES,
    LAST_SYNC,
}

/** What a block shows. A failed block says so on its own: the others, and the way into its screen, stay. */
sealed interface BlockState {
    data object Loading : BlockState

    /**
     * [needsAttention] colours the block: an overspent figure, a consent to renew. [marks] is the
     * block's visual when it has one: one mark per item in order (the projects by priority), `true`
     * for an item that needs attention.
     */
    data class Ready(
        val lines: List<String>,
        val needsAttention: Boolean = false,
        val marks: List<Boolean> = emptyList(),
    ) : BlockState

    data class Unavailable(val message: String) : BlockState
}

/**
 * The figures of the summary pages: Trésorerie, Revue, Planification, Configuration, and the last
 * synchronisation line of Comptes.
 *
 * A page asks for its own blocks ([load]) so that opening Trésorerie does not fetch the loans.
 * Each block is its own request and its own state: one that fails leaves the others drawn.
 */
class FinanceSummaryViewModel(
    private val cushionRepository: CushionRepository,
    private val advancesRepository: SavingsAdvancesRepository,
    private val treasuryRepository: TreasuryRepository,
    private val reviewRepository: ReviewRepository,
    private val ruleRepository: CategorizationRuleRepository,
    private val recurringRepository: RecurringOperationRepository,
    private val loanRepository: LoanRepository,
    private val projectRepository: ProjectRepository,
    private val dashboardRepository: FinanceDashboardRepository,
    private val bankRepository: BankConnectionRepository,
    private val categoryRepository: CategoryRepository,
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val _blocks = MutableStateFlow<Map<SummaryBlock, BlockState>>(emptyMap())
    val blocks: StateFlow<Map<SummaryBlock, BlockState>> = _blocks

    fun load(requested: Collection<SummaryBlock>) {
        requested.forEach { block ->
            if (_blocks.value[block] == null) set(block, BlockState.Loading)
            viewModelScope.launch { set(block, fetch(block)) }
        }
    }

    private fun set(block: SummaryBlock, state: BlockState) {
        _blocks.value = _blocks.value + (block to state)
    }

    private suspend fun fetch(block: SummaryBlock): BlockState = when (block) {
        SummaryBlock.CUSHION -> cushionRepository.getStatus().toBlock(::cushionBlock)
        SummaryBlock.SAVINGS_ADVANCES -> advancesRepository.getSavingsAdvances().toBlock(::advancesBlock)
        SummaryBlock.OUTLOOK -> treasuryRepository.getOutlook().toBlock(::outlookBlock)
        SummaryBlock.REVIEW -> {
            // The period the block counts: what happened since the last review, the lines to qualify and the recognised ones.
            val month = reviewMonthOf(today())
            reviewRepository.getList(ReviewPeriod.SINCE_REVIEW, month, ReviewState.ALL, limit = 1).toBlock(::reviewBlock)
        }
        SummaryBlock.RULES -> ruleRepository.getRules().toBlock { countBlock(it.size, "règle", "règles", "Aucune règle") }
        SummaryBlock.RECURRING_OPERATIONS -> recurringRepository.getOverview().toBlock {
            countBlock(
                it.totals.activeCount, "opération récurrente", "opérations récurrentes", "Aucune opération récurrente",
                extra = if (it.totals.activeCount > 0) "${formatCents(it.totals.monthlyCostCents)} par mois" else null,
            )
        }
        SummaryBlock.PROJECTS -> projectRepository.getProjects().toBlock(::projectsBlock)
        SummaryBlock.LOANS -> loanRepository.getLoans().toBlock {
            countBlock(
                it.size, "prêt", "prêts", "Aucun prêt",
                extra = if (it.isNotEmpty()) "${formatCents(it.sumOf { loan -> loan.monthlyPaymentCents })} de mensualités" else null,
            )
        }
        SummaryBlock.INDEPENDENCE -> {
            val date = today()
            dashboardRepository.getDashboard(date.year, date.monthValue)
                .toBlock { BlockState.Ready(listOf(independenceSummary(it.tiles.planning.independence))) }
        }
        SummaryBlock.BANKS -> bankRepository.getConnections().toBlock(::banksBlock)
        SummaryBlock.CATEGORIES -> categoryRepository.getCategories()
            .toBlock { countBlock(it.size, "catégorie", "catégories", "Aucune catégorie") }
        SummaryBlock.LAST_SYNC -> bankRepository.getConnections().toBlock(::lastSyncBlock)
    }

    private fun <T> Result<T>.toBlock(ready: (T) -> BlockState): BlockState = fold(
        onSuccess = ready,
        onFailure = { BlockState.Unavailable(it.message ?: "Indisponible") },
    )
}

internal fun cushionBlock(status: CushionStatus): BlockState {
    if (!status.isConfigured) return BlockState.Ready(listOf("Pas encore réglé"))

    return BlockState.Ready(
        listOf(
            "${formatCents(status.currentCents)} / ${formatCents(status.targetCents)}",
            cushionStateLabel(status.state),
        ),
    )
}

internal fun advancesBlock(advances: SavingsAdvances): BlockState =
    if (advances.openAdvances.isEmpty()) {
        BlockState.Ready(listOf("Aucune avance en cours"))
    } else {
        BlockState.Ready(
            listOf(
                "${formatCents(advances.totalOutstandingCents)} à remettre",
                "${advances.openAdvances.size} avance${if (advances.openAdvances.size > 1) "s" else ""} ouverte${if (advances.openAdvances.size > 1) "s" else ""}",
            ),
            needsAttention = advances.salaryDelay.isLate,
        )
    }

internal fun outlookBlock(outlook: TreasuryOutlook): BlockState {
    val banner = outlook.toBanner()
        ?: return BlockState.Ready(listOf("Aucun découvert prévu avant le salaire"))

    return BlockState.Ready(
        listOf(
            "Découvert prévu le ${treasuryDate(banner.overdraftOn)}",
            "${formatCents(banner.balanceBeforeSalaryCents)} avant le salaire",
        ),
        needsAttention = true,
    )
}

/**
 * The Revue block (MAG-539), one for the three it replaces: the lines still to qualify, and the lines
 * an operation recognised since the last review. It asks for attention while something waits.
 */
internal fun reviewBlock(queue: ReviewQueue): BlockState {
    val toQualify = queue.unrecognisedCount
    val recognised = queue.recognisedCount
    if (toQualify == 0 && recognised == 0) return BlockState.Ready(listOf("Rien à revoir"))

    return BlockState.Ready(
        listOf(
            when (toQualify) {
                0 -> "Rien à qualifier"
                1 -> "1 transaction à qualifier"
                else -> "$toQualify transactions à qualifier"
            },
            when (recognised) {
                0 -> "Aucune reconnue depuis la dernière revue"
                1 -> "1 reconnue depuis la dernière revue"
                else -> "$recognised reconnues depuis la dernière revue"
            },
        ),
        needsAttention = toQualify > 0 || recognised > 0,
    )
}

/** « 2 projets », the first in priority order, and a warning when one waits for a project placed after it. */
internal fun projectsBlock(projects: List<Project>): BlockState {
    if (projects.isEmpty()) return BlockState.Ready(listOf("Aucun projet"))
    val unmet = projects.count { it.unmetDependencies.isNotEmpty() }
    return BlockState.Ready(
        listOfNotNull(
            "${projects.size} projet${if (projects.size > 1) "s" else ""}",
            "En premier : ${projects.first().name}",
            if (unmet > 0) "$unmet dépendance${if (unmet > 1) "s" else ""} non respectée${if (unmet > 1) "s" else ""}" else null,
        ),
        needsAttention = unmet > 0,
        marks = projects.map { it.unmetDependencies.isNotEmpty() },
    )
}

internal fun countBlock(count: Int, one: String, many: String, none: String, extra: String? = null): BlockState =
    BlockState.Ready(
        listOfNotNull(
            if (count == 0) none else "$count ${if (count > 1) many else one}",
            extra,
        ),
    )

internal fun banksBlock(connections: List<BankConnection>): BlockState {
    if (connections.isEmpty()) return BlockState.Ready(listOf("Aucune banque connectée"))

    val toRenew = connections.count(::bankConnectionNeedsAction)
    return BlockState.Ready(
        listOf(
            "${connections.size} banque${if (connections.size > 1) "s" else ""}",
            if (toRenew == 0) "Consentements à jour" else "$toRenew à reconnecter",
        ),
        needsAttention = toRenew > 0,
    )
}

internal fun lastSyncBlock(connections: List<BankConnection>): BlockState {
    if (connections.isEmpty()) return BlockState.Ready(listOf("Aucune banque connectée"))

    val latest = connections.mapNotNull { it.lastSyncedAt }
        .maxByOrNull { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: Instant.EPOCH }
    return BlockState.Ready(listOf(lastSyncLabel(latest)))
}
