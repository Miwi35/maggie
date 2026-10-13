package com.maggie.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.chat.AgentState
import com.maggie.app.ui.screens.chat.ApprovalItem
import com.maggie.app.ui.screens.chat.ToolCallItem
import com.maggie.app.ui.screens.chat.ToolCallStatus
import com.maggie.app.ui.screens.chat.toolActivityLabel

/** The two faces of the chatbox: the conversation, and what Maggie is doing and waiting for (MAG-403). */
enum class ChatTab { CHAT, MIND }

/**
 * « Chat | Mind » at the top of the chatbox, as in the admin. The only door to the Mind tab —
 * no icon in the chat strip nor in the app bar. [awaitingCount] is the number of held actions
 * waiting for an answer; the tab says it in its label too, which is what a screen reader
 * and a journey read.
 */
@Composable
fun ChatTabs(
    selected: ChatTab,
    onSelect: (ChatTab) -> Unit,
    awaitingCount: Int,
    modifier: Modifier = Modifier,
) {
    // A plain Row, not a TabRow: its subcomposition makes the list under it scroll to the
    // last message in the middle of a measure pass, which the framework refuses.
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TabCell(
                selected = selected == ChatTab.CHAT,
                onClick = { onSelect(ChatTab.CHAT) },
                tag = UiTags.CHAT_TAB,
                modifier = Modifier.weight(1f),
            ) { Text("Chat") }
            TabCell(
                selected = selected == ChatTab.MIND,
                onClick = { onSelect(ChatTab.MIND) },
                tag = UiTags.MIND_TAB,
                description = if (awaitingCount > 0) "Mind, $awaitingCount en attente de validation" else "Mind",
                modifier = Modifier.weight(1f),
            ) {
                Text("Mind")
                if (awaitingCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Badge(
                        modifier = Modifier.testTag(UiTags.MIND_BADGE),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(awaitingCount.toString())
                    }
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun TabCell(
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    label: @Composable RowScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Tab(
            selected = selected,
            onClick = onClick,
            modifier = Modifier
                .testTag(tag)
                .semantics { description?.let { contentDescription = it } },
        ) {
            Row(
                modifier = Modifier.padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = label,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}

/**
 * What Maggie is doing — her state, the tool calls of the session in plain words — and the
 * actions she holds until the owner answers. A tool is named by [toolActivityLabel] and a
 * held action by its summary; the technical names and the ids stay behind (MAG-403).
 */
@Composable
fun MindContent(
    agentState: AgentState,
    toolCalls: List<ToolCallItem>,
    approvals: List<ApprovalItem>,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onDismissApproval: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(UiTags.MIND_CONTENT)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MaggieState(agentState)

        SectionTitle("Activité")
        if (toolCalls.isEmpty()) {
            Text(
                text = "Aucune activité",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).testTag(UiTags.MIND_ACTIVITY),
            )
        } else {
            Column(modifier = Modifier.testTag(UiTags.MIND_ACTIVITY)) {
                toolCalls.forEach { ToolCallRow(it) }
            }
        }

        SectionTitle("Validations en attente")
        if (approvals.isEmpty()) {
            Text(
                text = "Aucune validation en attente",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                approvals.forEach { item ->
                    ApprovalCard(
                        item = item,
                        onApprove = { onApprove(item.approval.id) },
                        onDeny = { onDeny(item.approval.id) },
                        onDismiss = { onDismissApproval(item.approval.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun MaggieState(state: AgentState) {
    val (label, color) = when (state) {
        AgentState.IDLE -> "Maggie est au repos" to MaterialTheme.colorScheme.outline
        AgentState.THINKING -> "Maggie réfléchit…" to MaterialTheme.colorScheme.primary
        AgentState.ACTING -> "Maggie agit…" to MaterialTheme.colorScheme.tertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag(UiTags.MIND_STATE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ToolCallRow(call: ToolCallItem) {
    val (status, icon) = when (call.status) {
        ToolCallStatus.RUNNING -> "En cours" to null
        ToolCallStatus.SUCCESS -> "Réussi" to Icons.Default.Check
        ToolCallStatus.ERROR -> "Échoué" to Icons.Default.ErrorOutline
        ToolCallStatus.PENDING_APPROVAL -> "En attente de validation" to Icons.Default.HourglassEmpty
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag(UiTags.MIND_TOOL_CALL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon == null) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = when (call.status) {
                    ToolCallStatus.ERROR -> MaterialTheme.colorScheme.error
                    ToolCallStatus.PENDING_APPROVAL -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = toolActivityLabel(call.toolName),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
