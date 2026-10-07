package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.chat.ApprovalDecision
import com.maggie.app.ui.screens.chat.ApprovalItem
import com.maggie.app.util.ChatDateFormatter
import kotlinx.serialization.json.JsonPrimitive

/**
 * An action Maggie wants to take and holds until the user answers it.
 *
 * Pending, it offers Autoriser and Refuser; once an answer is on its way both are
 * locked and the chosen one shows its progress. A failed action has nothing left to
 * answer and only offers to be closed.
 */
@Composable
fun ApprovalCard(
    item: ApprovalItem,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val approval = item.approval
    val failed = approval.status == PendingApproval.STATUS_FAILED
    val inFlight = item.decision != null
    var detailsOpen by rememberSaveable(approval.id) { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTags.approvalCard(approval.id)),
        colors = CardDefaults.cardColors(
            containerColor = if (failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (failed) "Action échouée" else "Maggie demande ton accord",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(text = approvalTitle(approval), style = MaterialTheme.typography.titleSmall)
            TextButton(
                onClick = { detailsOpen = !detailsOpen },
                modifier = Modifier.testTag(UiTags.approvalDetails(approval.id)),
            ) {
                Text(if (detailsOpen) "Masquer les détails" else "Détails")
            }
            if (detailsOpen) {
                approvalArguments(approval).forEach { line ->
                    Text(text = line, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (failed) {
                approval.result?.takeIf { it.isNotBlank() }?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(UiTags.approvalDismiss(approval.id)),
                ) {
                    Text("Fermer")
                }
            } else {
                approvalExpiry(approval)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.error) {
                    Text(
                        text = "Réponse non transmise, réessaie.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDeny,
                        enabled = !inFlight,
                        modifier = Modifier.testTag(UiTags.approvalDeny(approval.id)),
                    ) {
                        if (item.decision == ApprovalDecision.DENY) Spinner()
                        Text("Refuser")
                    }
                    Button(
                        onClick = onApprove,
                        enabled = !inFlight,
                        modifier = Modifier.testTag(UiTags.approvalAllow(approval.id)),
                    ) {
                        if (item.decision == ApprovalDecision.APPROVE) Spinner()
                        Text("Autoriser")
                    }
                }
            }
        }
    }
}

@Composable
private fun Spinner() {
    CircularProgressIndicator(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(16.dp),
        strokeWidth = 2.dp,
    )
}

/** The agent's sentence; an approval held before it existed has none, and a tool name says nothing to the user. */
internal fun approvalTitle(approval: PendingApproval): String =
    approval.summary?.takeIf { it.isNotBlank() } ?: "Action en attente de validation"

private val SPOKEN_VERBS = mapOf(
    "delete" to "Je supprime",
    "create" to "Je crée",
    "add" to "J'ajoute",
    "update" to "Je modifie",
)

private val SPOKEN_NOUNS = mapOf(
    "event" to "l'événement",
    "task" to "la tâche",
    "recipe" to "la recette",
    "agenda" to "l'agenda",
    "meal" to "le repas",
    "transaction" to "la transaction",
    "skill" to "la compétence",
)

private val SPOKEN_LABEL_KEYS = listOf("title", "name", "label", "summary")

/**
 * What Maggie says out loud for a held action: « Je supprime l'événement Test validation ? ».
 * [resolvedLabel] is what the arguments cannot say (the title behind an id). A tool the
 * vocabulary above does not know is named as it is, so the question is always asked,
 * never swallowed.
 */
internal fun approvalQuestion(approval: PendingApproval, resolvedLabel: String? = null): String {
    val words = approval.toolName.split('_').filter { it.isNotBlank() }
    val verb = SPOKEN_VERBS[words.firstOrNull()]
    val noun = words.drop(1).joinToString("_").let { SPOKEN_NOUNS[it] }
    val label = resolvedLabel?.takeIf { it.isNotBlank() } ?: SPOKEN_LABEL_KEYS.firstNotNullOfOrNull { key ->
        (approval.arguments[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }
    return if (verb != null && noun != null) {
        listOfNotNull(verb, noun, label).joinToString(" ") + " ?"
    } else {
        val named = approval.summary?.takeIf { it.isNotBlank() }
            ?: approval.toolName.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }
        "Maggie demande ton accord pour $named" + (label?.let { " $it" } ?: "") + ". Tu autorises ?"
    }
}

internal fun approvalArguments(approval: PendingApproval): List<String> =
    approval.arguments.map { (key, value) ->
        val shown = if (value is JsonPrimitive) value.content else value.toString()
        "$key : $shown"
    }

private fun approvalExpiry(approval: PendingApproval): String? {
    val instant = approval.expiresAt?.let(ChatDateFormatter::parseIso) ?: return null
    return "Expire ${ChatDateFormatter.formatDayAndTime(instant)}"
}
