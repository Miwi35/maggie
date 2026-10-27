package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TransactionSheetSection
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.incidentRegularisationLabel
import com.maggie.app.data.model.transactionStatusLabel
import com.maggie.app.data.model.transferBadgeLabel
import com.maggie.app.data.model.transferLegSummary
import com.maggie.app.data.model.visibleSheetSections
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.formatBookedDay

/**
 * What the sheet does for the owner; each gesture acts at once, there is no « Enregistrer » for the
 * whole sheet — only « Partage » keeps a draft, the part and the people being one decision.
 */
class TransactionSheetActions(
    val onBack: () -> Unit,
    val onSearchCounterpart: () -> Unit,
    val onRelease: () -> Unit,
    // Category and qualification go together: the API refuses one without the other.
    val onQualify: ((categoryId: String?, qualification: Qualification) -> Unit)? = null,
    val onCreateRule: (() -> Unit)? = null,
    val onMakeRecurring: (() -> Unit)? = null,
    val onDetach: () -> Unit = {},
    val onSaveNote: (String) -> Unit = {},
    val onPickOneOffEnvelope: (() -> Unit)? = null,
    // « Partage » (MAG-451): its edits are a draft until its own « Enregistrer ».
    val share: ShareSheetActions? = null,
    // « Ce virement est » of a received transfer (MAG-452): a draft until its own « Enregistrer ».
    val debt: DebtSheetActions? = null,
    // « Ce virement est » of a transfer to or from a contact (MAG-453): a draft until its own « Enregistrer ».
    val peer: PeerTransferActions? = null,
)

/**
 * The one sheet of a transaction, in six ordered sections: Nature, Classement, Récurrence,
 * Partage, Jugement, Note. The nature decides the rest: an internal transfer, an advance, a
 * repayment, a top-up or an incident keeps only Nature and Note. Marking an internal transfer
 * opens a full-screen search for the counterpart, never an inline dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    state: TransferDetailState,
    actions: TransactionSheetActions,
    categories: List<Category> = emptyList(),
    provenance: String? = null,
    seriesName: String? = null,
    oneOffEnvelopeName: String? = null,
) {
    BackHandler(onBack = actions.onBack)
    val transaction = state.transaction

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(transaction.label, style = MaterialTheme.typography.titleLarge)
            Text(
                text = formatCents(transaction.amountCents, transaction.currency),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = listOfNotNull(
                    formatBookedDay(transaction.bookedAt),
                    transactionStatusLabel(transaction.status, transaction.amountCents),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.isLoading && state.info == null) {
                CircularProgressIndicator()
            }

            visibleSheetSections(transaction.transferKind).forEach { section ->
                HorizontalDivider()
                SheetSection(section) {
                    when (section) {
                        TransactionSheetSection.Nature -> NatureSection(state, actions)
                        TransactionSheetSection.Classement -> ClassementSection(state, actions, categories, provenance, oneOffEnvelopeName)
                        TransactionSheetSection.Recurrence -> RecurrenceSection(state, actions, seriesName)
                        TransactionSheetSection.Partage -> ShareSection(state, actions.share, actions.debt, actions.peer)
                        TransactionSheetSection.Jugement -> EmptySlot("Pas encore disponible")
                        TransactionSheetSection.Note -> NoteSection(state, actions)
                    }
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** A named slot of the sheet: later lots plug their content into it by its tag. */
@Composable
private fun SheetSection(section: TransactionSheetSection, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.sheetSection(section.key)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(section.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun EmptySlot(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SourceText(transaction: Transaction) {
    Text(
        text = if (transaction.transferSource == "manual") "Marqué à la main" else "Détecté automatiquement",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NatureChip(label: String, tag: String) {
    AssistChip(onClick = {}, label = { Text(label) }, modifier = Modifier.testTag(tag))
}

/** What the line is: ordinary, an internal move, or an incident; the only section a non-ordinary line keeps with the note. */
@Composable
private fun NatureSection(state: TransferDetailState, actions: TransactionSheetActions) {
    val transaction = state.transaction
    if (transaction.isRejected) {
        val label = transferBadgeLabel(transaction.transferKind, transaction.amountCents, state.info?.counterpart?.label)
        label?.let { NatureChip(it, UiTags.REJECTION_BADGE) }
        if (state.info != null) {
            Text(incidentRegularisationLabel(state.info.counterpart), style = MaterialTheme.typography.bodyMedium)
        }
        SourceText(transaction)
        state.info?.counterpart?.let {
            Text(
                text = "${if (transaction.amountCents < 0) "Recrédité par" else "Paiement rejeté"} : ${transferLegSummary(it)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedButton(
            onClick = actions.onRelease,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.REJECTION_RELEASE),
        ) { Text("Ce n'est pas un rejet") }
    } else if (transaction.isInternalTransfer) {
        transferBadgeLabel(transaction.transferKind, transaction.amountCents)?.let { NatureChip(it, UiTags.TRANSFER_BADGE) }
        SourceText(transaction)
        if (state.info != null) {
            Text(
                text = state.info.counterpart?.let { "Contrepartie : ${transferLegSummary(it)}" }
                    ?: "Sans contrepartie : l'autre compte n'est pas suivi",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedButton(
            onClick = actions.onRelease,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.TRANSFER_TOGGLE),
        ) { Text("Ce n'est pas un virement interne") }
    } else {
        Text("Ligne ordinaire", style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = actions.onSearchCounterpart,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.TRANSFER_TOGGLE),
        ) { Text("C'est un virement interne") }
    }
}

@Composable
private fun ClassementSection(
    state: TransferDetailState,
    actions: TransactionSheetActions,
    categories: List<Category>,
    provenance: String?,
    oneOffEnvelopeName: String?,
) {
    val transaction = state.transaction
    Text(
        text = "État : ${transactionStatusLabel(transaction.status, transaction.amountCents)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    actions.onQualify?.let { qualify ->
        QualificationSection(
            categories = categories,
            categoryId = transaction.categoryId,
            qualification = transaction.qualification,
            onCategoryChange = { qualify(it, transaction.qualification) },
            onQualificationChange = { qualify(transaction.categoryId, it) },
            categoryTag = UiTags.TRANSACTION_CATEGORY,
            searchTag = UiTags.TRANSACTION_CATEGORY_SEARCH,
            enabled = !state.isSaving,
            canClearCategory = true,
        )
        provenance?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.QUALIFICATION_PROVENANCE),
            )
        }
    }
    // Only a spend is budgeted: an income has nothing to attribute, unless it already was.
    if (transaction.amountCents < 0 || transaction.oneOffEnvelope != null) {
        actions.onPickOneOffEnvelope?.let {
            OneOffEnvelopeField(
                name = oneOffEnvelopeName ?: transaction.oneOffEnvelope?.let { "Enveloppe ponctuelle" },
                enabled = !state.isSaving,
                onClick = it,
            )
        }
    }
    actions.onCreateRule?.let {
        OutlinedButton(
            onClick = it,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.RULE_DRAFT_OPEN),
        ) { Text("Créer un filtre") }
    }
}

@Composable
private fun RecurrenceSection(state: TransferDetailState, actions: TransactionSheetActions, seriesName: String?) {
    val transaction = state.transaction
    if (transaction.isAttachedToRecurring) {
        Text(
            text = "Série : ${seriesName ?: "opération récurrente"}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(UiTags.RECURRING_ATTACHED),
        )
        transaction.recurringOccurrenceOn?.let {
            Text("Échéance : ${formatBookedDay(it)}", style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(
            onClick = actions.onDetach,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.RECURRING_DETACH),
        ) { Text("Détacher de la série") }
    } else {
        Text("Aucune série", style = MaterialTheme.typography.bodyMedium)
        actions.onMakeRecurring?.let {
            OutlinedButton(
                onClick = it,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.RECURRING_FROM_TRANSACTION),
            ) { Text("En faire une opération récurrente") }
        }
    }
}

@Composable
private fun NoteSection(state: TransferDetailState, actions: TransactionSheetActions) {
    val saved = state.transaction.note.orEmpty()
    var text by rememberSaveable(state.transaction.id, saved) { mutableStateOf(saved) }
    OutlinedTextField(
        value = text,
        onValueChange = { if (it.length <= NOTE_MAX_LENGTH) text = it },
        label = { Text("Note") },
        modifier = Modifier.fillMaxWidth().testTag(UiTags.TRANSACTION_NOTE),
        minLines = 3,
        maxLines = 8,
        supportingText = { Text("${text.length} / $NOTE_MAX_LENGTH") },
    )
    Button(
        onClick = { actions.onSaveNote(text) },
        enabled = !state.isSaving && text != saved,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.TRANSACTION_NOTE_SAVE),
    ) { Text("Enregistrer la note") }
}

private const val NOTE_MAX_LENGTH = 2000

/**
 * The event the spend is attributed to (MAG-414): it keeps its category but leaves the
 * monthly and annual envelopes. A tap opens the full-screen choice, never a dropdown.
 */
@Composable
private fun OneOffEnvelopeField(name: String?, enabled: Boolean, onClick: () -> Unit) {
    Box {
        OutlinedTextField(
            value = name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Enveloppe ponctuelle") },
            placeholder = { Text("Aucune") },
            supportingText = { Text("Hellfest, vacances… La dépense sort alors des enveloppes du mois.") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .testTag(UiTags.TRANSACTION_ONE_OFF_ENVELOPE)
                .semantics { contentDescription = "Enveloppe ponctuelle, ${name ?: "aucune"}" }
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        )
    }
}

/** Full-screen choice of the one-off envelope to attribute the open spend to, plus « Aucune ». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneOffEnvelopeSearchScreen(
    envelopes: List<Envelope>,
    selectedId: String?,
    isLoading: Boolean,
    error: String?,
    onLoad: () -> Unit,
    onPick: (Envelope?) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { onLoad() }

    val shown = remember(envelopes, query) {
        envelopes.filter { query.isBlank() || it.name.orEmpty().contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Attribuer à une enveloppe ponctuelle") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Rechercher un événement") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(UiTags.TRANSACTION_ONE_OFF_ENVELOPE_SEARCH),
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (selectedId != null) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().clickable { onPick(null) }.padding(16.dp)) {
                            Text("Aucune", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Remettre la dépense dans les enveloppes de sa catégorie",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
                if (!isLoading && shown.isEmpty()) {
                    item {
                        Text(
                            if (envelopes.isEmpty()) {
                                "Aucune enveloppe ponctuelle : créez-en une (Hellfest, vacances…) dans l'admin."
                            } else {
                                "Aucun événement ne correspond."
                            },
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(shown, key = { it.id }) { envelope ->
                    Column(modifier = Modifier.fillMaxWidth().clickable { onPick(envelope) }.padding(16.dp)) {
                        Text(
                            text = envelope.name.orEmpty() + if (envelope.id == selectedId) " ✓" else "",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = formatCents(envelope.amountCents, envelope.currency),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Full-screen search among the lines a transfer can be paired with, plus « no counterpart ». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CounterpartSearchScreen(
    state: TransferDetailState,
    onLoad: () -> Unit,
    onPick: (TransferLeg?) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { onLoad() }

    val shown = remember(state.candidates, query) {
        state.candidates.filter {
            query.isBlank() || transferLegSummary(it).contains(query.trim(), ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choisir la contrepartie") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Rechercher un libellé, un compte, une date") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(UiTags.TRANSFER_SEARCH),
            )
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().clickable { onPick(null) }.padding(16.dp)) {
                        Text("Aucune contrepartie", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "L'autre compte n'est pas suivi dans Maggie",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
                if (!state.isLoading && shown.isEmpty()) {
                    item {
                        Text(
                            "Aucune ligne proche à apparier.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(shown, key = { it.id }) { leg ->
                    Column(modifier = Modifier.fillMaxWidth().clickable { onPick(leg) }.padding(16.dp)) {
                        Text(leg.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = listOfNotNull(
                                leg.accountName,
                                formatBookedDay(leg.bookedAt),
                                formatCents(leg.amountCents, leg.currency),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
