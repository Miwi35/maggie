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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.maggie.app.data.model.ContactBalance
import com.maggie.app.data.model.ContactDebt
import com.maggie.app.data.model.PeerDebt
import com.maggie.app.data.model.balanceLabel
import com.maggie.app.data.model.compensationLabel
import com.maggie.app.data.model.debtDayLabel
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.owesYouLabel
import com.maggie.app.data.model.youOweLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.layout.ListDetailPane

/**
 * « Créances et dettes » (MAG-455), reached from « Comptes » and from the pill of the home tile. On a phone a
 * person opens in place; with [showsDetailPane] (a tablet) the person or the object opens beside the list.
 * [onOpenContact] leads to a person's page, [onAddPerson] to the new-contact dialog.
 */
@Composable
fun PeerDebtsScreen(
    viewModel: PeerDebtsViewModel,
    onBack: () -> Unit,
    onOpenContact: (String) -> Unit,
    onAddPerson: () -> Unit,
    showsDetailPane: Boolean = false,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        // Before a first read the screen says it with a retry; after it, the figures shown stay and the error is a message.
        state.error?.takeIf { state.loaded }?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    if (!showsDetailPane) {
        PeerDebtsList(state, viewModel, snackbarHostState, onBack, onOpenContact, onAddPerson, inline = true)
        return
    }

    val person = state.selectedBalance
    val debt = state.selectedDebt
    ListDetailPane(
        showsDetailPane = true,
        list = { PeerDebtsList(state, viewModel, snackbarHostState, onBack, onOpenContact, onAddPerson, inline = false) },
        detail = when {
            state.tab == PeerDebtsTab.BY_PERSON && person != null -> {
                { Box(Modifier.padding(16.dp)) { PersonDetail(person, state.linesOf(person.contactId), onOpenContact) } }
            }
            state.tab == PeerDebtsTab.BY_OBJECT && debt != null -> {
                { Box(Modifier.padding(16.dp)) { ObjectDetail(debt) } }
            }
            else -> null
        },
        placeholder = "Touchez une personne ou un objet pour voir le détail ici.",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeerDebtsList(
    state: PeerDebtsUiState,
    viewModel: PeerDebtsViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenContact: (String) -> Unit,
    onAddPerson: () -> Unit,
    inline: Boolean,
) {
    Scaffold(
        modifier = Modifier.testTag(UiTags.PEER_DEBTS),
        topBar = {
            TopAppBar(
                title = { Text("Créances et dettes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    TextButton(onClick = onAddPerson, modifier = Modifier.testTag(UiTags.PEER_DEBTS_ADD_PERSON)) {
                        Text("+ Personne")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        when {
            !state.loaded && state.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            !state.loaded -> Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    state.error ?: "Chargement impossible. Vérifiez votre connexion.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = viewModel::refresh,
                    modifier = Modifier.padding(top = 12.dp).testTag(UiTags.PEER_DEBTS_RETRY),
                ) { Text("Réessayer") }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Totals(state) }
                item { Tabs(state.tab, viewModel::selectTab) }

                if (state.isEmpty) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().testTag(UiTags.PEER_DEBTS_EMPTY)) {
                            EmptyState(
                                title = "Rien en cours",
                                description = "Personne ne te doit rien et tu ne dois rien : partage une dépense ou affecte un virement pour commencer.",
                            )
                        }
                    }
                } else if (state.tab == PeerDebtsTab.BY_PERSON) {
                    items(state.summary.byContact, key = { it.contactId }) { balance ->
                        PersonRow(
                            balance = balance,
                            expanded = inline && state.selectedContactId == balance.contactId,
                            selected = !inline && state.selectedContactId == balance.contactId,
                            lines = state.linesOf(balance.contactId),
                            onToggle = { viewModel.toggleContact(balance.contactId) },
                            onOpenContact = onOpenContact,
                        )
                    }
                } else {
                    items(state.debts, key = { it.id }) { debt ->
                        ObjectRow(debt, selected = !inline && state.selectedDebtId == debt.id, onClick = { viewModel.toggleDebt(debt.id) })
                    }
                }
            }
        }
    }
}

/** « On te doit » (what the cushion advanced) and « Tu dois » (what is not an income or a saving): the two sides apart. */
@Composable
private fun Totals(state: PeerDebtsUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        TotalTile("On te doit", "avancés sur ton matelas", state.summary.owedToMeCents, UiTags.PEER_DEBTS_TILE_OWED_TO_ME, Modifier.weight(1f))
        TotalTile("Tu dois", "hors revenus et épargne", state.summary.iOweCents, UiTags.PEER_DEBTS_TILE_I_OWE, Modifier.weight(1f))
    }
}

@Composable
private fun TotalTile(title: String, caption: String, cents: Int, tag: String, modifier: Modifier) {
    MaggieCard(modifier = modifier.testTag(tag)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatCents(cents), style = MaterialTheme.typography.headlineSmall)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Tabs(tab: PeerDebtsTab, onSelect: (PeerDebtsTab) -> Unit) {
    TabRow(selectedTabIndex = tab.ordinal) {
        Tab(
            selected = tab == PeerDebtsTab.BY_PERSON,
            onClick = { onSelect(PeerDebtsTab.BY_PERSON) },
            text = { Text("Par personne") },
            modifier = Modifier.testTag(UiTags.PEER_DEBTS_TAB_PERSON),
        )
        Tab(
            selected = tab == PeerDebtsTab.BY_OBJECT,
            onClick = { onSelect(PeerDebtsTab.BY_OBJECT) },
            text = { Text("Par objet") },
            modifier = Modifier.testTag(UiTags.PEER_DEBTS_TAB_OBJECT),
        )
    }
}

@Composable
private fun PersonRow(
    balance: ContactBalance,
    expanded: Boolean,
    selected: Boolean,
    lines: List<ContactDebt>,
    onToggle: () -> Unit,
    onOpenContact: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag(UiTags.PEER_DEBTS_PERSON)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    balance.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(balanceLabel(balance.netCents), style = MaterialTheme.typography.bodyMedium)
        }
        if (balance.compensates) {
            compensationLabel(balance.name, balance.netCents)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp).testTag(UiTags.PEER_DEBTS_COMPENSATION),
                )
            }
        }
        if (expanded) PersonDetail(balance, lines, onOpenContact)
        HorizontalDivider()
    }
}

/** The lines per object a person is left with, and the way to their page. */
@Composable
private fun PersonDetail(balance: ContactBalance, lines: List<ContactDebt>, onOpenContact: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 8.dp)) {
        if (balance.compensates) {
            compensationLabel(balance.name, balance.netCents)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        lines.forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(UiTags.PEER_DEBTS_PERSON_LINE),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(line.subject, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    if (line.direction == PeerDebt.DIRECTION_I_OWE) youOweLabel(line.remainingCents) else owesYouLabel(line.remainingCents),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(
            onClick = { onOpenContact(balance.contactId) },
            modifier = Modifier.testTag(UiTags.PEER_DEBTS_CONTACT_LINK),
        ) { Text("Fiche et comptes de ${balance.name}") }
    }
}

@Composable
private fun ObjectRow(debt: PeerDebt, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp).testTag(UiTags.PEER_DEBTS_OBJECT),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                debt.`object`.orEmpty().ifBlank { "Dépense partagée" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(owedSentence(debt), style = MaterialTheme.typography.bodyMedium)
        }
        Text(peopleSentence(debt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        debt.notes?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

/** An object on the tablet: who still owes (or is owed) on it, and the transfers that gave shares back. */
@Composable
private fun ObjectDetail(debt: PeerDebt) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(debt.`object`.orEmpty().ifBlank { "Dépense partagée" }, style = MaterialTheme.typography.titleMedium)
        Text(owedSentence(debt), style = MaterialTheme.typography.bodyMedium)
        debt.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        debt.shares.filter { it.remainingCents > 0 }.forEach {
            Text("${it.contactName} ${formatCents(it.remainingCents)}", style = MaterialTheme.typography.bodyMedium)
        }
        if (debt.history.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Historique", style = MaterialTheme.typography.titleSmall)
            debt.history.forEach {
                Text(
                    listOfNotNull(debtDayLabel(it.bookedAt), it.contactName, formatCents(it.amountCents)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** « on te doit 400,00 € » / « tu dois 280,00 € »: what is left of the object. */
internal fun owedSentence(debt: PeerDebt): String =
    if (debt.direction == PeerDebt.DIRECTION_I_OWE) "tu dois ${formatCents(debt.remainingCents)}" else "on te doit ${formatCents(debt.remainingCents)}"

/** « Julie 200,00 € · Tom 200,00 € »: who still owes (or is owed) on one object. */
internal fun peopleSentence(debt: PeerDebt): String =
    debt.shares.filter { it.remainingCents > 0 }.joinToString(" · ") { "${it.contactName} ${formatCents(it.remainingCents)}" }
