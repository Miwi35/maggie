package com.maggie.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.maggie.app.data.model.OPERATION_SERIES
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TransactionPrefill
import com.maggie.app.ui.screens.finance.BankConnectionListContent
import com.maggie.app.ui.screens.finance.BankConnectionViewModel
import com.maggie.app.ui.screens.finance.CategorizationRuleListContent
import com.maggie.app.ui.screens.finance.CategorizationRuleViewModel
import com.maggie.app.ui.screens.finance.CategoryListContent
import com.maggie.app.ui.screens.finance.CategoryViewModel
import com.maggie.app.ui.screens.finance.CushionContent
import com.maggie.app.ui.screens.finance.CushionViewModel
import com.maggie.app.ui.screens.finance.FinanceAspect
import com.maggie.app.ui.screens.finance.FinanceMenuPage
import com.maggie.app.ui.screens.finance.FinanceSummaryViewModel
import com.maggie.app.ui.screens.finance.IndependenceContent
import com.maggie.app.ui.screens.finance.IndependenceViewModel
import com.maggie.app.ui.screens.finance.LoanListContent
import com.maggie.app.ui.screens.finance.LoanViewModel
import com.maggie.app.ui.screens.finance.MonthlyReviewContent
import com.maggie.app.ui.screens.finance.MonthlyReviewViewModel
import com.maggie.app.ui.screens.finance.ProjectListContent
import com.maggie.app.ui.screens.finance.ProjectViewModel
import com.maggie.app.ui.screens.finance.RecognisedToControlContent
import com.maggie.app.ui.screens.finance.RecognisedToControlViewModel
import com.maggie.app.ui.screens.finance.RecurringOperationListContent
import com.maggie.app.ui.screens.finance.RecurringOperationViewModel
import com.maggie.app.ui.screens.finance.SavingsAdvancesContent
import com.maggie.app.ui.screens.finance.SavingsAdvancesViewModel
import com.maggie.app.ui.screens.finance.ToSortContent
import com.maggie.app.ui.screens.finance.ToSortViewModel
import com.maggie.app.ui.screens.finance.TreasuryContent
import com.maggie.app.ui.screens.finance.TreasuryViewModel
import org.koin.androidx.compose.koinViewModel

/** The optional `tab` of a menu page: the id of the aspect it opens on. */
internal val FinanceMenuArguments: List<NamedNavArgument> = listOf(
    navArgument(FINANCE_TAB_ARG) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    },
)

/**
 * What the aspects of a menu page do with the rest of the app: the state `NavGraph` keeps (the
 * account being opened, the series to edit…) and the screens they lead to. Kept apart so the
 * graph's body only has to build it.
 */
internal class FinanceAspectHooks(
    val showsDetailPane: Boolean,
    val openTransaction: (accountId: String, accountName: String?, transactionId: String, drillDown: Boolean) -> Unit,
    val prepareAdvance: (accountId: String, accountName: String, prefill: TransactionPrefill) -> Unit,
    val openSuggestions: () -> Unit,
    val openRules: () -> Unit,
    val declareRente: () -> Unit,
    val openProjectForm: (projectId: String?) -> Unit,
    val recurringSeed: Transaction?,
    val onSeedConsumed: () -> Unit,
    val onBackToTransaction: () -> Unit,
    val seriesToOpen: String?,
    val setSeriesToOpen: (String?) -> Unit,
)

@Composable
internal fun FinanceMenuRoute(
    menu: FinanceMenu,
    requestedTab: String?,
    hooks: FinanceAspectHooks,
    onBack: () -> Unit,
) {
    FinanceMenuPage(
        menu = menu,
        viewModel = koinViewModel<FinanceSummaryViewModel>(),
        requestedTab = requestedTab,
        onBack = onBack,
    ) { aspect, selectTab, modifier ->
        FinanceAspectBody(aspect, selectTab, hooks, modifier)
    }
}

@Composable
private fun FinanceAspectBody(
    aspect: FinanceAspect,
    selectTab: (String) -> Unit,
    hooks: FinanceAspectHooks,
    modifier: Modifier,
) {
    when (aspect.id) {
        "to-sort" -> ToSortContent(
            viewModel = koinViewModel<ToSortViewModel>(),
            modifier = modifier,
            showsDetailPane = hooks.showsDetailPane,
            onOpenTransaction = { line ->
                line.accountId?.let { hooks.openTransaction(it, line.accountName, line.id, true) }
            },
            showsBackToList = true,
        )
        "to-control" -> RecognisedToControlContent(
            viewModel = koinViewModel<RecognisedToControlViewModel>(),
            modifier = modifier,
            showsDetailPane = hooks.showsDetailPane,
            onOpenOperation = { group ->
                // A series has its editor; a rule has no screen of its own, only the list of the rules.
                if (group.operationType == OPERATION_SERIES) {
                    hooks.setSeriesToOpen(group.operationId)
                    selectTab("recurring")
                } else {
                    selectTab("rules")
                }
            },
            onOpenTransaction = { line ->
                line.accountId?.let { hooks.openTransaction(it, line.accountName, line.id, false) }
            },
            showsBackToList = true,
        )
        "monthly-review" -> MonthlyReviewContent(koinViewModel<MonthlyReviewViewModel>(), modifier)
        "rules" -> CategorizationRuleListContent(
            viewModel = koinViewModel<CategorizationRuleViewModel>(),
            onOpenSuggestions = hooks.openSuggestions,
            modifier = modifier,
        )
        "recurring" -> RecurringOperationListContent(
            viewModel = koinViewModel<RecurringOperationViewModel>(),
            modifier = modifier,
            seed = hooks.recurringSeed,
            onSeedConsumed = hooks.onSeedConsumed,
            onBackToTransaction = hooks.onBackToTransaction,
            openSeriesId = hooks.seriesToOpen,
            onSeriesOpened = { hooks.setSeriesToOpen(null) },
        )
        "outlook" -> TreasuryContent(koinViewModel<TreasuryViewModel>(), hooks.prepareAdvance, modifier)
        "cushion" -> CushionContent(koinViewModel<CushionViewModel>(), modifier)
        "advances" -> SavingsAdvancesContent(koinViewModel<SavingsAdvancesViewModel>(), modifier)
        "projects" -> ProjectListContent(koinViewModel<ProjectViewModel>(), hooks.openProjectForm, modifier)
        "loans" -> LoanListContent(koinViewModel<LoanViewModel>(), modifier)
        "independence" -> IndependenceContent(koinViewModel<IndependenceViewModel>(), hooks.declareRente, modifier)
        "banks" -> BankConnectionListContent(koinViewModel<BankConnectionViewModel>(), modifier)
        "categories" -> CategoryListContent(
            viewModel = koinViewModel<CategoryViewModel>(),
            modifier = modifier,
            onOpenRules = hooks.openRules,
            showsDetailPane = hooks.showsDetailPane,
        )
    }
}
