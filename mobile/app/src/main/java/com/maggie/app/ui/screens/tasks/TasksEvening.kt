package com.maggie.app.ui.screens.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.DayReview
import com.maggie.app.data.model.DayTask
import com.maggie.app.ui.UiTags

/** The key of [outcome] in the test tags: `tomorrow`, `later`, `aside`. */
internal fun outcomeKeyOf(outcome: EveningOutcome): String = outcome.name.lowercase()

/**
 * « Le soir » (MAG-500): « 3 sur 5 » with the day's ring, what is done first, then each task left
 * with its way out in one gesture — « Demain », « Plus tard », « Mettre de côté » (and « Annuler »
 * once set aside) — then « Qu'est-ce qui t'a aidé ? », « Bonne nuit » and « Plus tard ».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EveningContent(evening: EveningState, viewModel: TasksViewModel) {
    val done = evening.done
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(UiTags.TASKS_EVENING)) {
        item(key = "ring") { DayRing(done = done.size, total = evening.day.total) }
        if (done.isNotEmpty()) {
            item(key = "done") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(UiTags.TASKS_EVENING_DONE),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    done.forEach { task ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(task.title, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        items(evening.left, key = { "left_" + it.id }) { task ->
            LeftRow(task = task, evening = evening, viewModel = viewModel)
        }
        item(key = "helped") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Qu'est-ce qui t'a aidé ?", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayReview.HELPED.forEach { (value, label) ->
                        val selected = value in evening.helped
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.toggleHelped(value) },
                            label = { Text(label) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else {
                                null
                            },
                            modifier = Modifier.testTag(UiTags.tasksEveningHelped(value)),
                        )
                    }
                }
            }
        }
        item(key = "actions") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = viewModel::goodNight,
                    enabled = !evening.isSaving,
                    modifier = Modifier.testTag(UiTags.TASKS_EVENING_GOOD_NIGHT),
                ) {
                    Text("Bonne nuit")
                }
                TextButton(
                    onClick = viewModel::dismissEvening,
                    enabled = !evening.isSaving,
                    modifier = Modifier.testTag(UiTags.TASKS_EVENING_LATER),
                ) {
                    Text("Plus tard")
                }
            }
        }
    }
}

/** A task left: its title, then its three ways out — or « Un jour » and « Annuler » once set aside. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeftRow(task: DayTask, evening: EveningState, viewModel: TasksViewModel) {
    val outcome = evening.outcomeOf(task.id)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.tasksEveningLeft(task.id))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = task.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (outcome == EveningOutcome.ASIDE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (outcome == EveningOutcome.ASIDE) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                SuggestionChip(onClick = {}, label = { Text("Un jour") }, enabled = false)
                TextButton(
                    onClick = { viewModel.chooseOutcome(task.id, EveningOutcome.LATER) },
                    enabled = !evening.isSaving,
                    modifier = Modifier.testTag(UiTags.tasksEveningUndo(task.id)),
                ) {
                    Text("Annuler")
                }
            }
        } else {
            val outcomes = EveningOutcome.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                outcomes.forEachIndexed { index, choice ->
                    SegmentedButton(
                        selected = outcome == choice,
                        onClick = { viewModel.chooseOutcome(task.id, choice) },
                        enabled = !evening.isSaving && (choice != EveningOutcome.TOMORROW || evening.canGoTomorrow(task.id)),
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = outcomes.size),
                        modifier = Modifier
                            .testTag(UiTags.tasksEveningOutcome(task.id, outcomeKeyOf(choice)))
                            .semantics { contentDescription = "${task.title} : ${choice.label}" },
                    ) {
                        Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
