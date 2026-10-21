package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CategoryTree
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.QualificationKind
import com.maggie.app.data.model.PeerKind
import com.maggie.app.data.model.TO_SORT_REASON_PEER_TRANSFER
import com.maggie.app.data.model.TransactionToSort
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.toSortCounter
import com.maggie.app.data.model.transferLegSummary
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.CategorySearchDialog
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.layout.ListDetailPane
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.uiTagRoot

/**
 * « À qualifier »: the lines without a category, oldest first (MAG-423, MAG-458). It opens on the list;
 * a line opens its card, « Précédent » / « Suivant » (or a swipe) walk the list's order, and back returns
 * to the list where it was (MAG-507). With [showsDetailPane] (a tablet) the list and the card sit side by side.
 * [onOpenTransaction] opens the line's own sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToSortScreen(
    viewModel: ToSortViewModel,
    onBack: () -> Unit,
    showsDetailPane: Boolean = false,
    onOpenTransaction: (TransactionToSort) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val tree = remember(state.categories) { CategoryTree(state.categories) }
    val listState = rememberLazyListState()
    val showsCardOnly = state.ficheOpen && !showsDetailPane

    BackHandler(enabled = showsCardOnly, onBack = viewModel::backToList)

    // Back on the list, the line the owner walked to is in view (the counter is the list's first item).
    LaunchedEffect(showsCardOnly, state.selectedId) {
        if (showsCardOnly) return@LaunchedEffect
        val index = state.pile.indexOfFirst { it.id == state.selectedId }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index + 1 }) {
            listState.scrollToItem(index + 1)
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // The server's own sentence: « … is already linked to Anna » says what to do next.
    LaunchedEffect(state.contactError) {
        state.contactError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearContactError()
        }
    }

    ErrorSnackbar(
        error = state.sortError,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearSortError,
        message = "Classement impossible. La ligne reste à qualifier.",
    )

    ErrorSnackbar(
        error = state.judgeError,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearJudgeError,
        message = "Jugement impossible. La ligne garde son état.",
    )

    if (state.choosingCategory) {
        CategorySearchDialog(
            title = "Choisir la catégorie",
            candidates = state.categories,
            pathOf = tree::path,
            selectedId = state.current?.suggestion?.categoryId,
            onPick = { viewModel.chooseCategory(it.id) },
            onDismiss = viewModel::closeCategorySearch,
            searchTag = UiTags.TO_SORT_SEARCH,
        )
    }

    state.contactChoice?.let { choice ->
        ContactPickerDialog(
            contacts = choice.contacts,
            isLoading = choice.isLoading,
            subtitle = state.current?.let { it.counterpartyLabel.ifBlank { it.label } },
            onPick = viewModel::pickContact,
            onCreate = viewModel::createContact,
            onDismiss = viewModel::closeContactPicker,
        )
    }

    // « Ce virement est »'s person (MAG-453): the same full-screen picker.
    state.peerContacts?.let { choice ->
        ContactPickerDialog(
            contacts = choice.contacts,
            isLoading = choice.isLoading,
            title = if (state.currentPeer?.kind == PeerKind.NEW_CLAIM) "Qui te doit ?" else "À qui le rendre ?",
            subtitle = state.current?.label,
            onPick = viewModel::pickPeerContact,
            onCreate = viewModel::createPeerContact,
            onDismiss = viewModel::closePeerContactPicker,
        )
    }

    state.transfer?.let { choice ->
        TransferChoiceDialog(
            choice = choice,
            onPick = viewModel::markAsInternalTransfer,
            onDismiss = viewModel::closeTransfer,
        )
    }

    state.ruleDraft?.let { draft ->
        TransactionRuleDraftScreen(
            draft = draft,
            categories = state.categories,
            onPatternChange = viewModel::changeRulePattern,
            onAmountChange = viewModel::changeRuleAmount,
            onCategoryChange = viewModel::changeRuleCategory,
            onQualificationChange = viewModel::changeRuleQualification,
            onSave = viewModel::saveRule,
            onBack = viewModel::closeRuleDraft,
            title = "Créer un filtre",
            appliesToExisting = true,
        )
        return
    }

    Scaffold(
        topBar = {
            FinanceTopBar(Screen.TransactionsToSort.route) {
                TopAppBar(
                    title = { Text("À qualifier") },
                    navigationIcon = {
                        IconButton(
                            onClick = if (showsCardOnly) viewModel::backToList else onBack,
                            modifier = if (showsCardOnly) Modifier.testTag(UiTags.REVIEW_BACK_TO_LIST) else Modifier,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (showsCardOnly) "Retour à la liste" else "Retour",
                            )
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                !state.loaded && state.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                !state.loaded -> EmptyState(
                    title = "Chargement impossible",
                    description = "La pile à qualifier n'a pas pu être lue. Vérifiez votre connexion.",
                    actionLabel = "Réessayer",
                    onAction = viewModel::refresh,
                )

                state.isEmpty -> EmptyState(
                    title = toSortCounter(0),
                    description = "Toutes les transactions ont une catégorie.",
                    modifier = Modifier.testTag(UiTags.TO_SORT_EMPTY),
                )

                showsDetailPane -> ListDetailPane(
                    showsDetailPane = true,
                    list = { PileList(state, listState, onSelect = viewModel::select) },
                    detail = if (state.ficheOpen) ({ CardPane(state, viewModel, onOpenTransaction) }) else null,
                    placeholder = "Touchez une ligne pour la qualifier ici.",
                )

                state.ficheOpen -> CardPane(state, viewModel, onOpenTransaction)

                else -> PileList(state, listState, onSelect = viewModel::select)
            }
        }
    }
}

@Composable
private fun CardPane(state: ToSortUiState, viewModel: ToSortViewModel, onOpenTransaction: (TransactionToSort) -> Unit) {
    val line = state.current ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .swipeToNavigate(onPrevious = viewModel::previous, onNext = viewModel::next)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReviewSheetNav(
            position = state.position,
            total = state.pile.size,
            hasPrevious = state.hasPrevious,
            hasNext = state.hasNext,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
        )
        SortCard(
            line = line,
            state = state,
            onValidate = viewModel::validate,
            onOther = viewModel::openCategorySearch,
            onAlways = viewModel::setAlwaysForMerchant,
            onTransfer = viewModel::openTransfer,
            onQualify = viewModel::setQualification,
            onJudge = viewModel::judge,
            onCreateRule = viewModel::openRuleDraft,
            onOpenSheet = { onOpenTransaction(line) },
            onContact = viewModel::openContactPicker,
            peerActions = PeerTransferActions(
                onKindChange = viewModel::choosePeerKind,
                onToggleShare = viewModel::togglePeerShare,
                onShareAmountChange = viewModel::changePeerShareAmount,
                onPickPerson = viewModel::openPeerContactPicker,
                onDebtAmountChange = viewModel::changePeerDebtAmount,
                onSubjectChange = viewModel::changePeerSubject,
                onNotesChange = viewModel::changePeerNotes,
                onSave = viewModel::savePeer,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortCard(
    line: TransactionToSort,
    state: ToSortUiState,
    onValidate: () -> Unit,
    onOther: () -> Unit,
    onAlways: (Boolean) -> Unit,
    onTransfer: () -> Unit,
    onQualify: (Qualification) -> Unit,
    onJudge: (String) -> Unit,
    onCreateRule: () -> Unit,
    onOpenSheet: () -> Unit,
    onContact: () -> Unit,
    peerActions: PeerTransferActions,
) {
    val suggestion = line.suggestion
    // Filed already, only « Ce virement est » is missing (MAG-453): no category to ask for.
    val asksForCategory = line.reason != TO_SORT_REASON_PEER_TRANSFER
    var qualifying by rememberSaveable(line.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.TO_SORT_CARD),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(line.label, style = MaterialTheme.typography.titleMedium)
            Text(formatCents(line.amountCents, line.currency), style = MaterialTheme.typography.headlineSmall)
            Text(
                listOfNotNull(line.bookedAt, line.accountName).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val contact = line.contact
            if (contact != null) {
                Text(
                    "Contact : ${contact.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(UiTags.TO_SORT_CONTACT_NAME),
                )
            } else if (line.asksForContact) {
                TextButton(
                    onClick = onContact,
                    enabled = !state.isLinking && !state.isSorting,
                    modifier = Modifier.testTag(UiTags.TO_SORT_CONTACT),
                ) { Text("Associer à un contact ?") }
            }

            if (line.asksForPeerKind) {
                HorizontalDivider()
                val peer = state.currentPeer
                if (peer == null) {
                    CircularProgressIndicator()
                } else {
                    PeerTransferQualifier(peer, peerActions, line.currency)
                }
            }

            if (asksForCategory) {
                HorizontalDivider()

                if (suggestion != null) {
                    Text("→ ${suggestion.categoryPath}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        suggestion.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Aucune suggestion pour cette ligne.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (suggestion != null) {
                    Button(
                        onClick = onValidate,
                        enabled = state.canValidate,
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.TO_SORT_VALIDATE),
                    ) { Text("Valider") }
                }
                OutlinedButton(
                    onClick = onOther,
                    enabled = !state.isSorting,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.TO_SORT_OTHER),
                ) { Text("Autre catégorie") }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.alwaysForMerchant,
                            role = Role.Switch,
                            onValueChange = onAlways,
                        )
                        .testTag(UiTags.TO_SORT_ALWAYS),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Toujours pour ce marchand", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = state.alwaysForMerchant, onCheckedChange = null)
                }

                TextButton(
                    onClick = { qualifying = !qualifying },
                    modifier = Modifier.testTag(UiTags.TO_SORT_QUALIFICATION),
                ) { Text(qualificationToggleLabel(state.qualification, qualifying)) }
                if (qualifying) {
                    QualificationFlags(
                        qualification = state.qualification,
                        onQualificationChange = onQualify,
                        enabled = !state.isSorting,
                    )
                }
            }

            if (line.judgeable) {
                Text("Cette dépense", style = MaterialTheme.typography.bodyLarge)
                SatisfactionChips(
                    retrospect = line.retrospect,
                    enabled = !state.isJudging,
                    onJudge = onJudge,
                    keepTag = UiTags.TO_SORT_KEEP,
                    avoidableTag = UiTags.TO_SORT_AVOIDABLE,
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A filed transfer has a category: the API refuses an internal transfer, and a filter has nothing to file.
                if (asksForCategory) {
                    TextButton(
                        onClick = onTransfer,
                        enabled = !state.isSorting,
                        modifier = Modifier.testTag(UiTags.TO_SORT_TRANSFER),
                    ) { Text("Virement interne") }
                    TextButton(
                        onClick = onCreateRule,
                        enabled = !state.isSorting,
                        modifier = Modifier.testTag(UiTags.TO_SORT_RULE),
                    ) { Text("Créer un filtre") }
                }
                TextButton(
                    onClick = onOpenSheet,
                    modifier = Modifier.testTag(UiTags.TO_SORT_OPEN_SHEET),
                ) { Text("Voir la fiche") }
            }
        }
    }
}

// « Qualification », with what is already chosen once the block is folded: « Obligatoire · Épargne ».
private fun qualificationToggleLabel(qualification: Qualification, open: Boolean): String {
    val chosen = listOfNotNull(
        "Obligatoire".takeIf { qualification.mandatory },
        qualification.kind.label.takeIf { qualification.kind != QualificationKind.None },
    )
    return if (open || chosen.isEmpty()) "Qualification" else "Qualification · ${chosen.joinToString(" · ")}"
}

// The pill of a row: the heading the app suggests, or what the line still needs.
private fun pillOf(line: TransactionToSort): String {
    val suggestion = line.suggestion
    return when {
        line.reason == TO_SORT_REASON_PEER_TRANSFER -> "Virement à préciser"
        suggestion != null -> "→ ${suggestion.categoryPath.substringAfterLast(" › ")}"
        else -> "À qualifier"
    }
}

@Composable
private fun PileList(state: ToSortUiState, listState: LazyListState, onSelect: (String) -> Unit) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                toSortCounter(state.total),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp).testTag(UiTags.TO_SORT_REMAINING),
            )
        }
        items(state.pile, key = { it.id }) { line ->
            val isCursor = line.id == state.selectedId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(line.id) }
                    .testTag(UiTags.TO_SORT_ROW)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        line.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isCursor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        listOfNotNull(line.bookedAt, formatCents(line.amountCents, line.currency), line.contact?.name).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (line.suggestion != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        pillOf(line),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun TransferChoiceDialog(
    choice: TransferChoice,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.uiTagRoot(),
        onDismissRequest = onDismiss,
        title = { Text("Virement interne") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (choice.isLoading) {
                    CircularProgressIndicator()
                } else if (choice.candidates.isEmpty()) {
                    Text("Aucune autre ligne ne ressemble à l'autre côté de ce virement.")
                } else {
                    Text("L'autre côté du virement :")
                    choice.candidates.forEach { leg ->
                        Text(
                            transferLegSummary(leg),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(leg.id) }.padding(vertical = 10.dp),
                        )
                    }
                }
                TextButton(
                    onClick = { onPick(null) },
                    modifier = Modifier.testTag(UiTags.TO_SORT_TRANSFER_NONE),
                ) { Text("Sans contrepartie importée") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
