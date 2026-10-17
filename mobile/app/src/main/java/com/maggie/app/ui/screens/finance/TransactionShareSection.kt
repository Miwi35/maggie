package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.formatCents
import com.maggie.app.ui.UiTags

/** What « Partage » does for the owner (MAG-451); every edit stays a draft until « Enregistrer ». */
class ShareSheetActions(
    val onEnable: (Boolean) -> Unit,
    val onMyShareChange: (Int) -> Unit,
    val onAddPerson: () -> Unit,
    val onRemovePerson: (contactId: String) -> Unit,
    val onAmountChange: (contactId: String, cents: Int) -> Unit,
    val onSubjectChange: (String) -> Unit,
    val onNotesChange: (String) -> Unit,
    val onSave: () -> Unit,
    /** « Voir tout ce qu'on te doit »: the contacts, until the créances have a screen of their own (MAG-455). */
    val onSeeAllOwed: (() -> Unit)? = null,
)

/** One euro, the step of « Ta part »'s − and +. */
private const val SHARE_STEP_CENTS = 100

/**
 * « Partage »: the part of the spend the owner keeps — only it counts in the envelopes — and
 * the people who owe the rest, split equally until an amount is written by hand.
 */
@Composable
internal fun ShareSection(
    state: TransferDetailState,
    actions: ShareSheetActions?,
    debtActions: DebtSheetActions? = null,
    peerActions: PeerTransferActions? = null,
) {
    val share = state.share
    val transaction = state.transaction
    // A transfer to or from a contact is first said what it is (MAG-453): its « À rembourser » supersedes
    // MAG-452's, and money sent already shared keeps its « Partage ».
    val peer = state.peer
    if (peer != null && peer.isShown && peerActions != null && !(peer.isOutgoing && transaction.isShared)) {
        PeerTransferQualifier(peer, peerActions, transaction.currency)
        return
    }
    // A received transfer is not shared: it is a recette, or it is given back (MAG-452).
    if (transaction.amountCents > 0 && state.debt != null && debtActions != null) {
        DebtSection(state, debtActions)
        return
    }
    when {
        share == null || actions == null -> if (transaction.amountCents >= 0) ShareHint("Seule une dépense se partage.")
        !share.canBeShared && !share.isShared -> ShareHint("Seule une dépense se partage.")
        else -> ShareEditorContent(share, actions, transaction.currency)
    }
}

@Composable
private fun ShareHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ShareEditorContent(share: ShareEditor, actions: ShareSheetActions, currency: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Dépense partagée", style = MaterialTheme.typography.bodyLarge)
            ShareHint("Seule ta part compte dans tes enveloppes")
        }
        Switch(
            checked = share.enabled,
            onCheckedChange = actions.onEnable,
            enabled = !share.isSaving,
            modifier = Modifier.testTag(UiTags.SHARE_SWITCH),
        )
    }

    if (share.enabled) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = { actions.onMyShareChange(share.myShareCents - SHARE_STEP_CENTS) },
                enabled = !share.isSaving && share.myShareCents > 0,
                modifier = Modifier.testTag(UiTags.SHARE_MINE_LESS),
            ) { Icon(Icons.Default.Remove, contentDescription = "Diminuer ta part") }
            CentsField(
                cents = share.myShareCents,
                onChange = actions.onMyShareChange,
                label = "Ta part",
                enabled = !share.isSaving,
                modifier = Modifier.weight(1f).testTag(UiTags.SHARE_MINE),
            )
            IconButton(
                onClick = { actions.onMyShareChange(share.myShareCents + SHARE_STEP_CENTS) },
                enabled = !share.isSaving && share.myShareCents < share.totalCents - 1,
                modifier = Modifier.testTag(UiTags.SHARE_MINE_MORE),
            ) { Icon(Icons.Default.Add, contentDescription = "Augmenter ta part") }
        }
        Text(
            "On te doit ${formatCents(share.owedCents, currency)}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(UiTags.SHARE_OWED),
        )

        Text("Qui te doit", style = MaterialTheme.typography.titleSmall)
        if (share.people.isEmpty()) {
            ShareHint("Personne pour l'instant.")
        }
        share.people.forEach { person ->
            Row(
                modifier = Modifier.fillMaxWidth().testTag(UiTags.SHARE_PERSON),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(person.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                CentsField(
                    cents = person.amountCents,
                    onChange = { actions.onAmountChange(person.contactId, it) },
                    label = "Montant",
                    enabled = !share.isSaving,
                    modifier = Modifier.width(140.dp).testTag(UiTags.SHARE_PERSON_AMOUNT),
                )
                IconButton(
                    onClick = { actions.onRemovePerson(person.contactId) },
                    enabled = !share.isSaving,
                    modifier = Modifier.testTag(UiTags.SHARE_PERSON_REMOVE),
                ) { Icon(Icons.Default.Close, contentDescription = "Retirer ${person.name}") }
            }
        }
        OutlinedButton(
            onClick = actions.onAddPerson,
            enabled = !share.isSaving,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.SHARE_ADD_PERSON),
        ) { Text("Ajouter une personne") }

        OutlinedTextField(
            value = share.subject,
            onValueChange = actions.onSubjectChange,
            label = { Text("Objet") },
            singleLine = true,
            enabled = !share.isSaving,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.SHARE_SUBJECT),
        )
        OutlinedTextField(
            value = share.notes,
            onValueChange = { if (it.length <= SHARE_NOTES_MAX_LENGTH) actions.onNotesChange(it) },
            label = { Text("Notes") },
            minLines = 2,
            enabled = !share.isSaving,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.SHARE_NOTES),
        )

        share.problem?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.SHARE_PROBLEM),
            )
        }
        Button(
            onClick = actions.onSave,
            enabled = share.canSave,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.SHARE_SAVE),
        ) { Text("Enregistrer") }
    }

    // The server's own sentence: « Tom already gave back … » says what to do next.
    share.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    share.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }

    actions.onSeeAllOwed?.let {
        TextButton(onClick = it, modifier = Modifier.testTag(UiTags.SHARE_ALL_OWED)) { Text("Voir tout ce qu'on te doit") }
    }
}

private const val SHARE_NOTES_MAX_LENGTH = 2000

/** « 200 » for 20 000 cents, « 33,34 » for 3 334: what the field shows of an amount. */
internal fun centsText(cents: Int): String =
    if (cents % 100 == 0) (cents / 100).toString() else "%d,%02d".format(cents / 100, kotlin.math.abs(cents % 100))

/**
 * An amount typed in euros. The text is the owner's while it reads as [cents]; when the amount
 * changes from elsewhere (a step, a new split, a bound) the field shows the new one.
 */
@Composable
internal fun CentsField(
    cents: Int,
    onChange: (Int) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(centsText(cents)) }
    LaunchedEffect(cents) {
        if (parseCents(text) != cents) text = centsText(cents)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            parseCents(typed)?.let(onChange)
        },
        label = { Text(label) },
        suffix = { Text("€") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
