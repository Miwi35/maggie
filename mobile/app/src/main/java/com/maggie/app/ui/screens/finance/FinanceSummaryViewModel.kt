package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.BankConnection
import com.maggie.app.data.model.CushionStatus
import com.maggie.app.data.model.MonthlyReview
import com.maggie.app.data.model.RecognisedToControl
import com.maggie.app.data.model.SavingsAdvances
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.model.toControlCounter
import com.maggie.app.data.model.TreasuryOutlook
import com.maggie.app.data.model.bankConnectionNeedsAction
import com.maggie.app.data.model.cushionStateLabel
import com.maggie.app.data.model.formatCents
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
import com.maggie.app.data.repository.MonthlyReviewRepository
import com.maggie.app.data.repository.RecurringOperationRepository
import com.maggie.app.data.repository.SavingsAdvancesRepository
import com.maggie.app.data.repository.TransactionRepository
import com.maggie.app.data.repository.TreasuryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** One aspect of a Finance menu, loaded to know whether it has anything to show (MAG-426, MAG-518). */
enum class SummaryBlock {
    CUSHION,
    SAVINGS_ADVANCES,
    OUTLOOK,
    TO_SORT,
    TO_CONTROL,
    MONTHLY_REVIEW,
    RULES,
    RECURRING_OPERATIONS,
    LOANS,
    INDEPENDENCE,
    BANKS,
    CATEGORIES,
    LAST_SYNC,
}

/** What a block knows about its aspect. A failed block says so on its own: the others stay. */
sealed interface BlockState {
    data object Loading : BlockState

    /**
     * [needsAttention] marks the aspect's tab: an overspent figure, a consent to renew.
     * [hasData] is what picks the tab a menu opens on: the first aspect that has something to show.
     */
    data class Ready(
        val lines: List<String>,
        val needsAttention: Boolean = false,
        val hasData: Boolean = false,
    ) : BlockState

    data class Unavailable(val message: String) : BlockState
}

/**
 * What each aspect of Trésorerie, Revue, Planification and Configuration holds — enough for a menu
 * to open on the first aspect that has data — and the last synchronisation line of Comptes.
 *
 * A page asks for its own blocks ([load]) so that opening Trésorerie does not fetch the loans.
 * Each block is its own request and its own state: one that fails leaves the others drawn.
 */
class FinanceSummaryViewModel(
    private val cushionRepository: CushionRepository,
    private val advancesRepository: SavingsAdvancesRepository,
    private val treasuryRepository: TreasuryRepository,
    private val reviewRepository: MonthlyReviewRepository,
    private val ruleRepository: CategorizationRuleRepository,
    private val recurringRepository: RecurringOperationRepository,
    private val loanRepository: LoanRepository,
    private val dashboardRepository: FinanceDashboardRepository,
    private val bankRepository: BankConnectionRepository,
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: TransactionRepository,
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
        SummaryBlock.TO_SORT -> transactionRepository.getToSort().toBlock(::toSortBlock)
        SummaryBlock.TO_CONTROL -> transactionRepository.getRecognisedToControl(today().toString().take(7)).toBlock(::toControlBlock)
        SummaryBlock.MONTHLY_REVIEW -> {
            val date = today()
            reviewRepository.getReview(date.year, date.monthValue).toBlock(::monthlyReviewBlock)
        }
        SummaryBlock.RULES -> ruleRepository.getRules().toBlock { countBlock(it.size, "règle", "règles", "Aucune règle") }
        SummaryBlock.RECURRING_OPERATIONS -> recurringRepository.getOverview().toBlock {
            countBlock(
                it.totals.activeCount, "opération récurrente", "opérations récurrentes", "Aucune opération récurrente",
                extra = if (it.totals.activeCount > 0) "${formatCents(it.totals.monthlyCostCents)} par mois" else null,
            )
        }
        SummaryBlock.LOANS -> loanRepository.getLoans().toBlock {
            countBlock(
                it.size, "prêt", "prêts", "Aucun prêt",
                extra = if (it.isNotEmpty()) "${formatCents(it.sumOf { loan -> loan.monthlyPaymentCents })} de mensualités" else null,
            )
        }
        SummaryBlock.INDEPENDENCE -> {
            val date = today()
            dashboardRepository.getDashboard(date.year, date.monthValue)
                .toBlock {
                    val counter = it.tiles.planning.independence
                    BlockState.Ready(listOf(independenceSummary(counter)), hasData = counter.hasPassiveIncomeCategories)
                }
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
        hasData = true,
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
            hasData = true,
        )
    }

internal fun outlookBlock(outlook: TreasuryOutlook): BlockState {
    val banner = outlook.toBanner()
        ?: return BlockState.Ready(
            listOf("Aucun découvert prévu avant le salaire"),
            hasData = outlook.salary.isConfigured || outlook.projection.isNotEmpty(),
        )

    return BlockState.Ready(
        listOf(
            "Découvert prévu le ${treasuryDate(banner.overdraftOn)}",
            "${formatCents(banner.balanceBeforeSalaryCents)} avant le salaire",
        ),
        needsAttention = true,
        hasData = true,
    )
}

internal fun toSortBlock(pile: ToSortPile): BlockState =
    if (pile.total == 0) {
        BlockState.Ready(listOf("Rien à qualifier"))
    } else {
        BlockState.Ready(
            listOf("${pile.total} transaction${if (pile.total > 1) "s" else ""} à qualifier"),
            needsAttention = true,
            hasData = true,
        )
    }

internal fun toControlBlock(recognised: RecognisedToControl): BlockState =
    BlockState.Ready(
        listOf(toControlCounter(recognised.total)),
        needsAttention = recognised.total > 0,
        hasData = recognised.total > 0,
    )

internal fun monthlyReviewBlock(review: MonthlyReview): BlockState = when {
    review.pendingCount > 0 -> BlockState.Ready(
        listOf("${review.pendingCount} dépense${if (review.pendingCount > 1) "s" else ""} à revoir"),
        hasData = true,
    )
    review.ratedCount > 0 -> BlockState.Ready(listOf("Revue du mois terminée"), hasData = true)
    else -> BlockState.Ready(listOf("Rien à revoir ce mois-ci"))
}

internal fun countBlock(count: Int, one: String, many: String, none: String, extra: String? = null): BlockState =
    BlockState.Ready(
        listOfNotNull(
            if (count == 0) none else "$count ${if (count > 1) many else one}",
            extra,
        ),
        hasData = count > 0,
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
        hasData = true,
    )
}

internal fun lastSyncBlock(connections: List<BankConnection>): BlockState {
    if (connections.isEmpty()) return BlockState.Ready(listOf("Aucune banque connectée"))

    val latest = connections.mapNotNull { it.lastSyncedAt }
        .maxByOrNull { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: Instant.EPOCH }
    return BlockState.Ready(listOf(lastSyncLabel(latest)))
}
