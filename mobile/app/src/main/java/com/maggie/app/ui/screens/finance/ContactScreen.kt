package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ContactCounterparty
import com.maggie.app.data.model.ContactDebt
import com.maggie.app.data.model.CounterpartySuggestion
import com.maggie.app.data.model.debtDayLabel
import com.maggie.app.data.model.lastSeenLabel
import com.maggie.app.data.model.owesYouLabel
import com.maggie.app.data.model.transferCount
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.layout.ListDetailPane
import com.maggie.app.ui.uiTagRoot

/**
 * The contacts and a contact's page (MAG-450), reached from « Comptes ». On a phone the page
 * takes the screen and back returns to the list; with [showsDetailPane] (a tablet) it sits beside
 * the list. [openContactId] opens a contact on arrival (a link), then [onOpenConsumed] clears it.
 */
@Composable
fun ContactScreen(
    viewModel: ContactViewModel,
    onBack: () -> Unit,
    showsDetailPane: Boolean = false,
    openContactId: String? = null,
    onOpenConsumed: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(openContactId) {
        openContactId?.let {
            viewModel.open(it)
            onOpenConsumed()
        }
    }

    // The server's own sentence (« … est déjà associé à Anna. »): a generic one would hide why.
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (state.creating) {
        NewContactDialog(
            initialName = state.query.trim(),
            isSaving = state.isSaving,
            onConfirm = viewModel::create,
            onDismiss = viewModel::cancelCreation,
        )
    }

    val detail = state.detail
    if (!showsDetailPane) {
        if (detail != null) {
            BackHandler(onBack = viewModel::close)
            ContactPage(detail, state.isSaving, viewModel, snackbarHostState, onBack = viewModel::close)
        } else {
            ContactList(state, viewModel, snackbarHostState, onBack)
        }
        return
    }

    ListDetailPane(
        showsDetailPane = true,
        list = { ContactList(state, viewModel, snackbarHostState, onBack) },
        detail = detail?.let { { ContactPage(it, state.isSaving, viewModel, remember { SnackbarHostState() }, onBack = null) } },
        placeholder = "Touchez un contact pour voir sa fiche ici.",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactList(
    state: ContactUiState,
    viewModel: ContactViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val search = state.search

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::startCreation,
                modifier = Modifier.testTag(UiTags.CONTACT_ADD),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Nouveau contact")
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                !state.loaded && state.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                !state.loaded -> EmptyState(
                    title = "Chargement impossible",
                    description = "Les contacts n'ont pas pu être lus. Vérifiez votre connexion.",
                    actionLabel = "Réessayer",
                    onAction = viewModel::refresh,
                )

                state.isEmpty -> EmptyState(
                    title = "Aucun contact",
                    description = "Ajoutez les personnes avec qui vous échangez de l'argent, ou associez un virement depuis « À trier ».",
                    actionLabel = "Nouveau contact",
                    onAction = viewModel::startCreation,
                    modifier = Modifier.testTag(UiTags.CONTACT_EMPTY),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::search,
                            label = { Text("Rechercher un contact") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(UiTags.CONTACT_SEARCH),
                        )
                    }
                    search.creatable?.let { name ->
                        item {
                            Text(
                                "+ Créer « $name »",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.isSaving) { viewModel.create(name) }
                                    .padding(16.dp),
                            )
                            HorizontalDivider()
                        }
                    }
                    items(search.results, key = { it.id }) { contact ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.open(contact.id) }
                                .testTag(UiTags.CONTACT_ROW)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                contact.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (contact.id == state.detail?.contact?.id) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            contact.notes?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactPage(
    detail: ContactDetail,
    isSaving: Boolean,
    viewModel: ContactViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: (() -> Unit)?,
) {
    val contact = detail.contact
    var name by rememberSaveable(contact.id, contact.name) { mutableStateOf(contact.name) }
    var notes by rememberSaveable(contact.id, contact.notes) { mutableStateOf(contact.notes.orEmpty()) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val changed = name.trim() != contact.name || notes.trim() != contact.notes.orEmpty()

    if (confirmingDelete) {
        AlertDialog(
            modifier = Modifier.uiTagRoot(),
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Supprimer ${contact.name} ?") },
            text = { Text("Ses transactions restent, sans contact ; ses libellés ne lui seront plus associés.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.delete()
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Annuler") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(contact.name) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { confirmingDelete = true },
                        enabled = !isSaving,
                        modifier = Modifier.testTag(UiTags.CONTACT_DELETE),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Supprimer le contact")
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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.CONTACT_NAME),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.CONTACT_NOTES),
            )
            if (changed) {
                Button(
                    onClick = { viewModel.save(name, notes) },
                    enabled = name.isNotBlank() && !isSaving,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.CONTACT_SAVE),
                ) { Text("Enregistrer") }
            }

            HorizontalDivider()
            Text("En cours", style = MaterialTheme.typography.titleMedium)
            when {
                detail.isLoading -> CircularProgressIndicator()
                detail.openDebts.isEmpty() -> Text(
                    "${contact.name} ne te doit rien pour l'instant.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> detail.openDebts.forEach { DebtRow(it) }
            }

            HorizontalDivider()
            Text("Ses comptes", style = MaterialTheme.typography.titleMedium)
            when {
                detail.isLoading -> CircularProgressIndicator()
                detail.counterparties.isEmpty() -> Text(
                    "Aucun libellé pour l'instant. Associez un virement depuis « À trier », ou ajoutez une suggestion.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> detail.counterparties.forEach { counterparty ->
                    CounterpartyRow(counterparty, enabled = !isSaving, onRemove = { viewModel.unlink(counterparty.id) })
                }
            }

            if (!detail.isLoading && detail.suggestions.isNotEmpty()) {
                HorizontalDivider()
                Text("Peut-être aussi ${contact.name} :", style = MaterialTheme.typography.titleMedium)
                detail.suggestions.forEach { suggestion ->
                    SuggestionRow(suggestion, enabled = !isSaving, onAdd = { viewModel.addSuggestion(suggestion.label) })
                }
            }
        }
    }
}

/** One open créance: its object and day, and what is left of their share. */
@Composable
private fun DebtRow(debt: ContactDebt) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.CONTACT_DEBT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(debt.subject, style = MaterialTheme.typography.bodyLarge)
            debtDayLabel(debt.createdOn)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            owesYouLabel(debt.remainingCents),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(UiTags.CONTACT_DEBT_REMAINING),
        )
    }
}

@Composable
private fun CounterpartyRow(counterparty: ContactCounterparty, enabled: Boolean, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.CONTACT_COUNTERPARTY),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(counterparty.label, style = MaterialTheme.typography.bodyLarge)
            Usage(counterparty.transactionCount, counterparty.lastSeenOn)
        }
        TextButton(
            onClick = onRemove,
            enabled = enabled,
            modifier = Modifier.testTag(UiTags.CONTACT_COUNTERPARTY_REMOVE),
        ) { Text("Retirer") }
    }
}

@Composable
private fun SuggestionRow(suggestion: CounterpartySuggestion, enabled: Boolean, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(suggestion.label, style = MaterialTheme.typography.bodyLarge)
            Usage(suggestion.transactionCount, suggestion.lastSeenOn)
        }
        IconButton(
            onClick = onAdd,
            enabled = enabled,
            modifier = Modifier.testTag(UiTags.CONTACT_SUGGESTION_ADD),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Ajouter")
        }
    }
}

// The count on its own text, the date beside it: « 2 virements » is what a glance (and a journey) reads.
@Composable
private fun Usage(count: Int, lastSeenOn: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            transferCount(count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lastSeenLabel(lastSeenOn)?.let {
            Text(
                "· $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NewContactDialog(
    initialName: String,
    isSaving: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }

    AlertDialog(
        modifier = Modifier.uiTagRoot(),
        onDismissRequest = onDismiss,
        title = { Text("Nouveau contact") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.CONTACT_NEW_NAME),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank() && !isSaving,
                modifier = Modifier.testTag(UiTags.CONTACT_NEW_CONFIRM),
            ) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
