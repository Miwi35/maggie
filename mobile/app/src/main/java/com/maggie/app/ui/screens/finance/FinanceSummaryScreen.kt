package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.navigation.FinanceMenu
import com.maggie.app.ui.navigation.Screen

/** One block of a summary page: what it is called, the figures it carries, and the screen it opens. */
data class SummaryEntry(val block: SummaryBlock, val title: String, val route: String)

/**
 * The blocks of a menu's summary page, in reading order.
 *
 * One block per aspect that has a screen: a menu never lists what does not exist yet, and an
 * aspect that arrives with its ticket (the merged operations list with MAG-478) is added here
 * and nowhere else. Menus whose home is a screen themselves (Dashboard, Comptes, Budget) have no page.
 */
fun summaryEntries(menu: FinanceMenu): List<SummaryEntry> = when (menu) {
    FinanceMenu.REVIEW -> listOf(
        SummaryEntry(SummaryBlock.TO_SORT, "À qualifier", Screen.TransactionsToSort.route),
        SummaryEntry(SummaryBlock.MONTHLY_REVIEW, "Revue mensuelle", Screen.MonthlyReview.route),
        SummaryEntry(SummaryBlock.RULES, "Règles", Screen.CategorizationRuleList.route),
        SummaryEntry(SummaryBlock.RECURRING_OPERATIONS, "Opérations récurrentes", Screen.RecurringOperations.route),
    )
    FinanceMenu.TREASURY -> listOf(
        SummaryEntry(SummaryBlock.OUTLOOK, "Prévision", Screen.Treasury.route),
        SummaryEntry(SummaryBlock.CUSHION, "Matelas", Screen.Cushion.route),
        SummaryEntry(SummaryBlock.SAVINGS_ADVANCES, "Avances d'épargne", Screen.SavingsAdvances.route),
    )
    FinanceMenu.PLANNING -> listOf(
        SummaryEntry(SummaryBlock.LOANS, "Prêts", Screen.LoanList.route),
        // The counter has no screen of its own: it lives on the dashboard, which is where the block leads.
        SummaryEntry(SummaryBlock.INDEPENDENCE, "Indépendance financière", Screen.FinanceDashboard.route),
    )
    FinanceMenu.SETTINGS -> listOf(
        SummaryEntry(SummaryBlock.BANKS, "Banques", Screen.BankConnectionList.route),
        SummaryEntry(SummaryBlock.CATEGORIES, "Catégories", Screen.CategoryList.route),
    )
    FinanceMenu.DASHBOARD, FinanceMenu.ACCOUNTS, FinanceMenu.BUDGET -> emptyList()
}

/**
 * The home of Revue, Trésorerie, Planification and Configuration (MAG-426): a block per aspect,
 * each a few figures and a way into the detailed screen — never a list of links.
 *
 * A block that cannot load says so in its own place and still opens its screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceSummaryScreen(
    menu: FinanceMenu,
    viewModel: FinanceSummaryViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val entries = summaryEntries(menu)
    val blocks by viewModel.blocks.collectAsState()

    LaunchedEffect(menu) { viewModel.load(entries.map { it.block }) }

    Scaffold(
        topBar = {
            FinanceTopBar(menu.home) {
                TopAppBar(
                    title = { Text(menu.label) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries, key = { it.route }) { entry ->
                SummaryCard(entry, blocks[entry.block] ?: BlockState.Loading, onOpen = { onOpen(entry.route) })
            }
        }
    }
}

@Composable
private fun SummaryCard(entry: SummaryEntry, state: BlockState, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag(UiTags.financeBlock(entry.route)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                when (state) {
                    BlockState.Loading -> Box(modifier = Modifier.padding(top = 8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    is BlockState.Ready -> state.lines.forEachIndexed { index, line ->
                        Text(
                            text = line,
                            style = if (index == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                            color = if (state.needsAttention) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    is BlockState.Unavailable -> Text(
                        text = "Chiffres indisponibles",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}
