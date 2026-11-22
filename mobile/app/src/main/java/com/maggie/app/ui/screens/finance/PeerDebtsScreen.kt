package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ContactBalance
import com.maggie.app.data.model.ContactDebt
import com.maggie.app.data.model.PeerDebt
import com.maggie.app.data.model.balanceVerdict
import com.maggie.app.data.model.compensationLabel
import com.maggie.app.data.model.debtDayLabel
import com.maggie.app.data.model.sinceLabel
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.owesYouLabel
import com.maggie.app.data.model.youOweLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.layout.ListDetailPane
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.maggieSurface

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
                { Box(Modifier.padding(16.dp)) { PersonDetail(person, state.linesOf(person.contactId), onOpenContact, indent = 0.dp) } }
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
                    val surface = maggieSurface()
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .height(44.dp)
                            .background(surface.text.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
                            .clickable(role = Role.Button, onClick = onAddPerson)
                            .testTag(UiTags.PEER_DEBTS_ADD_PERSON)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("+ Personne", fontSize = 14.sp) }
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    items(byPersonOrder(state.summary.byContact), key = { it.contactId }) { balance ->
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

/** « On te doit » and « Tu dois »: the two sides apart, each tinted with its own colour. */
@Composable
private fun Totals(state: PeerDebtsUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        TotalTile("On te doit", state.summary.owedToMeCents, MaggieTokens.Signal.info, UiTags.PEER_DEBTS_TILE_OWED_TO_ME, Modifier.weight(1f))
        TotalTile("Tu dois", state.summary.iOweCents, MaggieTokens.Signal.warning, UiTags.PEER_DEBTS_TILE_I_OWE, Modifier.weight(1f))
    }
}

@Composable
private fun TotalTile(title: String, cents: Int, tint: Color, tag: String, modifier: Modifier) {
    MaggieCard(modifier = modifier.testTag(tag), containerColor = tint.copy(alpha = 0.10f)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, fontSize = 13.sp, color = maggieSurface().textMuted)
            Text(formatCents(cents), fontSize = 26.sp, fontWeight = FontWeight.Light, color = tint)
        }
    }
}

@Composable
private fun Tabs(tab: PeerDebtsTab, onSelect: (PeerDebtsTab) -> Unit) {
    val surface = maggieSurface()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(surface.text.copy(alpha = 0.05f), RoundedCornerShape(999.dp))
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            Triple(PeerDebtsTab.BY_PERSON, "Par personne", UiTags.PEER_DEBTS_TAB_PERSON),
            Triple(PeerDebtsTab.BY_OBJECT, "Par objet", UiTags.PEER_DEBTS_TAB_OBJECT),
        ).forEach { (key, label, tag) ->
            val isSelected = key == tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(if (isSelected) surface.text else Color.Transparent, RoundedCornerShape(999.dp))
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(key) })
                    .testTag(tag),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) surface.background else surface.textMuted,
                )
            }
        }
    }
}

/** Those who owe come first, then those the owner owes; the API's order is kept inside each group. */
internal fun byPersonOrder(list: List<ContactBalance>): List<ContactBalance> = list.sortedBy { if (it.netCents > 0) 0 else 1 }

@Composable
private fun balanceColor(netCents: Int): Color = when {
    netCents > 0 -> MaggieTokens.Signal.info
    netCents < 0 -> MaggieTokens.Signal.warning
    else -> maggieSurface().textMuted
}

private fun directionColor(direction: String): Color =
    if (direction == PeerDebt.DIRECTION_I_OWE) MaggieTokens.Signal.warning else MaggieTokens.Signal.info

@Composable
private fun PersonRow(
    balance: ContactBalance,
    expanded: Boolean,
    selected: Boolean,
    lines: List<ContactDebt>,
    onToggle: () -> Unit,
    onOpenContact: (String) -> Unit,
) {
    val surface = maggieSurface()
    Column(modifier = Modifier.fillMaxWidth().testTag(UiTags.PEER_DEBTS_PERSON)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(onClick = onToggle)
                .padding(top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(surface.raised, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    balance.name.take(1).uppercase(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaggieTokens.Signal.label,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    balance.name,
                    fontSize = 16.sp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(balanceVerdict(balance.name, balance.netCents), fontSize = 13.sp, color = balanceColor(balance.netCents))
            }
            Text(
                formatCents(kotlin.math.abs(balance.netCents)),
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = balanceColor(balance.netCents),
            )
        }
        if (expanded) PersonDetail(balance, lines, onOpenContact, indent = 52.dp)
        HorizontalDivider()
    }
}

/** The lines per object a person is left with, the way to their page, and what compensates. */
@Composable
private fun PersonDetail(balance: ContactBalance, lines: List<ContactDebt>, onOpenContact: (String) -> Unit, indent: Dp) {
    val surface = maggieSurface()
    Column(modifier = Modifier.fillMaxWidth().padding(start = indent, bottom = 8.dp)) {
        lines.forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(UiTags.PEER_DEBTS_PERSON_LINE),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(line.subject, fontSize = 14.sp, color = surface.textMuted)
                    sinceLabel(line.since)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    if (line.direction == PeerDebt.DIRECTION_I_OWE) youOweLabel(line.remainingCents) else owesYouLabel(line.remainingCents),
                    fontSize = 14.sp,
                    color = directionColor(line.direction),
                )
            }
        }
        TextButton(
            onClick = { onOpenContact(balance.contactId) },
            modifier = Modifier.testTag(UiTags.PEER_DEBTS_CONTACT_LINK),
        ) { Text("Fiche et comptes de ${balance.name}") }
        if (balance.compensates) {
            compensationLabel(balance.name, balance.netCents)?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = surface.caption,
                    modifier = Modifier.padding(top = 4.dp).testTag(UiTags.PEER_DEBTS_COMPENSATION),
                )
            }
        }
    }
}

@Composable
private fun ObjectRow(debt: PeerDebt, selected: Boolean, onClick: () -> Unit) {
    val surface = maggieSurface()
    MaggieCard(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.PEER_DEBTS_OBJECT),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    debt.`object`.orEmpty().ifBlank { "Dépense partagée" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(owedSentence(debt), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = directionColor(debt.direction))
            }
            Text(peopleSentence(debt), fontSize = 13.sp, color = surface.textMuted)
            sinceLabel(debt.since)?.let { Text(it, fontSize = 13.sp, color = surface.caption) }
            debt.notes?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = surface.caption) }
        }
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

/** « Emma 200,00 € · Tom 200,00 € »: who still owes (or is owed) on one object. */
internal fun peopleSentence(debt: PeerDebt): String =
    debt.shares.filter { it.remainingCents > 0 }.joinToString(" · ") { "${it.contactName} ${formatCents(it.remainingCents)}" }
