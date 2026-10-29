package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Contact
import com.maggie.app.data.model.PeerAllocationCandidate
import com.maggie.app.data.model.PeerAllocationClaim
import com.maggie.app.data.model.PeerAllocationDebt
import com.maggie.app.data.model.PeerAllocationRequest
import com.maggie.app.data.model.PeerAllocationRequestLine
import com.maggie.app.data.model.PeerKind
import com.maggie.app.data.model.TransactionAllocation
import com.maggie.app.data.model.formatCents
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.markdown.MarkdownEditor

/** One créance (or dette) « Il couvre » offers: checked or not, and how much of the transfer goes on it. */
data class PeerShareChoice(
    val candidate: PeerAllocationCandidate,
    val selected: Boolean = false,
    val amountCents: Int = 0,
)

/**
 * « Ce virement est » of a transfer to or from someone close (MAG-453), as a draft until « Enregistrer ».
 * Money received is « Une recette », « Un remboursement » of créances (the rest stays an income,
 * « Non affecté ») or « À rembourser » (a dette); money sent repays dettes, makes a new créance, or is
 * something else. Pure: the sheet, the « À trier » card and the monthly review drive it the same way.
 */
data class PeerTransferEditor(
    val transactionId: String,
    /** The transfer, in absolute cents. */
    val totalCents: Int,
    val direction: String = PeerKind.INCOMING,
    val canBeAllocated: Boolean = true,
    /** The answer the server holds; null while unanswered. */
    val savedKind: String? = null,
    /** The choice on screen. */
    val kind: String? = savedKind,
    /** The transaction's own contact, if any. */
    val contactId: String? = null,
    val contactName: String? = null,
    val choices: List<PeerShareChoice> = emptyList(),
    /** « À rembourser » / « Nouvelle créance »: towards whom. */
    val personId: String? = null,
    val personName: String? = null,
    /** « À rembourser »: the part to give back. A new créance is always the whole amount. */
    val debtAmountCents: Int = totalCents,
    val subject: String = "",
    val notes: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val isOutgoing: Boolean get() = direction == PeerKind.OUTGOING

    /** Whether « Ce virement est » is worth asking: a transfer that can be, to or from a contact, or already answered. */
    val isShown: Boolean get() = canBeAllocated && (contactId != null || savedKind != null)

    val allocatedCents: Int get() = choices.filter { it.selected }.sumOf { it.amountCents }

    /** What « Il couvre » leaves: for money received, it stays an income. */
    val unallocatedCents: Int get() = (totalCents - allocatedCents).coerceAtLeast(0)

    /** Why « Enregistrer » cannot be sent yet, null when it can (or when there is nothing to send). */
    val problem: String? get() = when (kind) {
        PeerKind.REPAYMENT -> {
            val selected = choices.filter { it.selected }
            val over = selected.firstOrNull { it.amountCents > it.candidate.availableCents }
            when {
                selected.isEmpty() ->
                    if (isOutgoing) "Coche au moins une dette qu'il rembourse." else "Coche au moins une créance qu'il couvre."
                selected.any { it.amountCents <= 0 } -> "Chaque montant doit être positif."
                over != null -> "« ${subjectOf(over.candidate)} » n'attend plus que ${formatCents(over.candidate.availableCents)}."
                allocatedCents > totalCents -> "Les montants font ${formatCents(allocatedCents)}, plus que le virement (${formatCents(totalCents)})."
                else -> null
            }
        }
        PeerKind.NEW_DEBT -> when {
            personId == null -> "Choisis la personne à qui tu dois cet argent."
            debtAmountCents <= 0 -> "Indique le montant à rendre."
            debtAmountCents > totalCents -> "Le montant à rendre ne peut pas dépasser le virement."
            else -> null
        }
        PeerKind.NEW_CLAIM -> if (personId == null) "Choisis qui te doit cet argent." else null
        else -> null
    }

    /** « Une recette » or « Autre » already held: nothing new to send. */
    private val hasNothingNew: Boolean get() = kind == savedKind && kind in setOf(PeerKind.INCOME, PeerKind.OTHER)

    val canSave: Boolean get() = kind != null && problem == null && !isSaving && !hasNothingNew

    /** What « Enregistrer » makes, in one sentence; null for an answer that makes nothing. */
    fun summary(currency: String = "EUR"): String? = when (kind) {
        PeerKind.NEW_DEBT -> personName?.let { "Crée une dette de ${formatCents(debtAmountCents, currency)} envers $it, dans « Tu dois »" }
        PeerKind.NEW_CLAIM -> personName?.let { "Crée une créance de ${formatCents(totalCents, currency)} sur $it, dans « On te doit »" }
        else -> null
    }

    fun withKind(next: String): PeerTransferEditor = copy(kind = next, error = null, message = null)

    /** Checking a créance puts on it what it waits for, within what the transfer has left; unchecking frees it. */
    fun toggle(shareId: String): PeerTransferEditor {
        val left = totalCents - allocatedCents
        return copy(
            choices = choices.map { choice ->
                when {
                    choice.candidate.shareId != shareId -> choice
                    choice.selected -> choice.copy(selected = false, amountCents = 0)
                    else -> choice.copy(selected = true, amountCents = minOf(choice.candidate.availableCents, left).coerceAtLeast(0))
                }
            },
            error = null,
            message = null,
        )
    }

    /** An amount typed or stepped on one créance: kept between 0 and what it may take; it checks the line. */
    fun withShareAmount(shareId: String, cents: Int): PeerTransferEditor = copy(
        choices = choices.map {
            if (it.candidate.shareId == shareId) it.copy(selected = true, amountCents = cents.coerceIn(0, it.candidate.availableCents)) else it
        },
        error = null,
        message = null,
    )

    fun withPerson(contact: Contact): PeerTransferEditor = copy(personId = contact.id, personName = contact.name, error = null, message = null)

    fun withDebtAmount(cents: Int): PeerTransferEditor = copy(debtAmountCents = cents.coerceIn(0, totalCents), error = null, message = null)

    fun withSubject(text: String): PeerTransferEditor = copy(subject = text)

    fun withNotes(text: String): PeerTransferEditor = copy(notes = text.take(PEER_NOTES_MAX_LENGTH))

    /** The body of `PUT …/allocation`; null while there is no choice. */
    fun toRequest(): PeerAllocationRequest? {
        val chosen = kind ?: return null
        val subjectOrNull = subject.trim().ifEmpty { null }
        val notesOrNull = notes.trim().ifEmpty { null }
        return when (chosen) {
            PeerKind.REPAYMENT -> PeerAllocationRequest(
                kind = chosen,
                allocations = choices.filter { it.selected }.map { PeerAllocationRequestLine(it.candidate.shareId, it.amountCents) },
            )
            PeerKind.NEW_DEBT -> PeerAllocationRequest(
                kind = chosen,
                debt = PeerAllocationDebt(personId, debtAmountCents, subjectOrNull, notesOrNull),
            )
            PeerKind.NEW_CLAIM -> PeerAllocationRequest(kind = chosen, claim = PeerAllocationClaim(personId, subjectOrNull, notesOrNull))
            else -> PeerAllocationRequest(kind = chosen)
        }
    }

    companion object {
        /** The editor as the server's answer leaves it: the créances it covers checked, the dette or créance it made filled in. */
        fun of(allocation: TransactionAllocation, label: String, message: String? = null): PeerTransferEditor {
            val total = kotlin.math.abs(allocation.amountCents)
            val made = allocation.peerDebt
            val share = made?.shares?.firstOrNull()
            return PeerTransferEditor(
                transactionId = allocation.transactionId,
                totalCents = total,
                direction = allocation.direction,
                canBeAllocated = allocation.canBeAllocated,
                savedKind = allocation.peerKind,
                kind = allocation.peerKind,
                contactId = allocation.contact?.id,
                contactName = allocation.contact?.name,
                // The contact's own créances first, as the server sends them; kept in that order.
                choices = allocation.candidates
                    .sortedByDescending { it.fromContact }
                    .map { PeerShareChoice(it, selected = it.allocatedCents > 0, amountCents = it.allocatedCents) },
                personId = share?.contactId ?: allocation.contact?.id,
                personName = share?.contactName ?: allocation.contact?.name,
                debtAmountCents = share?.amountCents?.takeIf { allocation.peerKind == PeerKind.NEW_DEBT } ?: total,
                subject = made?.`object` ?: label,
                notes = made?.notes.orEmpty(),
                message = message,
            )
        }

        private fun subjectOf(candidate: PeerAllocationCandidate): String = candidate.`object`.orEmpty().ifBlank { "Dépense partagée" }
    }
}

/** What the owner reads once « Ce virement est » is saved. */
fun peerSavedMessage(kind: String?): String = when (kind) {
    PeerKind.INCOME -> "Recette enregistrée"
    PeerKind.REPAYMENT -> "Remboursement enregistré"
    PeerKind.NEW_DEBT -> "Dette enregistrée"
    PeerKind.NEW_CLAIM -> "Créance enregistrée"
    else -> "Enregistré"
}

/** What « Ce virement est » does for the owner; every edit stays a draft until « Enregistrer ». */
class PeerTransferActions(
    val onKindChange: (String) -> Unit,
    val onToggleShare: (shareId: String) -> Unit,
    val onShareAmountChange: (shareId: String, cents: Int) -> Unit,
    /** « Envers qui » / « Qui te doit »: opens the full-screen contact picker. */
    val onPickPerson: () -> Unit,
    val onDebtAmountChange: (Int) -> Unit,
    val onSubjectChange: (String) -> Unit,
    val onNotesChange: (String) -> Unit,
    val onSave: () -> Unit,
)

/** One euro, the step of the amounts' − and +. */
private const val PEER_STEP_CENTS = 100

private const val PEER_NOTES_MAX_LENGTH = 2000

/**
 * « Ce virement est »: the choices by direction, then what the choice needs — the créances (or dettes) it
 * covers with « Non affecté », or the person and the amount. The person is chosen in the full-screen
 * contact picker, never an inline dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PeerTransferQualifier(editor: PeerTransferEditor, actions: PeerTransferActions, currency: String = "EUR") {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Ce virement est", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PeerKind.choicesFor(editor.direction).forEach { kind ->
                FilterChip(
                    selected = editor.kind == kind,
                    onClick = { if (editor.kind != kind) actions.onKindChange(kind) },
                    enabled = !editor.isSaving,
                    label = { Text(PeerKind.label(kind, editor.direction)) },
                    modifier = Modifier.testTag(UiTags.peerKind(kind)),
                )
            }
        }

        when (editor.kind) {
            PeerKind.REPAYMENT -> CoveredShares(editor, actions, currency)
            PeerKind.NEW_DEBT, PeerKind.NEW_CLAIM -> NewDebtFields(editor, actions, currency)
            PeerKind.INCOME -> Hint("Tout le virement compte dans tes recettes.")
            PeerKind.OTHER -> Hint("Une dépense comme une autre.")
        }

        editor.summary(currency)?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(UiTags.PEER_SUMMARY))
        }
        editor.problem?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.PEER_PROBLEM),
            )
        }
        if (editor.kind != null) {
            Button(
                onClick = actions.onSave,
                enabled = editor.canSave,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.PEER_SAVE),
            ) { Text(if (editor.savedKind != null) "Mettre à jour" else "Enregistrer") }
        }

        // The server's own sentence: « too much on a share » says what to do next.
        editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        editor.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** « Il couvre » (or « Elle rembourse »): one checkable line per open share, its amount, then « Non affecté ». */
@Composable
private fun CoveredShares(editor: PeerTransferEditor, actions: PeerTransferActions, currency: String) {
    Text(if (editor.isOutgoing) "Il rembourse" else "Il couvre", style = MaterialTheme.typography.titleSmall)
    if (editor.choices.isEmpty()) {
        Hint(if (editor.isOutgoing) "Aucune dette en cours." else "Aucune créance en cours.")
    }
    editor.choices.forEach { choice ->
        val candidate = choice.candidate
        Column(modifier = Modifier.fillMaxWidth().testTag(UiTags.PEER_CANDIDATE)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = choice.selected,
                        enabled = !editor.isSaving,
                        role = Role.Checkbox,
                        onValueChange = { actions.onToggleShare(candidate.shareId) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = choice.selected, onCheckedChange = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(candidate.`object`.orEmpty().ifBlank { "Dépense partagée" }, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${candidate.contactName} · reste ${formatCents(candidate.availableCents, currency)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (choice.selected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                ) {
                    IconButton(
                        onClick = { actions.onShareAmountChange(candidate.shareId, choice.amountCents - PEER_STEP_CENTS) },
                        enabled = !editor.isSaving && choice.amountCents > 0,
                    ) { Icon(Icons.Default.Remove, contentDescription = "Diminuer le montant") }
                    CentsField(
                        cents = choice.amountCents,
                        onChange = { actions.onShareAmountChange(candidate.shareId, it) },
                        label = "Montant",
                        enabled = !editor.isSaving,
                        modifier = Modifier.width(160.dp).testTag(UiTags.PEER_CANDIDATE_AMOUNT),
                    )
                    IconButton(
                        onClick = { actions.onShareAmountChange(candidate.shareId, choice.amountCents + PEER_STEP_CENTS) },
                        enabled = !editor.isSaving && choice.amountCents < candidate.availableCents,
                    ) { Icon(Icons.Default.Add, contentDescription = "Augmenter le montant") }
                }
            }
        }
    }
    Text(
        "Non affecté : ${formatCents(editor.unallocatedCents, currency)}",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag(UiTags.PEER_UNALLOCATED),
    )
    if (!editor.isOutgoing) Hint("Ce qui n'est pas affecté reste une recette.")
}

/** « À rembourser » (a dette of part of the transfer) or « Nouvelle créance » (the whole amount, owed to the owner). */
@Composable
private fun NewDebtFields(editor: PeerTransferEditor, actions: PeerTransferActions, currency: String) {
    val isDebt = editor.kind == PeerKind.NEW_DEBT
    OutlinedButton(
        onClick = actions.onPickPerson,
        enabled = !editor.isSaving,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.PEER_PERSON),
    ) {
        Text(
            when {
                editor.personName == null -> if (isDebt) "Envers qui ?" else "Qui te doit ?"
                isDebt -> "Envers : ${editor.personName}"
                else -> "Te doit : ${editor.personName}"
            },
        )
    }
    if (isDebt) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = { actions.onDebtAmountChange(editor.debtAmountCents - PEER_STEP_CENTS) },
                enabled = !editor.isSaving && editor.debtAmountCents > 0,
            ) { Icon(Icons.Default.Remove, contentDescription = "Diminuer le montant à rendre") }
            CentsField(
                cents = editor.debtAmountCents,
                onChange = actions.onDebtAmountChange,
                label = "Montant à rendre",
                enabled = !editor.isSaving,
                modifier = Modifier.weight(1f).testTag(UiTags.PEER_AMOUNT),
            )
            IconButton(
                onClick = { actions.onDebtAmountChange(editor.debtAmountCents + PEER_STEP_CENTS) },
                enabled = !editor.isSaving && editor.debtAmountCents < editor.totalCents,
            ) { Icon(Icons.Default.Add, contentDescription = "Augmenter le montant à rendre") }
        }
    } else {
        Hint("Tout le virement, ${formatCents(editor.totalCents, currency)}, est à te rendre.")
    }
    OutlinedTextField(
        value = editor.subject,
        onValueChange = actions.onSubjectChange,
        label = { Text("Objet") },
        singleLine = true,
        enabled = !editor.isSaving,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.PEER_SUBJECT),
    )
    MarkdownEditor(
        value = editor.notes,
        onValueChange = { if (it.length <= PEER_NOTES_MAX_LENGTH) actions.onNotesChange(it) },
        label = "Notes",
        enabled = !editor.isSaving,
        modifier = Modifier.testTag(UiTags.PEER_NOTES),
    )
}
