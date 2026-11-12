package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Period
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.AgendaTopBar
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.FabModule
import com.maggie.app.ui.components.ModuleFab
import com.maggie.app.ui.layout.ListDetailPane
import com.maggie.app.ui.navigation.AgendaSection

/**
 * « Périodes » of Agenda (MAG-815). On a phone a row opens the form's route ([onOpenForm]); on a tablet the form is the
 * detail pane beside the list, the selected row highlighted.
 */
@Composable
fun PeriodListScreen(
    viewModel: PeriodViewModel,
    showsDetailPane: Boolean,
    onOpenForm: (String?) -> Unit,
    modifier: Modifier = Modifier,
    requestedId: String? = null,
    onRequestHandled: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    // A chip of the calendar asks for this period's form: it opens in the pane once the list is read.
    LaunchedEffect(requestedId, uiState.hasLoaded, showsDetailPane) {
        if (requestedId != null && uiState.hasLoaded && showsDetailPane) {
            viewModel.openEdit(requestedId)
            onRequestHandled()
        }
    }
    val open: (String?) -> Unit = { id ->
        if (!showsDetailPane) onOpenForm(id) else if (id == null) viewModel.openNew() else viewModel.openEdit(id)
    }

    if (!showsDetailPane) {
        PeriodList(viewModel, uiState, onOpen = open, modifier = modifier)
        return
    }

    val form = uiState.form
    LaunchedEffect(form?.done) {
        if (form?.done == true) viewModel.closeForm()
    }
    ListDetailPane(
        showsDetailPane = true,
        list = { PeriodList(viewModel, uiState, onOpen = open) },
        detail = form?.let { current ->
            {
                PeriodFormContent(
                    form = current,
                    agendas = uiState.agendas,
                    onEdit = viewModel::editForm,
                    onSave = viewModel::save,
                    onDelete = { current.periodId?.let(viewModel::delete) },
                    onClose = null,
                )
            }
        },
        placeholder = "Aucune période choisie",
        modifier = modifier,
    )
}

@Composable
private fun PeriodList(
    viewModel: PeriodViewModel,
    state: PeriodUiState,
    onOpen: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbar(
        error = state.error.takeIf { state.hasLoaded },
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
        message = state.error ?: PeriodViewModel.MESSAGE_LOAD,
    )
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }
    val groups = remember(state.periods, state.tab) { viewModel.groups() }

    Scaffold(
        modifier = modifier.testTag(UiTags.PERIODS),
        topBar = { AgendaTopBar(AgendaSection.PERIODS) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ModuleFab(FabModule.AGENDA, "Nouvelle période", onClick = { onOpen(null) }, tag = UiTags.PERIOD_ADD)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PeriodTab.entries.forEach { tab ->
                    FilterChip(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        label = { Text(tab.label) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(if (tab == PeriodTab.UPCOMING) UiTags.PERIOD_TAB_UPCOMING else UiTags.PERIOD_TAB_PAST),
                    )
                }
            }
            when {
                state.isLoading && !state.hasLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                !state.hasLoaded -> EmptyState(
                    modifier = Modifier.testTag(UiTags.PERIOD_EMPTY),
                    title = "Périodes indisponibles",
                    description = PeriodViewModel.MESSAGE_LOAD,
                    actionLabel = "Réessayer",
                    onAction = viewModel::refresh,
                )
                groups.isEmpty() -> EmptyState(
                    modifier = Modifier.testTag(UiTags.PERIOD_EMPTY),
                    title = if (state.tab == PeriodTab.UPCOMING) "Aucune période à venir" else "Aucune période passée",
                    description = "Une période dit de quel genre sont des jours : la garde, des vacances, un voyage.",
                    actionLabel = if (state.tab == PeriodTab.UPCOMING) "Nouvelle période" else null,
                    onAction = if (state.tab == PeriodTab.UPCOMING) ({ onOpen(null) }) else null,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    groups.forEach { group ->
                        item(key = "group-${group.title}") {
                            Text(
                                group.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(group.periods, key = { "${group.title}-${it.id}" }) { period ->
                            PeriodRow(period, state.agendas, selected = period.id == state.selectedId) { onOpen(period.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeriodRow(period: Period, agendas: List<Agenda>, selected: Boolean, onClick: () -> Unit) {
    val color = periodColor(period, agendas, MaterialTheme.colorScheme.primary)
    val agendaName = agendas.firstOrNull { it.id == period.agendaId }?.name
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(UiTags.periodRow(period.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HatchedBar(color, Modifier.width(6.dp).heightIn(min = 40.dp))
        Column(Modifier.weight(1f)) {
            Text(period.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                periodDatesLabel(period),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (agendaName != null) {
            Text(
                agendaName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
        }
    }
}
