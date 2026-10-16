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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CategoryTree
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.QualificationKind
import com.maggie.app.data.model.RETROSPECT_AVOIDABLE
import com.maggie.app.data.model.RETROSPECT_KEEP
import com.maggie.app.data.model.TransactionToSort
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.toSortCounter
import com.maggie.app.data.model.transferLegSummary
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.CategorySearchDialog
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.layout.ListDetailPane
import com.maggie.app.ui.uiTagRoot

/**
 * « À qualifier »: the lines without a category, oldest first, one card at a time (MAG-423, MAG-458).
 * On a phone the card is the screen; with [showsDetailPane] (a tablet) the pile is a list and the card
 * sits beside it. [onOpenTransaction] opens the line's own sheet.
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

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
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
            TopAppBar(
                title = { Text("À qualifier") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
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
                    list = { PileList(state, onSelect = viewModel::select) },
                    detail = { CardPane(state, viewModel, onOpenTransaction) },
                    placeholder = "Touchez une ligne pour la qualifier ici.",
                )

                else -> CardPane(state, viewModel, onOpenTransaction)
            }
        }
    }
}

@Composable
private fun CardPane(state: ToSortUiState, viewModel: ToSortViewModel, onOpenTransaction: (TransactionToSort) -> Unit) {
    val line = state.current ?: return

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            toSortCounter(state.total),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(UiTags.TO_SORT_REMAINING),
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
) {
    val suggestion = line.suggestion
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

            if (line.judgeable) {
                Text("Cette dépense", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = line.retrospect == RETROSPECT_KEEP,
                        onClick = { onJudge(RETROSPECT_KEEP) },
                        enabled = !state.isJudging,
                        label = { Text("À conserver") },
                        modifier = Modifier.testTag(UiTags.TO_SORT_KEEP),
                    )
                    FilterChip(
                        selected = line.retrospect == RETROSPECT_AVOIDABLE,
                        onClick = { onJudge(RETROSPECT_AVOIDABLE) },
                        enabled = !state.isJudging,
                        label = { Text("Superflue") },
                        modifier = Modifier.testTag(UiTags.TO_SORT_AVOIDABLE),
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun PileList(state: ToSortUiState, onSelect: (String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                toSortCounter(state.total),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp).testTag(UiTags.TO_SORT_REMAINING),
            )
        }
        items(state.pile, key = { it.id }) { line ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(line.id) }
                    .testTag(UiTags.TO_SORT_ROW)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    line.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (line.id == state.current?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    listOfNotNull(line.bookedAt, formatCents(line.amountCents, line.currency)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
