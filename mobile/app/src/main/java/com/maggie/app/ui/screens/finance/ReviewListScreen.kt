package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.CategoryTree
import com.maggie.app.data.model.PeerKind
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.QualificationKind
import com.maggie.app.data.model.ReviewPeriod
import com.maggie.app.data.model.ReviewPreset
import com.maggie.app.data.model.ReviewQueueItem
import com.maggie.app.data.model.ReviewState
import com.maggie.app.data.model.TO_SORT_REASON_PEER_TRANSFER
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.qualificationProvenance
import com.maggie.app.data.model.reviewMonthLabel
import com.maggie.app.data.model.reviewPillLabel
import com.maggie.app.data.model.transferLegSummary
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.CategorySearchDialog
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.layout.ListDetailPane
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.uiTagRoot

/**
 * La Revue (MAG-539): one list of the lines to qualify and the recognised ones. The state pills and the period sit on
 * top; « Non reconnues » then « Reconnues » follow, each by ascending date. A line opens its fiche, « Précédent » /
 * « Suivant » (or a swipe) walk the filter, and back returns to the list where it was. With [showsDetailPane] (a tablet)
 * the list and the fiche sit side by side.
 *
 * [preset] is where the entry point wants the filters to start; [onPresetConsumed] tells the caller it was taken, so a
 * rotation or a return from a sheet never applies it again. [onOpenTransaction] opens the line's own sheet,
 * [onOpenOperation] the series or the rule that recognised it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewListScreen(
    viewModel: ReviewListViewModel,
    onBack: () -> Unit,
    preset: ReviewPreset? = null,
    onPresetConsumed: () -> Unit = {},
    showsDetailPane: Boolean = false,
    onOpenTransaction: (ReviewQueueItem) -> Unit = {},
    onOpenOperation: (ReviewQueueItem) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val tree = remember(state.categories) { CategoryTree(state.categories) }
    val ficheOnly = !showsDetailPane && state.ficheOpen && state.current != null

    LaunchedEffect(preset) {
        viewModel.open(preset)
        if (preset != null) onPresetConsumed()
    }

    BackHandler(enabled = ficheOnly, onBack = viewModel::backToList)

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

    ErrorSnackbar(
        error = state.controlError,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearControlError,
        message = "Geste impossible. La ligne reste à contrôler.",
    )

    if (state.choosingCategory) {
        CategorySearchDialog(
            title = "Choisir la catégorie",
            candidates = state.categories,
            pathOf = tree::path,
            selectedId = state.current?.suggestion?.categoryId,
            onPick = { viewModel.chooseCategory(it.id) },
            onDismiss = viewModel::closeCategorySearch,
            searchTag = UiTags.REVIEW_FICHE_SEARCH,
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

    // « Ce virement est »'s person: the same full-screen picker.
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
            title = "Créer une opération récurrente",
            appliesToExisting = true,
        )
        return
    }

    state.correction?.let { correction ->
        BackHandler(onBack = viewModel::cancelCorrection)
        CorrectionScreen(state.categories, correction, viewModel, snackbarHostState)
        return
    }

    Scaffold(
        topBar = {
            FinanceTopBar(Screen.ReviewList.route) {
                TopAppBar(
                    title = { Text("Revue") },
                    navigationIcon = {
                        IconButton(
                            onClick = if (ficheOnly) viewModel::backToList else onBack,
                            modifier = if (ficheOnly) Modifier.testTag(UiTags.REVIEW_BACK_TO_LIST) else Modifier,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (ficheOnly) "Retour à la liste" else "Retour",
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
                showsDetailPane -> ListDetailPane(
                    showsDetailPane = true,
                    list = { ListPane(state, viewModel) },
                    detail = if (state.ficheOpen && state.current != null) ({ FichePane(state, viewModel, onOpenTransaction, onOpenOperation) }) else null,
                    placeholder = "Touchez une ligne pour la voir ici.",
                )

                ficheOnly -> FichePane(state, viewModel, onOpenTransaction, onOpenOperation)

                else -> ListPane(state, viewModel)
            }
        }
    }
}

/** The filters over the list, whatever the list holds (a spinner, an error, nothing, the rows). */
@Composable
private fun ListPane(state: ReviewListUiState, viewModel: ReviewListViewModel) {
    Column(modifier = Modifier.fillMaxSize().testTag(UiTags.REVIEW_LIST)) {
        FilterHeader(state, viewModel)
        HorizontalDivider()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                !state.loaded && state.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                !state.loaded -> EmptyState(
                    title = "Chargement impossible",
                    description = "La revue n'a pas pu être lue. Vérifiez votre connexion.",
                    actionLabel = "Réessayer",
                    onAction = viewModel::refresh,
                )

                state.isEmpty -> EmptyState(
                    title = "Rien à afficher",
                    description = "Aucune ligne pour ces filtres.",
                    modifier = Modifier.testTag(UiTags.REVIEW_LIST_EMPTY),
                )

                else -> Rows(state, viewModel)
            }
        }
    }
}

@Composable
private fun FilterHeader(state: ReviewListUiState, viewModel: ReviewListViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReviewState.entries.forEach { pill ->
                FilterChip(
                    selected = state.state == pill,
                    onClick = { viewModel.setState(pill) },
                    label = { Text(reviewPillLabel(pill, state.counts.of(pill))) },
                    modifier = Modifier.testTag(stateTag(pill)),
                )
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::previousMonth, modifier = Modifier.testTag(UiTags.REVIEW_LIST_MONTH_PREVIOUS)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mois précédent")
            }
            FilterChip(
                selected = state.period == ReviewPeriod.MONTH,
                onClick = { viewModel.setPeriod(ReviewPeriod.MONTH) },
                label = { Text(reviewMonthLabel(state.month)) },
                modifier = Modifier.testTag(UiTags.REVIEW_LIST_PERIOD_MONTH),
            )
            IconButton(onClick = viewModel::nextMonth, modifier = Modifier.testTag(UiTags.REVIEW_LIST_MONTH_NEXT)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mois suivant")
            }
            FilterChip(
                selected = state.period == ReviewPeriod.SINCE_REVIEW,
                onClick = { viewModel.setPeriod(ReviewPeriod.SINCE_REVIEW) },
                label = { Text(ReviewPeriod.SINCE_REVIEW.label) },
                modifier = Modifier.testTag(UiTags.REVIEW_LIST_PERIOD_SINCE_REVIEW),
            )
            FilterChip(
                selected = state.period == ReviewPeriod.ALL,
                onClick = { viewModel.setPeriod(ReviewPeriod.ALL) },
                label = { Text(ReviewPeriod.ALL.label) },
                modifier = Modifier.testTag(UiTags.REVIEW_LIST_PERIOD_ALL),
            )
        }
    }
}

private fun stateTag(state: ReviewState): String = when (state) {
    ReviewState.ALL -> UiTags.REVIEW_LIST_PILL_ALL
    ReviewState.UNRECOGNISED -> UiTags.REVIEW_LIST_PILL_UNRECOGNISED
    ReviewState.RECOGNISED -> UiTags.REVIEW_LIST_PILL_RECOGNISED
}

// What the list draws: a heading, then the rows of its group.
private sealed interface ListEntry {
    data class Heading(val recognised: Boolean, val count: Int) : ListEntry

    data class Line(val line: ReviewQueueItem) : ListEntry
}

@Composable
private fun Rows(state: ReviewListUiState, viewModel: ReviewListViewModel) {
    val listState = rememberLazyListState()
    val entries = remember(state.items, state.visibleIds) {
        buildList {
            val waiting = state.unrecognisedRows
            val recognised = state.recognisedRows
            if (waiting.isNotEmpty()) {
                add(ListEntry.Heading(recognised = false, count = waiting.size))
                waiting.forEach { add(ListEntry.Line(it)) }
            }
            if (recognised.isNotEmpty()) {
                add(ListEntry.Heading(recognised = true, count = recognised.size))
                recognised.forEach { add(ListEntry.Line(it)) }
            }
        }
    }

    // Back from a fiche (or a tablet's list), the line the owner walked to is in view.
    LaunchedEffect(state.selectedId, state.ficheOpen) {
        val index = entries.indexOfFirst { it is ListEntry.Line && it.line.id == state.selectedId }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem(index)
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(
            entries,
            key = { entry ->
                when (entry) {
                    is ListEntry.Heading -> "heading:${entry.recognised}"
                    is ListEntry.Line -> entry.line.id
                }
            },
        ) { entry ->
            when (entry) {
                is ListEntry.Heading -> Text(
                    "${if (entry.recognised) "Reconnues" else "Non reconnues"} · ${entry.count}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag(if (entry.recognised) UiTags.REVIEW_LIST_SECTION_RECOGNISED else UiTags.REVIEW_LIST_SECTION_UNRECOGNISED),
                )

                is ListEntry.Line -> {
                    LineRow(
                        line = entry.line,
                        isCursor = entry.line.id == state.selectedId,
                        onClick = { viewModel.select(entry.line.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** « À qualifier » in orange, « Reconnue » in mint: the theme's warning and success, never a palette of its own. */
@Composable
private fun StatePill(recognised: Boolean, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val feedback = if (dark) MaggieTokens.feedbackDark else MaggieTokens.feedbackLight
    val color: Color = if (recognised) feedback.success else feedback.warning
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.16f),
        // One node that reads « Reconnue »: the tag and the screen reader get the words, not a bare box.
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Text(
            if (recognised) "Reconnue" else "À qualifier",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LineRow(line: ReviewQueueItem, isCursor: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(UiTags.REVIEW_LIST_ROW)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                line.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCursor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                listOfNotNull(line.bookedAt, line.contact?.name).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatePill(line.recognised, Modifier.testTag(UiTags.REVIEW_LIST_ROW_STATE))
            Text(formatCents(line.amountCents, line.currency), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The fiche of the line the cursor is on: « Précédent » / « Suivant » over its gestures; a swipe does the same. */
@Composable
private fun FichePane(
    state: ReviewListUiState,
    viewModel: ReviewListViewModel,
    onOpenTransaction: (ReviewQueueItem) -> Unit,
    onOpenOperation: (ReviewQueueItem) -> Unit,
) {
    val line = state.current ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .swipeToNavigate(onPrevious = viewModel::previous, onNext = viewModel::next)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(UiTags.REVIEW_FICHE),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReviewSheetNav(
            position = state.position,
            total = state.total,
            hasPrevious = state.hasPrevious,
            hasNext = state.hasNext,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
        )
        FicheCard(line, state, viewModel, onOpenTransaction, onOpenOperation)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FicheCard(
    line: ReviewQueueItem,
    state: ReviewListUiState,
    viewModel: ReviewListViewModel,
    onOpenTransaction: (ReviewQueueItem) -> Unit,
    onOpenOperation: (ReviewQueueItem) -> Unit,
) {
    val suggestion = line.suggestion
    // Filed already, only « Ce virement est » is missing: no category to ask for.
    val asksForCategory = !line.recognised && line.reason != TO_SORT_REASON_PEER_TRANSFER
    var qualifying by rememberSaveable(line.id) { mutableStateOf(false) }
    val controlled = state.isControlled(line)
    val peerActions = PeerTransferActions(
        onKindChange = viewModel::choosePeerKind,
        onToggleShare = viewModel::togglePeerShare,
        onShareAmountChange = viewModel::changePeerShareAmount,
        onPickPerson = viewModel::openPeerContactPicker,
        onDebtAmountChange = viewModel::changePeerDebtAmount,
        onSubjectChange = viewModel::changePeerSubject,
        onNotesChange = viewModel::changePeerNotes,
        onSave = viewModel::savePeer,
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatePill(line.recognised, Modifier.testTag(UiTags.REVIEW_FICHE_STATE))
                if (controlled) {
                    Text(
                        "Contrôlée",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(UiTags.REVIEW_FICHE_CHECKED),
                    )
                }
            }
            Text(line.label, style = MaterialTheme.typography.titleMedium)
            Text(formatCents(line.amountCents, line.currency), style = MaterialTheme.typography.headlineSmall)
            Text(
                listOfNotNull(line.bookedAt, line.accountName).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!line.recognised) {
                val contact = line.contact
                if (contact != null) {
                    Text(
                        "Contact : ${contact.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(UiTags.REVIEW_FICHE_CONTACT_NAME),
                    )
                } else if (line.asksForContact) {
                    TextButton(
                        onClick = viewModel::openContactPicker,
                        enabled = !state.isLinking && !state.isSorting,
                        modifier = Modifier.testTag(UiTags.REVIEW_FICHE_CONTACT),
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
                        onClick = viewModel::validate,
                        enabled = state.canValidate,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(UiTags.REVIEW_FICHE_VALIDATE),
                    ) { Text("Valider") }
                }
                OutlinedButton(
                    onClick = viewModel::openCategorySearch,
                    enabled = !state.isSorting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(UiTags.REVIEW_FICHE_OTHER),
                ) { Text("Autre catégorie") }
                OutlinedButton(
                    onClick = viewModel::openRuleDraft,
                    enabled = !state.isSorting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(UiTags.REVIEW_FICHE_CREATE_OPERATION),
                ) { Text("Créer une opération récurrente") }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.alwaysForMerchant,
                            role = Role.Switch,
                            onValueChange = viewModel::setAlwaysForMerchant,
                        )
                        .testTag(UiTags.REVIEW_FICHE_ALWAYS),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Toujours pour ce marchand", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = state.alwaysForMerchant, onCheckedChange = null)
                }

                TextButton(
                    onClick = { qualifying = !qualifying },
                    modifier = Modifier.testTag(UiTags.REVIEW_FICHE_QUALIFICATION),
                ) { Text(qualificationToggleLabel(state.qualification, qualifying)) }
                if (qualifying) {
                    QualificationFlags(
                        qualification = state.qualification,
                        onQualificationChange = viewModel::setQualification,
                        enabled = !state.isSorting,
                    )
                }
            }

            if (line.recognised) {
                HorizontalDivider()
                line.categoryPath?.let { Text("→ $it", style = MaterialTheme.typography.titleSmall) }
                // Recognised by an operation: its name, until the line is corrected by hand.
                line.operationLabel?.let {
                    Text(
                        listOfNotNull(qualificationProvenance(line.qualifiedBy), it).joinToString(" : "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = viewModel::control,
                    enabled = !controlled && !state.isControlling,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(UiTags.REVIEW_FICHE_CONTROL),
                ) { Text("Correct") }
                OutlinedButton(
                    onClick = viewModel::startCorrection,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(UiTags.REVIEW_FICHE_CORRECT),
                ) { Text("Corriger") }
            }

            if (line.judgeable) {
                Text("Cette dépense", style = MaterialTheme.typography.bodyLarge)
                SatisfactionChips(
                    retrospect = line.retrospect,
                    enabled = !state.isJudging,
                    onJudge = viewModel::judge,
                    keepTag = UiTags.REVIEW_FICHE_KEEP,
                    avoidableTag = UiTags.REVIEW_FICHE_AVOIDABLE,
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A filed transfer has a category: the API refuses an internal transfer, and a filter has nothing to file.
                if (asksForCategory) {
                    TextButton(
                        onClick = viewModel::openTransfer,
                        enabled = !state.isSorting,
                        modifier = Modifier.testTag(UiTags.REVIEW_FICHE_TRANSFER),
                    ) { Text("Virement interne") }
                }
                if (line.recognised && (line.qualifiedBySeriesId != null || line.qualifiedByRuleId != null)) {
                    TextButton(
                        onClick = { onOpenOperation(line) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.REVIEW_FICHE_OPEN_OPERATION),
                    ) { Text("Voir l'opération récurrente") }
                }
                TextButton(
                    onClick = { onOpenTransaction(line) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.REVIEW_FICHE_OPEN_SHEET),
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

/** « Corriger »: the category and the qualification put by hand, the same section as the transaction's sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CorrectionScreen(
    categories: List<Category>,
    correction: ReviewCorrection,
    viewModel: ReviewListViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val line = correction.line

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Corriger") },
                navigationIcon = {
                    IconButton(onClick = viewModel::cancelCorrection) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(line.label, style = MaterialTheme.typography.titleMedium)
            Text(formatCents(line.amountCents, line.currency), style = MaterialTheme.typography.headlineSmall)
            qualificationProvenance(line.qualifiedBy)?.let {
                Text(
                    "$it. Corriger la pose à la main : la ligne ne sera plus reconnue par la règle ou la série.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (correction.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            QualificationSection(
                categories = categories,
                categoryId = correction.categoryId,
                qualification = correction.qualification,
                onCategoryChange = viewModel::setCorrectionCategory,
                onQualificationChange = viewModel::setCorrectionQualification,
                categoryTag = UiTags.REVIEW_CORRECTION_CATEGORY,
                searchTag = UiTags.REVIEW_CORRECTION_SEARCH,
                enabled = !correction.isLoading && !correction.isSaving,
            )

            correction.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = viewModel::saveCorrection,
                enabled = correction.canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(UiTags.REVIEW_CORRECTION_SAVE),
            ) { Text("Enregistrer") }
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
                    modifier = Modifier.testTag(UiTags.REVIEW_FICHE_TRANSFER_NONE),
                ) { Text("Sans contrepartie importée") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
