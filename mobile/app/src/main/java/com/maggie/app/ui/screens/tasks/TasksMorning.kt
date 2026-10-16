package com.maggie.app.ui.screens.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.DayProposal
import com.maggie.app.data.model.ProposalReason
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.shared.minutesLabel

/** « ≈ 55 min · 2 h 30 libres »: the proposed time against the day's free time. */
internal fun morningTimeOf(proposal: DayProposal): String =
    "≈ ${minutesLabel(proposal.totalMinutes)} · ${minutesLabel(proposal.freeMinutes)} libres"

/**
 * « Ta journée » (MAG-498): the morning's five, numbered, each with why and its minutes, the time
 * they take against the free time, the next ones of the reserve in a line, and « C'est parti ».
 * A row's swap takes the next task of the reserve; nothing is written before « C'est parti ».
 */
@Composable
internal fun MorningContent(morning: MorningState, viewModel: TasksViewModel) {
    val proposal = morning.proposal
    if (proposal == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(UiTags.TASKS_MORNING)) {
        item(key = "title") {
            Text(
                text = "Ta journée",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        if (proposal.slots.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "Rien à proposer aujourd'hui.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        } else {
            item(key = "time") { MorningTime(proposal) }
            itemsIndexed(proposal.slots, key = { _, slot -> slot.task.id }) { index, slot ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.tasksMorningSlot(slot.position))
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "${slot.position}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = slot.task.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        SuggestionChip(onClick = {}, label = { Text(ProposalReason.label(slot.reason)) }, enabled = false)
                    }
                    Text(
                        text = minutesLabel(slot.minutes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (proposal.reserveRanked.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.swapMorning(index) },
                            enabled = !morning.isConfirming,
                            modifier = Modifier.testTag(UiTags.tasksMorningSwap(slot.position)),
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "Échanger « ${slot.task.title} »")
                        }
                    }
                }
            }
        }
        if (proposal.reserveRanked.isNotEmpty()) {
            item(key = "reserve") {
                Text(
                    text = "Ensuite : " + proposal.reserveRanked.take(3).joinToString(" · ") { it.task.title },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag(UiTags.TASKS_MORNING_RESERVE),
                )
            }
        }
        item(key = "actions") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = viewModel::confirmMorning,
                    enabled = proposal.slots.isNotEmpty() && !morning.isConfirming,
                    modifier = Modifier.testTag(UiTags.TASKS_MORNING_GO),
                ) {
                    Text("C'est parti")
                }
                TextButton(
                    onClick = viewModel::dismissMorning,
                    enabled = !morning.isConfirming,
                    modifier = Modifier.testTag(UiTags.TASKS_MORNING_LATER),
                ) {
                    Text("Plus tard")
                }
            }
        }
    }
}

/** The five's minutes against the free time as a bar, and in words: a count has its picture. */
@Composable
private fun MorningTime(proposal: DayProposal) {
    val label = morningTimeOf(proposal)
    val progress = if (proposal.freeMinutes > 0) (proposal.totalMinutes.toFloat() / proposal.freeMinutes).coerceIn(0f, 1f) else 1f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(UiTags.TASKS_MORNING_TIME)
            .clearAndSetSemantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}
