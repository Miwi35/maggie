package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags

/** What « Ce virement est » does for the owner (MAG-452); every edit stays a draft until « Enregistrer ». */
class DebtSheetActions(
    /** true = « À rembourser », false = « Une recette ». */
    val onEnable: (Boolean) -> Unit,
    /** « Envers qui »: opens the full-screen contact picker. */
    val onPickPerson: () -> Unit,
    val onAmountChange: (Int) -> Unit,
    val onSubjectChange: (String) -> Unit,
    val onNotesChange: (String) -> Unit,
    val onSave: () -> Unit,
)

/** One euro, the step of the amount's − and +. */
private const val DEBT_STEP_CENTS = 100

private const val DEBT_NOTES_MAX_LENGTH = 2000

/**
 * « Ce virement est »: a recette, or to be given back — a dette towards one person for up to the
 * amount of the transfer, which then no longer counts in the income. The person is chosen in the
 * full-screen contact picker, never an inline dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DebtSection(state: TransferDetailState, actions: DebtSheetActions) {
    val debt = state.debt ?: return
    val currency = state.transaction.currency
    if (!debt.canBeDebt && !debt.isRecorded) {
        Text("Ce virement ne peut pas être à rembourser.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    Text("Ce virement est", style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(
            selected = !debt.enabled,
            onClick = { if (debt.enabled) actions.onEnable(false) },
            enabled = !debt.isSaving,
            label = { Text("Une recette") },
            modifier = Modifier.testTag(UiTags.TRANSACTION_DEBT_RECEIPT),
        )
        FilterChip(
            selected = debt.enabled,
            onClick = { if (!debt.enabled) actions.onEnable(true) },
            enabled = !debt.isSaving,
            label = { Text("À rembourser") },
            modifier = Modifier.testTag(UiTags.TRANSACTION_DEBT_TOGGLE),
        )
    }

    if (debt.enabled) {
        OutlinedButton(
            onClick = actions.onPickPerson,
            enabled = !debt.isSaving,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.TRANSACTION_DEBT_PERSON),
        ) { Text(debt.contactName?.let { "Envers : $it" } ?: "Envers qui ?") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = { actions.onAmountChange(debt.amountCents - DEBT_STEP_CENTS) },
                enabled = !debt.isSaving && debt.amountCents > 0,
                modifier = Modifier.testTag(UiTags.TRANSACTION_DEBT_AMOUNT_LESS),
            ) { Icon(Icons.Default.Remove, contentDescription = "Diminuer le montant à rendre") }
            CentsField(
                cents = debt.amountCents,
                onChange = actions.onAmountChange,
                label = "Montant à rendre",
                enabled = !debt.isSaving,
                modifier = Modifier.weight(1f).testTag(UiTags.TRANSACTION_DEBT_AMOUNT),
            )
            IconButton(
                onClick = { actions.onAmountChange(debt.amountCents + DEBT_STEP_CENTS) },
                enabled = !debt.isSaving && debt.amountCents < debt.totalCents,
                modifier = Modifier.testTag(UiTags.TRANSACTION_DEBT_AMOUNT_MORE),
            ) { Icon(Icons.Default.Add, contentDescription = "Augmenter le montant à rendre") }
        }

        OutlinedTextField(
            value = debt.subject,
            onValueChange = actions.onSubjectChange,
            label = { Text("Objet") },
            singleLine = true,
            enabled = !debt.isSaving,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.TRANSACTION_DEBT_SUBJECT),
        )
        OutlinedTextField(
            value = debt.notes,
            onValueChange = { if (it.length <= DEBT_NOTES_MAX_LENGTH) actions.onNotesChange(it) },
            label = { Text("Notes") },
            minLines = 2,
            enabled = !debt.isSaving,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.TRANSACTION_DEBT_NOTES),
        )

        debt.summary(currency)?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(UiTags.TRANSACTION_DEBT_SUMMARY))
        }
        debt.problem?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.TRANSACTION_DEBT_PROBLEM),
            )
        }
        Button(
            onClick = actions.onSave,
            enabled = debt.canSave,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.TRANSACTION_DEBT_SAVE),
        ) { Text(if (debt.isRecorded) "Mettre à jour" else "Enregistrer") }
    }

    // The server's own sentence: « Tom already gave back … » says what to do next.
    debt.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    debt.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
}
