package com.maggie.app.ui.screens.tasks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DaySlot
import com.maggie.app.data.model.DayTask
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.shared.PostponedLabel
import com.maggie.app.ui.screens.shared.TaskPostponed
import com.maggie.app.ui.screens.shared.UrgencyBadge
import com.maggie.app.ui.screens.shared.minutesLabel
import com.maggie.app.ui.uiTagRoot
import java.time.LocalDate

/** The ring's words, also what a screen reader says of it. */
internal fun ringLabelOf(done: Int, total: Int): String = "$done sur $total"

/**
 * « Aujourd'hui » as the day (MAG-497): « Maintenant », the ring, « Mes 5 » tickable in one gesture,
 * the add field, and the reserve folded under its count. A row opens its task through [onOpen],
 * which reads it whole first ([TasksViewModel.openTask]).
 */
@Composable
internal fun DayContent(
    day: DayPlan,
    state: TasksUiState,
    viewModel: TasksViewModel,
    onFocus: (taskId: String) -> Unit,
) {
    val onOpen: (String) -> Unit = viewModel::openTask
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (day.total > 0) {
            item(key = "ring") { DayRing(done = day.done, total = day.total) }
        }
        day.now?.let { now ->
            item(key = "now") {
                NowCard(task = now.task, onToggle = viewModel::toggleDayTask, onOpen = onOpen, onFocus = onFocus)
            }
        }
        item(key = "slots") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Mes 5",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::openMorning, modifier = Modifier.testTag(UiTags.TASKS_CHANGE)) {
                    Text("Changer")
                }
            }
        }
        if (day.slots.isNotEmpty()) {
            items(day.slots, key = { "slot_" + it.task.id }) { slot ->
                SlotRow(
                    slot = slot,
                    day = day.day,
                    enabled = !state.isSavingDay,
                    onToggle = viewModel::toggleDayTask,
                    onOpen = onOpen,
                    onFocus = onFocus,
                    onRemove = viewModel::removeFromDay,
                )
            }
        }
        item(key = "add") {
            QuickAdd(
                state = state,
                viewModel = viewModel,
                hint = "Ajouter une tâche",
                inputTag = UiTags.TASKS_DAY_ADD_INPUT,
                submitTag = UiTags.TASKS_DAY_ADD_SUBMIT,
            )
        }
        item(key = "reserve") {
            ReserveHeader(count = day.reserve.size, open = state.isReserveOpen, onToggle = viewModel::toggleReserve)
        }
        if (state.isReserveOpen) {
            items(day.reserve, key = { "reserve_" + it.id }) { task ->
                ReserveRow(
                    task = task,
                    day = day,
                    enabled = !state.isSavingDay,
                    onOpen = onOpen,
                    onPutInDay = viewModel::putInDay,
                )
            }
        }
    }
}

/** [total] segments, [done] of them filled: the count has its picture, and the words beside it. */
@Composable
internal fun DayRing(done: Int, total: Int) {
    val filled = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val label = ringLabelOf(done, total)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(UiTags.TASKS_RING)
            .clearAndSetSemantics { contentDescription = "$label tâches faites" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(modifier = Modifier.size(44.dp)) {
            val stroke = 6.dp.toPx()
            val gap = if (total > 1) 10f else 0f
            val sweep = 360f / total - gap
            val inset = stroke / 2
            for (segment in 0 until total) {
                drawArc(
                    color = if (segment < done) filled else empty,
                    startAngle = -90f + segment * (360f / total) + gap / 2,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke),
                )
            }
        }
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun NowCard(task: DayTask, onToggle: (DayTask) -> Unit, onOpen: (String) -> Unit, onFocus: (String) -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(UiTags.TASKS_NOW),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Maintenant",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = task.isDone,
                    onCheckedChange = { onToggle(task) },
                    modifier = Modifier.testTag(UiTags.TASKS_NOW_CHECK),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = task.title, style = MaterialTheme.typography.titleMedium)
                    task.firstStep?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                task.estimatedMinutes?.let { Minutes(it) }
            }
            Button(
                onClick = { onFocus(task.id) },
                modifier = Modifier.testTag(UiTags.TASKS_NOW_FOCUS),
            ) {
                Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Juste 10 minutes", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun SlotRow(
    slot: DaySlot,
    day: LocalDate,
    enabled: Boolean,
    onToggle: (DayTask) -> Unit,
    onOpen: (String) -> Unit,
    onFocus: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val task = slot.task
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(task.id) }
            .testTag(UiTags.tasksSlot(slot.position))
            .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = task.isDone,
            onCheckedChange = { onToggle(task) },
            modifier = Modifier.testTag(UiTags.tasksSlotCheck(task.id)),
        )
        DayTaskText(task = task, day = day, modifier = Modifier.weight(1f))
        task.estimatedMinutes?.let { Minutes(it) }
        Box {
            IconButton(
                onClick = { menu = true },
                enabled = enabled,
                modifier = Modifier.testTag(UiTags.tasksSlotMenu(task.id)),
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "Options de « ${task.title} »")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.uiTagRoot()) {
                if (!task.isDone) {
                    DropdownMenuItem(
                        text = { Text("Focus") },
                        onClick = {
                            menu = false
                            onFocus(task.id)
                        },
                        modifier = Modifier.testTag(UiTags.TASKS_SLOT_FOCUS),
                    )
                }
                DropdownMenuItem(
                    text = { Text("Remettre dans la réserve") },
                    onClick = {
                        menu = false
                        onRemove(task.id)
                    },
                    modifier = Modifier.testTag(UiTags.TASKS_SLOT_REMOVE),
                )
            }
        }
    }
}

@Composable
private fun ReserveHeader(count: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .testTag(UiTags.TASKS_RESERVE_TOGGLE)
            .semantics { stateDescription = if (open) "dépliée" else "repliée" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Réserve ($count)",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
}

/**
 * A reserve task: its button fills a free slot, or — the five taken — opens « À la place de … »,
 * one entry per slot; the slot chosen goes back to the reserve.
 */
@Composable
private fun ReserveRow(
    task: DayTask,
    day: DayPlan,
    enabled: Boolean,
    onOpen: (String) -> Unit,
    onPutInDay: (String, String?) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(task.id) }
            .testTag(UiTags.tasksReserveItem(task.id))
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DayTaskText(task = task, day = day.day, modifier = Modifier.weight(1f))
        task.estimatedMinutes?.let { Minutes(it) }
        Box {
            IconButton(
                onClick = { if (day.isFull) menu = true else onPutInDay(task.id, null) },
                enabled = enabled,
                modifier = Modifier.testTag(UiTags.tasksReserveAdd(task.id)),
            ) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Mettre « ${task.title} » dans la journée")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.uiTagRoot()) {
                day.slots.forEach { slot ->
                    DropdownMenuItem(
                        text = { Text("À la place de « ${slot.task.title} »", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            menu = false
                            onPutInDay(task.id, slot.task.id)
                        },
                        modifier = Modifier.testTag(UiTags.tasksReplace(slot.position)),
                    )
                }
            }
        }
    }
}

/** The title and its marks: « reportée depuis … » in a neutral colour, or a critical task's own. */
@Composable
private fun DayTaskText(task: DayTask, day: LocalDate, modifier: Modifier = Modifier) {
    val postponed = if (task.isDone) null else TaskPostponed.labelOf(task.postponedSince, task.urgency, day)
    Column(modifier = modifier) {
        Text(
            text = task.title,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
            color = if (task.isDone) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (task.isCritical) UrgencyBadge(task.urgency)
        postponed?.let { PostponedLabel(it) }
    }
}

@Composable
private fun Minutes(minutes: Int) {
    Text(
        text = minutesLabel(minutes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
