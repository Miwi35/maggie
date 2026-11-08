package com.maggie.app.ui.screens.bilan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieTokens

/**
 * The slots of the « Journée » step (MAG-752): Maggie's line and replies from what is left of the day, and the cards.
 * Loaded once per opening of the bilan, with the choices it already holds.
 */
@Composable
fun rememberJourneeSlots(
    viewModel: BilanJourneeViewModel,
    session: BilanSession?,
    record: (String, String?) -> Unit,
): BilanSlots {
    val state by viewModel.uiState.collectAsState()
    val opened = session != null
    val choices = session?.taskChoices().orEmpty()
    LaunchedEffect(opened) { if (opened) viewModel.load(choices) }

    val content: @Composable ColumnScope.(BilanLayout) -> Unit = {
        BilanJourneeCards(state, onChoose = { id, choice -> viewModel.choose(id, choice, record) }, onRetry = { viewModel.load(choices) })
    }
    val pending = state.remaining.filter { it.choice == null }
    val say = when {
        state.loading || state.error -> null
        state.remaining.isEmpty() -> "Tout est fait aujourd’hui. Rien à ranger."
        pending.isEmpty() -> "C’est rangé. Passons à la suite ?"
        else -> "${state.done} sur ${state.total} aujourd’hui. Il reste " +
            pending.joinToString(", ") { it.title } + " : je les mets demain ?"
    }
    val asks = if (pending.isEmpty()) {
        emptyList()
    } else {
        listOf(
            BilanAsk("Oui, demain") { viewModel.allTomorrow(record) },
            BilanAsk("Je choisis") {},
        )
    }
    return BilanSlots(
        content = mapOf(BilanStep.JOURNEE to content),
        say = listOfNotNull(say?.let { BilanStep.JOURNEE to it }).toMap(),
        asks = mapOf(BilanStep.JOURNEE to asks),
    )
}

@Composable
internal fun BilanJourneeCards(
    state: JourneeUiState,
    onChoose: (String, TaskChoice) -> Unit,
    onRetry: () -> Unit,
) {
    if (state.error) {
        Card {
            Text("Impossible de lire votre journée.", fontSize = 15.sp)
            Text("Réessayer", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(role = Role.Button, onClick = onRetry))
        }
        return
    }
    if (state.loading) return
    Card {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("Tâches")
            Text(state.count, fontSize = 12.sp, color = caption(), modifier = Modifier.testTag(UiTags.BILAN_TASKS_COUNT))
        }
        state.remaining.forEach { task -> TaskRow(task, onChoose) }
        state.message?.let {
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp).testTag(UiTags.BILAN_TASKS_MESSAGE))
        }
    }
    Card {
        Label("Agenda · fait")
        state.agenda.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(item.time, fontSize = 14.sp, color = MaggieTokens.surfaceDark.textMuted, modifier = Modifier.width(48.dp))
                Text(item.title, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun TaskRow(task: JourneeTask, onChoose: (String, TaskChoice) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(UiTags.BILAN_TASK_PREFIX + task.id),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(task.title, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Chip("Demain", task.choice == TaskChoice.TOMORROW, UiTags.BILAN_TASK_PREFIX + task.id + UiTags.BILAN_TASK_TOMORROW_SUFFIX) {
            onChoose(task.id, TaskChoice.TOMORROW)
        }
        Chip("Plus tard", task.choice == TaskChoice.LATER, UiTags.BILAN_TASK_PREFIX + task.id + UiTags.BILAN_TASK_LATER_SUFFIX) {
            onChoose(task.id, TaskChoice.LATER)
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, if (selected) primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag(tag),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (selected) primary else MaggieTokens.surfaceDark.textMuted,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaggieTokens.surfaceDark.paper, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun Label(text: String) = Text(text, fontSize = 14.sp, color = MaggieTokens.surfaceDark.textMuted)

@Composable
private fun caption() = MaggieTokens.surfaceDark.caption
