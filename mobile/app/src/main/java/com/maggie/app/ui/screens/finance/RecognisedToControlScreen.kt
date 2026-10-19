package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.OPERATION_SERIES
import com.maggie.app.data.model.RETROSPECT_AVOIDABLE
import com.maggie.app.data.model.RETROSPECT_KEEP
import com.maggie.app.data.model.RecognisedGroup
import com.maggie.app.data.model.RecognisedLine
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.operationKindLabel
import com.maggie.app.data.model.qualificationProvenance
import com.maggie.app.data.model.recognisedGroupTitle
import com.maggie.app.data.model.toControlCounter
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.layout.ListDetailPane

/**
 * « Reconnues à contrôler » (MAG-460, MAG-507): what the rules and the series filed this month, by operation, for the
 * owner to check with one thumb. The page opens on the list, still grouped: a group is checked in one tap (« Valider »),
 * or kept whole (« Tout à conserver »). Touching a line opens its fiche, where it can be judged, corrected by hand,
 * followed to its operation or to its sheet, and where « Précédent » / « Suivant » (or a swipe) walks the list in its
 * order. Back returns to the list on the line last open. With [showsDetailPane] (a tablet) the fiche sits beside the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognisedToControlScreen(
    viewModel: RecognisedToControlViewModel,
    onBack: () -> Unit,
    showsDetailPane: Boolean = false,
    onOpenOperation: (RecognisedGroup) -> Unit = {},
    onOpenTransaction: (RecognisedLine) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    ErrorSnackbar(
        error = state.actionError,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearActionError,
        message = "Geste impossible. Les lignes restent à contrôler.",
    )

    state.correction?.let { correction ->
        BackHandler(onBack = viewModel::cancelCorrection)
        CorrectionScreen(state.categories, correction, viewModel, snackbarHostState)
        return
    }

    // On a phone the fiche takes the screen; beside the list (tablet) back leaves the page as it always did.
    val ficheOnly = !showsDetailPane && state.ficheOpen && state.current != null
    BackHandler(enabled = ficheOnly, onBack = viewModel::backToList)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reconnues à contrôler") },
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
                    description = "Les lignes reconnues n'ont pas pu être lues. Vérifiez votre connexion.",
                    actionLabel = "Réessayer",
                    onAction = viewModel::refresh,
                )

                state.isEmpty -> EmptyState(
                    title = toControlCounter(0),
                    description = "Toutes les lignes reconnues ce mois ont été contrôlées.",
                    modifier = Modifier.testTag(UiTags.TO_CONTROL_EMPTY),
                )

                showsDetailPane -> ListDetailPane(
                    showsDetailPane = true,
                    list = { GroupList(state, viewModel) },
                    detail = if (state.ficheOpen && state.current != null) {
                        { Fiche(state, viewModel, onOpenOperation, onOpenTransaction) }
                    } else {
                        null
                    },
                    placeholder = "Touchez une ligne pour la contrôler ici.",
                )

                ficheOnly -> Fiche(state, viewModel, onOpenOperation, onOpenTransaction)

                else -> GroupList(state, viewModel)
            }
        }
    }
}

@Composable
private fun GroupList(state: RecognisedToControlUiState, viewModel: RecognisedToControlViewModel) {
    val listState = rememberLazyListState()

    // Coming back from a fiche, the list opens on the group of the line last open (the counter is item 0).
    LaunchedEffect(Unit) {
        val at = state.data.groups.indexOfFirst { group -> group.transactions.any { it.id == state.selectedId } }
        if (at >= 0) {
            listState.scrollToItem(at + 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "counter") {
            Text(
                toControlCounter(state.data.total),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.TO_CONTROL_REMAINING),
            )
        }
        items(state.data.groups, key = { it.key }) { group ->
            GroupCard(
                group = group,
                busy = state.controllingKey != null,
                selectedId = state.selectedId,
                onValidate = { viewModel.controlGroup(group.key, keep = false) },
                onKeepAll = { viewModel.controlGroup(group.key, keep = true) },
                onSelect = viewModel::select,
            )
        }
    }
}

@Composable
private fun GroupCard(
    group: RecognisedGroup,
    busy: Boolean,
    selectedId: String?,
    onValidate: () -> Unit,
    onKeepAll: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.TO_CONTROL_GROUP),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(recognisedGroupTitle(group), style = MaterialTheme.typography.titleMedium)
            Text(
                listOf(operationKindLabel(group.operationType), formatCents(group.totalCents)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onValidate,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(UiTags.TO_CONTROL_VALIDATE),
            ) { Text("Valider") }
            if (group.unratedCount > 0) {
                OutlinedButton(
                    onClick = onKeepAll,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(UiTags.TO_CONTROL_KEEP_ALL),
                ) { Text("Tout à conserver") }
            }

            group.transactions.forEach { line ->
                LineRow(line = line, selected = line.id == selectedId, onClick = { onSelect(line.id) })
            }
        }
    }
}

/** A line of the list: touching it opens its fiche. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineRow(line: RecognisedLine, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(UiTags.TO_CONTROL_ROW),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(line.label, style = MaterialTheme.typography.bodyLarge)
            Text(formatCents(line.amountCents, line.currency), style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(line.bookedAt, line.accountName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            line.categoryPath?.let {
                Text("→ $it", style = MaterialTheme.typography.bodyMedium)
            }
            retrospectLabel(line.retrospect)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun retrospectLabel(retrospect: String): String? = when (retrospect) {
    RETROSPECT_KEEP -> "À conserver"
    RETROSPECT_AVOIDABLE -> "Superflue"
    else -> null
}

/** The fiche of the line the cursor is on: « Précédent » / « Suivant » over its gestures; a swipe does the same. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Fiche(
    state: RecognisedToControlUiState,
    viewModel: RecognisedToControlViewModel,
    onOpenOperation: (RecognisedGroup) -> Unit,
    onOpenTransaction: (RecognisedLine) -> Unit,
) {
    val line = state.current ?: return
    val group = state.currentGroup
    val judging = state.judgingId != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .swipeToNavigate(onPrevious = viewModel::previous, onNext = viewModel::next)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(UiTags.TO_CONTROL_FICHE),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReviewSheetNav(
            position = state.position,
            total = state.total,
            hasPrevious = state.hasPrevious,
            hasNext = state.hasNext,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
        )
        group?.let {
            Text(
                recognisedGroupTitle(it),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                line.categoryPath?.let {
                    Text("→ $it", style = MaterialTheme.typography.bodyMedium)
                }

                if (line.judgeable) {
                    SatisfactionChips(
                        retrospect = line.retrospect,
                        enabled = !judging,
                        onJudge = { verdict -> viewModel.judge(line.id, verdict) },
                        keepTag = UiTags.TO_CONTROL_KEEP,
                        avoidableTag = UiTags.TO_CONTROL_AVOIDABLE,
                    )
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { viewModel.startCorrection(line.id) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.TO_CONTROL_CORRECT),
                    ) { Text("Corriger") }
                    if (group != null && group.isOpenable) {
                        TextButton(
                            onClick = { onOpenOperation(group) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.TO_CONTROL_OPEN_OPERATION),
                        ) { Text(if (group.operationType == OPERATION_SERIES) "Voir l'opération récurrente" else "Voir la règle") }
                    }
                    TextButton(
                        onClick = { onOpenTransaction(line) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.TO_CONTROL_OPEN_SHEET),
                    ) { Text("Voir la fiche") }
                }
            }
        }
    }
}

/** « Corriger »: the category and the qualification put by hand, the same section as the transaction's sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CorrectionScreen(
    categories: List<Category>,
    correction: RecognisedCorrection,
    viewModel: RecognisedToControlViewModel,
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
                categoryTag = UiTags.TO_CONTROL_CATEGORY,
                searchTag = UiTags.TO_CONTROL_SEARCH,
                enabled = !correction.isLoading && !correction.isSaving,
            )

            correction.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = viewModel::saveCorrection,
                enabled = correction.canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(UiTags.TO_CONTROL_SAVE),
            ) { Text("Enregistrer") }
        }
    }
}
