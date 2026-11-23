package com.maggie.app.ui.screens.tasks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Task
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.maggieSurface
import com.maggie.app.util.DateRanges
import java.time.LocalDate
import java.util.Locale

/** The ring's words, also what a screen reader says of it. */
internal fun ringLabelOf(done: Int, total: Int): String = "$done sur $total"

/** The sentence under the count (mock-up `Tac-Main`): what one more box means today. */
internal fun ringPhraseOf(done: Int, total: Int): String = when {
    done == 0 -> "Une seule suffit pour lancer la journée."
    done >= total -> "Tout est fait pour aujourd’hui."
    else -> "La journée est lancée, à ton rythme."
}

/** The cards' side margin: a row, its timer included, never reaches the screen's edge (recette, 10 nov.). */
private val CARD_MARGIN = 16.dp
private val TODAY_ROW_RADIUS = 20.dp

/**
 * « Aujourd'hui » (MAG-766, mock-up `Tac-Main`): the routines band (MAG-512) when some are to tick, the ring, and the
 * tasks of the day each with its box — done in any order, so no number and no « Maintenant ». No reserve: the other
 * tasks are in their views. A row opens its task through [TasksViewModel.openTask]; a timed task's button opens its
 * Focus, timer on, through [onTimer].
 */
@Composable
internal fun DayContent(
    tasks: List<Task>,
    state: TasksUiState,
    viewModel: TasksViewModel,
    onTimer: (taskId: String) -> Unit,
) {
    val today = state.todayDate ?: DateRanges.todayDate()
    val onOpen: (String) -> Unit = viewModel::openTask
    // The window is no longer panned by the keyboard (MAG-532): the list keeps room above it.
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(UiTags.TASKS_TODAY),
        contentPadding = WindowInsets.ime.asPaddingValues(),
    ) {
        state.routinesBand?.let { band ->
            item(key = "routines") {
                RoutinesBandRow(band = band, pending = state.routinesPending, onToggle = viewModel::toggleRoutine)
            }
        }
        if (state.todayTotal > 0) {
            item(key = "ring") { DayRing(done = state.todayDone, total = state.todayTotal, phrase = ringPhraseOf(state.todayDone, state.todayTotal)) }
            item(key = "today_label") {
                Text(
                    text = "AUJOURD’HUI",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = CARD_MARGIN + 4.dp, end = CARD_MARGIN, top = 8.dp, bottom = 4.dp),
                )
            }
        }
        if (tasks.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = emptyTextOf(TasksSection.TODAY),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp).testTag(UiTags.TASKS_EMPTY),
                )
            }
        }
        items(tasks, key = { "today_" + it.id }) { task ->
            TodayRow(
                task = task,
                today = today,
                onToggle = viewModel::toggleTodayTask,
                onOpen = onOpen,
                onTimer = onTimer,
            )
        }
    }
}

/**
 * The day's progress card (mock-up `Tac-Main`): a 76 dp ring of [total] segments, [done] of them filled, « n sur N »
 * and, on the day's list only, the [phrase] under it — the evening review has none.
 */
@Composable
internal fun DayRing(done: Int, total: Int, phrase: String? = null) {
    val filled = MaterialTheme.colorScheme.primary
    val empty = maggieSurface().track
    val label = ringLabelOf(done, total)
    MaggieCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CARD_MARGIN, vertical = 8.dp)
            .testTag(UiTags.TASKS_RING),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Canvas(modifier = Modifier.size(76.dp).clearAndSetSemantics { contentDescription = "$label tâches faites" }) {
                val stroke = 8.dp.toPx()
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
                phrase?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/**
 * A task of the day, in the mock-up's order: the box — its outline in the urgency's colour when Critique or Haute —,
 * the title, the « quand » pill, the urgency pill, the meta, and on the right the timer of a timed task not done.
 * Done, the row is struck and dimmed, without pills.
 */
@Composable
private fun TodayRow(
    task: Task,
    today: LocalDate,
    onToggle: (Task) -> Unit,
    onOpen: (String) -> Unit,
    onTimer: (String) -> Unit,
) {
    val timer = TodayRows.timerMinutesOf(task)
    TaskCardRow(
        task = task,
        whenChip = TodayRows.whenOf(task, today),
        meta = TodayRows.metaOf(task, today),
        rowTag = UiTags.tasksTodayRow(task.id),
        checkTag = UiTags.tasksTodayCheck(task.id),
        onToggle = onToggle,
        onOpen = { onOpen(task.id) },
    ) {
        timer?.let { minutes -> TimerButton(minutes = minutes, title = task.title, onClick = { onTimer(task.id) }, tag = UiTags.tasksTodayTimer(task.id)) }
    }
}

/**
 * A card of the tasks (mock-up `Tac-Main`: `paper`, 20 dp corners, 68 dp at least): the box, the title, the pills and
 * the meta; [trailing] is what sits on the right of an open task (a timer, « Aujourd'hui »).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskCardRow(
    task: Task,
    whenChip: WhenChip?,
    meta: String?,
    rowTag: String,
    checkTag: String,
    onToggle: (Task) -> Unit,
    onOpen: () -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    val urgency = TodayRows.urgencyOf(task)
    val done = task.isDone
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (task.isSubtask) CARD_MARGIN + 16.dp else CARD_MARGIN, end = CARD_MARGIN, top = 4.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(TODAY_ROW_RADIUS))
            .background(maggieSurface().paper)
            .clickable { onOpen() }
            .heightIn(min = 68.dp)
            .padding(start = 4.dp, end = 12.dp)
            .alpha(if (done) 0.6f else 1f)
            .testTag(rowTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val outline = urgency?.let(::urgencyColorOf) ?: MaterialTheme.colorScheme.onSurfaceVariant
        Checkbox(
            checked = done,
            onCheckedChange = { onToggle(task) },
            colors = CheckboxDefaults.colors(
                checkedColor = MaggieTokens.Signal.success,
                uncheckedColor = outline,
            ),
            modifier = Modifier
                .testTag(checkTag)
                .semantics {
                    contentDescription = (if (done) "Rouvrir « " else "Marquer comme faite « ") + task.title + " »"
                },
        )
        Column(
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (whenChip != null || urgency != null || TodayRows.repeats(task) || meta != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    whenChip?.let { WhenPill(it, Modifier.testTag(UiTags.tasksTodayWhen(task.id))) }
                    urgency?.let { UrgencyPill(it, Modifier.testTag(UiTags.tasksTodayUrgency(task.id))) }
                    if (TodayRows.repeats(task)) RepeatMark(task.id, Modifier.align(Alignment.CenterVertically))
                    meta?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
            }
        }
        // The star a task done earned (MAG-509).
        if (done) TaskStar(modifier = Modifier.padding(end = 8.dp))
        trailing()
    }
}

/**
 * « Tâches » (MAG-934): the groups « CETTE SEMAINE · n », « PLUS TARD · n », « SANS DATE · n », each task a card with
 * « Aujourd'hui » on its right, which plans it today.
 */
@Composable
internal fun StockContent(
    groups: List<StockGroup>,
    today: LocalDate,
    viewModel: TasksViewModel,
    onTaskClick: (Task) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = WindowInsets.ime.asPaddingValues(),
    ) {
        groups.forEach { group ->
            item(key = "group_" + group.kind.key) {
                Text(
                    text = group.kind.label.uppercase(Locale.FRENCH) + " · " + group.count,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = CARD_MARGIN + 4.dp, end = CARD_MARGIN, top = 12.dp, bottom = 4.dp)
                        .testTag(UiTags.tasksStockGroup(group.kind.key)),
                )
            }
            items(group.tasks, key = { "stock_" + it.id }) { task ->
                StockRow(task = task, today = today, onToggle = viewModel::toggleDone, onOpen = onTaskClick, onPick = viewModel::planToday)
            }
        }
    }
}

@Composable
internal fun StockRow(
    task: Task,
    today: LocalDate,
    onToggle: (Task) -> Unit,
    onOpen: (Task) -> Unit,
    onPick: (Task) -> Unit,
) {
    TaskCardRow(
        task = task,
        whenChip = TasksStock.whenOf(task, today),
        meta = TodayRows.metaOf(task, today),
        rowTag = UiTags.tasksStockRow(task.id),
        checkTag = UiTags.tasksCheck(task.id),
        onToggle = onToggle,
        onOpen = { onOpen(task) },
    ) {
        if (!task.isSubtask) PickTodayButton(title = task.title, onClick = { onPick(task) }, tag = UiTags.tasksStockPick(task.id))
    }
}

private fun urgencyColorOf(chip: UrgencyChip): Color = when (chip) {
    UrgencyChip.CRITICAL -> MaggieTokens.Signal.danger
    UrgencyChip.HIGH -> MaggieTokens.Signal.warning
}

@Composable
private fun WhenPill(chip: WhenChip, modifier: Modifier = Modifier) {
    val icon = when (chip.kind) {
        WhenKind.AT -> Icons.Default.Schedule
        WhenKind.MOMENT -> Icons.Default.WbSunny
        WhenKind.DUE_TODAY, WhenKind.DUE -> Icons.Default.Flag
    }
    if (chip.kind == WhenKind.DUE_TODAY) {
        Pill(text = chip.label, icon = icon, color = MaggieTokens.Signal.dueToday, background = MaggieTokens.Signal.dueToday.copy(alpha = 0.16f), modifier = modifier)
    } else {
        Pill(text = chip.label, icon = icon, color = MaggieTokens.Signal.label, background = MaggieTokens.Signal.labelFill, modifier = modifier)
    }
}

@Composable
private fun UrgencyPill(chip: UrgencyChip, modifier: Modifier = Modifier) {
    val color = urgencyColorOf(chip)
    val icon = when (chip) {
        UrgencyChip.CRITICAL -> Icons.Default.KeyboardDoubleArrowUp
        UrgencyChip.HIGH -> Icons.Default.KeyboardArrowUp
    }
    Pill(text = chip.label, icon = icon, color = color, background = color.copy(alpha = 0.16f), modifier = modifier)
}

@Composable
private fun Pill(text: String, icon: ImageVector, color: Color, background: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(background, RoundedCornerShape(percent = 50))
            .heightIn(min = 24.dp)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** « ⏱ 20 min »: the Focus of a timed task, its timer on and set to the task's minutes. */
@Composable
private fun TimerButton(minutes: Int, title: String, onClick: () -> Unit, tag: String) {
    val pink = MaggieTokens.Module.taches.second
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = pink.copy(alpha = 0.14f),
        contentColor = pink,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .testTag(tag)
            .semantics { contentDescription = "Lancer un minuteur de $minutes min pour « $title »" },
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text = "$minutes min", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** « + Aujourd'hui »: plans the task of the row today. */
@Composable
private fun PickTodayButton(title: String, onClick: () -> Unit, tag: String) {
    val pink = MaggieTokens.Module.taches.second
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = pink.copy(alpha = 0.14f),
        contentColor = pink,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .testTag(tag)
            .semantics { contentDescription = "Faire aujourd’hui « $title »" },
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text = "Aujourd’hui", style = MaterialTheme.typography.labelLarge)
        }
    }
}
