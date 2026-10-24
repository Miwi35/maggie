package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.TimelineMissedOccurrence
import com.maggie.app.data.model.TimelineLine
import com.maggie.app.data.model.accountTypeLabel
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.label
import com.maggie.app.data.model.lateLabel
import com.maggie.app.data.model.timelineDayTitle
import com.maggie.app.data.model.timelineMonthTitle
import com.maggie.app.data.model.transactionStatusLabel
import com.maggie.app.data.model.transferBadge
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.uiTagRoot
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What a timeline can ask of its screen: the rest it does through the [AccountViewModel]. */
data class AccountTimelineActions(
    /** A line was touched: its sheet (and the account's full list, with incidents) opens. */
    val onOpenTransaction: (accountId: String, accountName: String, transactionId: String?) -> Unit,
)

/**
 * The phone's accounts (MAG-422): one account per page, the position in a row of dots, and under
 * the header of the page the account's transactions by day. A touch on a dot goes to its account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPagerScreen(
    viewModel: AccountViewModel,
    onBack: () -> Unit,
    actions: AccountTimelineActions,
    onOpenContacts: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Comptes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    // The people behind the transfers live with the accounts (MAG-450).
                    IconButton(onClick = onOpenContacts, modifier = Modifier.testTag(UiTags.ACCOUNTS_CONTACTS)) {
                        Icon(Icons.Default.Person, contentDescription = "Contacts")
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Nouveau compte")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        val accounts = uiState.accounts
        when {
            uiState.isLoading && accounts.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            accounts.isEmpty() -> EmptyState(
                modifier = Modifier.padding(paddingValues),
                title = "Aucun compte pour l'instant",
                description = "Ajoutez vos comptes courants et vos livrets : c'est d'eux que partent les transactions et le matelas.",
                actionLabel = "Ajouter un compte",
                onAction = { showCreateDialog = true },
            )

            else -> AccountPager(
                viewModel = viewModel,
                uiState = uiState,
                actions = actions,
                modifier = Modifier.fillMaxSize().padding(paddingValues),
            )
        }
    }

    if (showCreateDialog) {
        AccountCreateDialog(
            onConfirm = { request ->
                viewModel.createAccount(request)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

@Composable
private fun AccountPager(
    viewModel: AccountViewModel,
    uiState: AccountUiState,
    actions: AccountTimelineActions,
    modifier: Modifier = Modifier,
) {
    val accounts = uiState.accounts
    val pagerState = rememberPagerState(initialPage = uiState.selectedIndex) { uiState.accounts.size }
    val scope = rememberCoroutineScope()

    // The account on screen follows the page, as soon as it is the nearest one: its month is read while it settles.
    val latestAccounts by rememberUpdatedState(accounts)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { index ->
            latestAccounts.getOrNull(index)?.let { viewModel.selectAccount(it.id) }
        }
    }

    Column(modifier = modifier) {
        AccountDots(
            accounts = accounts,
            current = pagerState.currentPage,
            onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
        )
        HorizontalPager(
            state = pagerState,
            key = { accounts.getOrNull(it)?.id ?: it },
            modifier = Modifier.fillMaxSize().testTag(UiTags.ACCOUNT_PAGER),
        ) { page ->
            val account = accounts.getOrNull(page)
            if (account != null) {
                AccountTimelineContent(
                    account = account,
                    timeline = uiState.timelines[account.id],
                    ui = uiState,
                    viewModel = viewModel,
                    actions = actions,
                )
            }
        }
    }
}

/**
 * One dot per account, the current one filled. Each dot is described by the account's full label
 * (« Boursorama · EUR · Compte courant »): that is what a screen reader says, and what a journey taps to go there.
 */
@Composable
fun AccountDots(
    accounts: List<Account>,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp).testTag(UiTags.ACCOUNT_DOTS),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        accounts.forEachIndexed { index, account ->
            val isCurrent = index == current
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .testTag(UiTags.accountDot(index))
                    .semantics {
                        contentDescription = account.label()
                        selected = isCurrent
                    }
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (isCurrent) 10.dp else 8.dp)
                        .background(
                            if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        ),
                )
            }
        }
    }
}

/** The tablet's detail pane: the timeline of the account chosen in the list, with no pager. */
@Composable
fun AccountTimelineDetail(
    viewModel: AccountViewModel,
    accountId: String,
    actions: AccountTimelineActions,
) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(accountId) { viewModel.selectAccount(accountId) }
    val account = uiState.accounts.firstOrNull { it.id == accountId } ?: return
    AccountTimelineContent(
        account = account,
        timeline = uiState.timelines[accountId],
        ui = uiState,
        viewModel = viewModel,
        actions = actions,
    )
}

/**
 * An account's page: its header (name, balance, the two actions), then the days. The balance is the
 * one of the newest read when there is one, the list's otherwise.
 */
@Composable
private fun AccountTimelineContent(
    account: Account,
    timeline: AccountTimelineState?,
    ui: AccountUiState,
    viewModel: AccountViewModel,
    actions: AccountTimelineActions,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showBalanceDialog by remember { mutableStateOf(false) }
    val read = timeline?.account
    val balanceCents = read?.balanceCents ?: account.balanceCents
    val currency = read?.currency ?: account.currency
    val isManual = read?.isManual == true

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(UiTags.ACCOUNT_PAGE),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(account.name, style = MaterialTheme.typography.headlineSmall)
                val subtitle = buildString {
                    account.bank?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
                    append(accountTypeLabel(account.type))
                    if (account.isCushion) append(" · Matelas")
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatCents(balanceCents, currency),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.testTag(UiTags.ACCOUNT_BALANCE),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.testTag(UiTags.ACCOUNT_ADD_TRANSACTION),
                    ) { Text("Ajouter une transaction") }
                    if (isManual) {
                        OutlinedButton(
                            onClick = { showBalanceDialog = true },
                            modifier = Modifier.testTag(UiTags.ACCOUNT_CORRECT_BALANCE),
                        ) { Text("Corriger le solde") }
                    }
                }
                TextButton(
                    onClick = { actions.onOpenTransaction(account.id, account.label(), null) },
                    modifier = Modifier.testTag(UiTags.ACCOUNT_ALL_TRANSACTIONS),
                ) { Text("Toutes les transactions et incidents") }
            }
        }

        when {
            timeline == null || (timeline.isLoading && !timeline.isLoaded) -> item(key = "loading") {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            timeline.error != null && !timeline.isLoaded -> item(key = "error") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Impossible de lire les transactions de ce compte.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { viewModel.loadTimeline(account.id) },
                        modifier = Modifier.testTag(UiTags.TIMELINE_RETRY),
                    ) { Text("Réessayer") }
                }
            }

            timeline.days.isEmpty() -> item(key = "empty") {
                Text(
                    "Aucune transaction sur ce compte ce mois-ci.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }

            else -> timeline.days.forEach { day ->
                item(key = "day-${day.date}") {
                    Text(
                        timelineDayTitle(day.date),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .testTag(UiTags.timelineDay(day.date))
                            .semantics { heading() },
                    )
                }
                items(day.transactions, key = { "line-${it.id}" }) { line ->
                    TimelineLineRow(line) { actions.onOpenTransaction(account.id, account.label(), line.id) }
                }
                items(day.missedOccurrences, key = { "missed-${it.recurringOperationId}-${it.expectedOn}" }) { missed ->
                    MissedOccurrenceRow(missed)
                }
            }
        }

        if (timeline != null && timeline.isLoaded) {
            val previous = timeline.previousMonth
            if (previous != null) {
                item(key = "previous") {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (timeline.isLoadingPrevious) {
                            CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.loadPreviousMonth(account.id) },
                                modifier = Modifier.testTag(UiTags.TIMELINE_PREVIOUS_MONTH),
                            ) { Text("Mois précédent (${timelineMonthTitle(previous)})") }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        TransactionCreateDialog(
            categories = ui.categories,
            prefill = null,
            onConfirm = { amountCents, label, status, categoryId, bookedAt, qualification ->
                viewModel.addTransaction(account.id, amountCents, label, status, categoryId, bookedAt, qualification)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
    if (showBalanceDialog) {
        BalanceCorrectionDialog(
            currentCents = balanceCents,
            onConfirm = { cents ->
                viewModel.correctBalance(account.id, cents)
                showBalanceDialog = false
            },
            onDismiss = { showBalanceDialog = false },
        )
    }
}

@Composable
private fun TimelineLineRow(line: TimelineLine, onOpen: () -> Unit) {
    MaggieCard(modifier = Modifier.fillMaxWidth().testTag(UiTags.TIMELINE_LINE).clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(line.label, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        statusIcon(line.status),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        transactionStatusLabel(line.status, line.amountCents),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(UiTags.TIMELINE_STATUS),
                    )
                }
                val badge = line.transferBadge()
                val increase = line.recurringIncrease
                if (badge != null || increase != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Chips, not texts: each is a node of its own, which a journey can address.
                        if (badge != null) {
                            AssistChip(
                                onClick = onOpen,
                                label = { Text(badge) },
                                leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier.testTag(if (line.transferKind == "rejected") UiTags.REJECTION_BADGE else UiTags.TRANSFER_BADGE),
                            )
                        }
                        if (increase != null) {
                            AssistChip(
                                onClick = onOpen,
                                label = { Text(increase.label()) },
                                modifier = Modifier.testTag(UiTags.TIMELINE_INCREASE),
                            )
                        }
                    }
                }
            }
            Text(
                formatCents(line.amountCents, line.currency),
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    line.transferKind != "none" -> MaterialTheme.colorScheme.onSurfaceVariant
                    line.amountCents < 0 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun MissedOccurrenceRow(missed: TimelineMissedOccurrence) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).testTag(UiTags.TIMELINE_MISSED),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Text(
            missed.lateLabel(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        Text(formatCents(missed.amountCents), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The icon that comes with the status word: the word says it, the icon lets the eye find it. */
internal fun statusIcon(status: String): ImageVector = when (status) {
    "spent" -> Icons.Default.CheckCircle
    "committed" -> Icons.Default.Lock
    "planned" -> Icons.Default.Schedule
    else -> Icons.Default.QuestionMark
}

/** « 1234,56 » for the balance form; the sign is kept. */
internal fun balanceInput(cents: Int): String {
    val magnitude = abs(cents.toLong())
    return (if (cents < 0) "-" else "") + "${magnitude / 100},${"%02d".format(magnitude % 100)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BalanceCorrectionDialog(
    currentCents: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(balanceInput(currentCents)) }
    val cents = parseCents(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Corriger le solde") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Nouveau solde (€)") },
                isError = cents == null,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.ACCOUNT_BALANCE_INPUT),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { cents?.let(onConfirm) },
                enabled = cents != null,
                modifier = Modifier.testTag(UiTags.ACCOUNT_BALANCE_SAVE),
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        modifier = Modifier.uiTagRoot(),
    )
}
