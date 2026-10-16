package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.maggie.app.ui.components.DateField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.CategoryTree
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TransactionNature
import com.maggie.app.data.model.TransactionPrefill
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.qualificationProvenance
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.idOfIri
import com.maggie.app.data.model.transactionPills
import com.maggie.app.data.model.incidentsTabTitle
import com.maggie.app.data.model.signedAmountCents
import com.maggie.app.data.model.statusForNature
import com.maggie.app.data.model.toFormValues
import com.maggie.app.data.model.transactionStatusCodes
import com.maggie.app.data.model.transactionStatusLabel
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot
import com.maggie.app.ui.components.ErrorSnackbar
import java.time.LocalDate
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    viewModel: TransactionViewModel,
    accountName: String,
    onBack: (() -> Unit)?,
    onMakeRecurring: ((Transaction) -> Unit)? = null,
    prefill: TransactionPrefill? = null,
    onPrefillConsumed: () -> Unit = {},
    // A line to open the sheet of as soon as the list holds it (« Voir la fiche » of « À qualifier »).
    openTransactionId: String? = null,
    onTransactionOpened: () -> Unit = {},
    // « Voir tout ce qu'on te doit » of a shared spend (MAG-451): the contacts, until MAG-455's screen.
    onOpenPeerDebts: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }
    var dialogPrefill by remember { mutableStateOf<TransactionPrefill?>(null) }

    // The form keeps its own copy: the caller's one is consumed at once, nothing is saved.
    LaunchedEffect(prefill) {
        if (prefill != null) {
            dialogPrefill = prefill
            showCreateDialog = true
            onPrefillConsumed()
        }
    }
    LaunchedEffect(openTransactionId, uiState.transactions) {
        if (openTransactionId != null && uiState.transactions.any { it.id == openTransactionId }) {
            viewModel.openDetail(openTransactionId)
            onTransactionOpened()
        }
    }
    var searchingCounterpart by remember { mutableStateOf(false) }
    var choosingOneOffEnvelope by remember { mutableStateOf(false) }
    val tree = remember(uiState.categories) { CategoryTree(uiState.categories) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val detail = uiState.detail
    if (detail != null) {
        // « Ajouter une personne »: the full-screen picker, in a window of its own over the sheet or its pane.
        uiState.shareContacts?.let { choice ->
            val chosen = detail.share?.people.orEmpty().map { it.contactId }.toSet()
            ContactPickerDialog(
                contacts = choice.contacts.filter { it.id !in chosen },
                isLoading = choice.isLoading,
                title = "Qui te doit ?",
                subtitle = detail.transaction.label,
                onPick = viewModel::pickShareContact,
                onCreate = viewModel::createShareContact,
                onDismiss = viewModel::closeShareContactPicker,
            )
        }
        val ruleDraft = uiState.ruleDraft
        if (ruleDraft != null) {
            TransactionRuleDraftScreen(
                draft = ruleDraft,
                categories = uiState.categories,
                onPatternChange = viewModel::changeRulePattern,
                onAmountChange = viewModel::changeRuleAmount,
                onCategoryChange = viewModel::changeRuleCategory,
                onQualificationChange = viewModel::changeRuleQualification,
                onSave = viewModel::saveRule,
                onBack = viewModel::closeRuleDraft,
            )
        } else if (searchingCounterpart) {
            CounterpartSearchScreen(
                state = detail,
                onLoad = viewModel::loadCandidates,
                onPick = { leg ->
                    viewModel.markAsTransfer(leg?.id)
                    searchingCounterpart = false
                },
                onBack = { searchingCounterpart = false },
            )
        } else if (choosingOneOffEnvelope) {
            OneOffEnvelopeSearchScreen(
                envelopes = uiState.oneOffEnvelopes,
                selectedId = detail.transaction.oneOffEnvelopeId,
                isLoading = uiState.isLoadingOneOffEnvelopes,
                error = detail.error,
                onLoad = viewModel::loadOneOffEnvelopes,
                onPick = { envelope ->
                    viewModel.setOneOffEnvelope(envelope?.id)
                    choosingOneOffEnvelope = false
                },
                onBack = { choosingOneOffEnvelope = false },
            )
        } else {
            TransactionDetailScreen(
                state = detail,
                actions = TransactionSheetActions(
                    onBack = viewModel::closeDetail,
                    onSearchCounterpart = { searchingCounterpart = true },
                    onRelease = viewModel::releaseTransfer,
                    onQualify = viewModel::setQualification,
                    onCreateRule = viewModel::openRuleDraft,
                    onMakeRecurring = onMakeRecurring?.let { open -> { open(detail.transaction) } },
                    onDetach = viewModel::detachFromSeries,
                    onSaveNote = viewModel::setNote,
                    onPickOneOffEnvelope = { choosingOneOffEnvelope = true },
                    share = ShareSheetActions(
                        onEnable = viewModel::setShareEnabled,
                        onMyShareChange = viewModel::changeMyShare,
                        onAddPerson = viewModel::openShareContactPicker,
                        onRemovePerson = viewModel::removeSharePerson,
                        onAmountChange = viewModel::editShareAmount,
                        onSubjectChange = viewModel::changeShareSubject,
                        onNotesChange = viewModel::changeShareNotes,
                        onSave = viewModel::saveShare,
                        onSeeAllOwed = onOpenPeerDebts,
                    ),
                ),
                categories = uiState.categories,
                provenance = qualificationProvenance(
                    detail.transaction.qualifiedBy,
                    seriesName = detail.transaction.qualifiedBySeries?.let { uiState.seriesNames[idOfIri(it)] },
                    ruleName = detail.transaction.qualifiedByRule?.let { uiState.ruleNames[idOfIri(it)] },
                ),
                seriesName = seriesNameOf(detail.transaction, uiState.seriesNames),
                oneOffEnvelopeName = uiState.oneOffEnvelopeNameOf(detail.transaction),
            )
        }
        return
    }

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )

    // The ViewModel owns the undo window and clears `undoableSettlement` when it closes, which
    // ends this effect and takes the bar away with it (MAG-424).
    LaunchedEffect(uiState.undoableSettlement?.key) {
        val settled = uiState.undoableSettlement ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Rejet ${settled.counterpartyName} régularisé",
            actionLabel = "Annuler",
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoSettlement()
    }

    ErrorSnackbar(
        error = uiState.settlementError,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearSettlementError,
        message = uiState.settlementError.orEmpty(),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(accountName) },
                navigationIcon = {
                    // No arrow in the detail pane beside the accounts (MAG-263): the list is still there.
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Nouvelle transaction")
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Transactions") },
                    modifier = Modifier.testTag(UiTags.TRANSACTIONS_TAB),
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(incidentsTabTitle(uiState.incidents.size.takeIf { uiState.incidentsError == null && !uiState.isLoadingIncidents }))
                    },
                    modifier = Modifier.testTag(UiTags.INCIDENTS_TAB),
                )
            }
            if (selectedTab == 0) {
                when {
                    uiState.isLoading && uiState.transactions.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    uiState.transactions.isEmpty() -> {
                        EmptyState(
                            title = "Aucune transaction sur ce compte",
                            description = "Ajoutez vos dépenses et vos recettes pour suivre ce compte au fil du mois.",
                            actionLabel = "Ajouter une transaction",
                            onAction = { showCreateDialog = true },
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(uiState.transactions, key = { it.id }) { transaction ->
                                Card(modifier = Modifier.fillMaxWidth().clickable { viewModel.openDetail(transaction.id) }) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = transaction.label,
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                            val subtitle = buildString {
                                                transaction.bookedAt?.let { append(it).append(" · ") }
                                                append(transactionStatusLabel(transaction.status, transaction.amountCents))
                                            }
                                            Text(
                                                text = subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            tree.pathOf(transaction.categoryId)?.let { path ->
                                                Text(
                                                    text = path,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            TransactionPills(
                                                pills = transactionPills(transaction, seriesNameOf(transaction, uiState.seriesNames)),
                                                onOpen = { viewModel.openDetail(transaction.id) },
                                                modifier = Modifier.padding(top = 4.dp),
                                            )
                                        }
                                        Text(
                                            text = formatCents(transaction.amountCents, transaction.currency),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (transaction.amountCents < 0) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                        )
                                        IconButton(onClick = { viewModel.deleteTransaction(transaction.id) }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Supprimer",
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                IncidentsTab(
                    incidents = uiState.incidents,
                    isLoading = uiState.isLoadingIncidents,
                    error = uiState.incidentsError,
                    onOpen = viewModel::openIncident,
                    onRetry = viewModel::refreshIncidents,
                    settled = uiState.settledIncidents,
                    settling = uiState.settlingIncident,
                    onSettle = viewModel::settleIncident,
                    onUnsettle = viewModel::unsettleIncident,
                )
            }
        }
    }

    if (showCreateDialog) {
        TransactionCreateDialog(
            categories = uiState.categories,
            prefill = dialogPrefill,
            onConfirm = { amountCents, label, status, categoryId, bookedAt, qualification ->
                viewModel.createTransaction(amountCents, label, status, categoryId, bookedAt, qualification)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

/** The name of the series a line is attached to, once the series are loaded. */
private fun seriesNameOf(transaction: Transaction, names: Map<String, String>): String? =
    transaction.recurringOperation?.let { names[idOfIri(it)] }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionCreateDialog(
    categories: List<Category>,
    prefill: TransactionPrefill?,
    onConfirm: (amountCents: Int, label: String, status: String, categoryId: String?, bookedAt: String, qualification: Qualification) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember(prefill) { prefill?.toFormValues(LocalDate.now()) }
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var amountText by remember { mutableStateOf(initial?.amountText.orEmpty()) }
    var nature by remember {
        mutableStateOf(if (initial?.isIncome == true) TransactionNature.Income else TransactionNature.Expense)
    }
    var status by remember { mutableStateOf(initial?.status ?: "spent") }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var qualification by remember { mutableStateOf(Qualification.NONE) }
    var date by remember { mutableStateOf(initial?.date ?: LocalDate.now()) }

    val canSubmit = label.isNotBlank() &&
        (amountText.toDoubleOrNull() ?: 0.0) != 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle transaction") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        TransactionNature.Expense to "Dépense",
                        TransactionNature.Income to "Recette",
                    ).forEach { (option, name) ->
                        FilterChip(
                            selected = nature == option,
                            onClick = {
                                nature = option
                                status = statusForNature(status, option)
                            },
                            label = { Text(name) },
                        )
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Libellé") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Montant (€)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                DateField(
                    label = "Date",
                    value = date,
                    onValueChange = { date = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (nature == TransactionNature.Income) "État de la recette" else "État de la dépense",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    transactionStatusCodes(nature).forEach { code ->
                        FilterChip(
                            selected = status == code,
                            onClick = { status = code },
                            label = { Text(transactionStatusLabel(code, nature)) },
                        )
                    }
                }
                QualificationSection(
                    categories = categories,
                    categoryId = categoryId,
                    qualification = qualification,
                    onCategoryChange = { categoryId = it },
                    onQualificationChange = { qualification = it },
                    categoryTag = UiTags.TRANSACTION_CATEGORY,
                    searchTag = UiTags.TRANSACTION_CATEGORY_SEARCH,
                    canClearCategory = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val euros = amountText.toDoubleOrNull() ?: return@TextButton
                    val cents = signedAmountCents((euros * 100).roundToInt(), nature)
                    onConfirm(cents, label.trim(), status, categoryId, date.toString(), qualification)
                },
                enabled = canSubmit,
            ) {
                Text("Créer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        },
        modifier = Modifier.uiTagRoot(),
    )
}
