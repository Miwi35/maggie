package com.maggie.app.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.HomeRoutine
import com.maggie.app.data.model.HomeSaga
import com.maggie.app.data.model.HomeTask
import com.maggie.app.data.model.HomeTasks
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.screens.tasks.lateLabel
import org.koin.androidx.compose.koinViewModel

private val CHECK_BOX = 48.dp
private val CHECK_SQUARE = 24.dp
private val SUBTASK_INDENT = 32.dp

/** « 3 sur 5 ». */
internal fun ringLabel(done: Int, total: Int): String = "$done sur $total"

/** « 1 en retard ». */
internal fun overdueLabel(overdue: Int): String = "$overdue en retard"

/** « + 2 autres ». */
internal fun moreLabel(more: Int): String = if (more == 1) "+ 1 autre" else "+ $more autres"

/** « Saga · 2/6 ». */
internal fun sagaLabel(saga: HomeSaga): String = "${saga.title} · ${saga.done}/${saga.total}"

/**
 * The entry of the registry: the card for [day], with the ViewModel of the app. The card opens the Tâches screen
 * through the Accueil's navigation.
 */
@Composable
fun TasksHomeCard(day: HomeDay, viewModel: TasksCardViewModel = koinViewModel()) {
    val open = LocalHomeNavigation.current
    LaunchedEffect(day) { viewModel.show(day) }
    DisposableEffect(viewModel) { onDispose { viewModel.onClosed() } }
    TasksCard(viewModel = viewModel, onOpen = { open(Screen.Tasks.route) })
}

/**
 * The « Tâches » card (MAG-561): the ring « x sur n » and « n en retard », the tasks, each with a box to tick,
 * « + n autres », the saga of the moment and the bar of routines to tick. The header, a task's title and
 * « + n autres » open the Tâches screen ([onOpen]); the boxes and the chips tick in place. A card with nothing
 * to say draws nothing.
 */
@Composable
fun TasksCard(viewModel: TasksCardViewModel, onOpen: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val card = state.card
    if (card != null && card.isEmpty && state.message == null) return

    HomeCard(title = "Tâches", module = HomeModules.TASKS, onOpen = onOpen) {
        when {
            card != null -> TasksCardBody(
                card = card,
                canTick = state.canTick,
                onToggleTask = viewModel::toggleTask,
                onToggleRoutine = viewModel::toggleRoutine,
                onOpen = onOpen,
            )
            state.error != null -> ReadFailure(message = state.error.orEmpty(), onRetry = viewModel::retry)
            else -> Text(
                text = "Chargement…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.message?.let { message -> FailureMessage(message, onDismiss = viewModel::dismissMessage) }
    }
}

@Composable
private fun ReadFailure(message: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.testTag(UiTags.HOME_TASKS_ERROR)) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.HOME_TASKS_RETRY)) {
            Text("Réessayer")
        }
    }
}

@Composable
private fun FailureMessage(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.HOME_TASKS_MESSAGE)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fermer") }
    }
}

@Composable
private fun TasksCardBody(
    card: HomeTasks,
    canTick: Boolean,
    onToggleTask: (String) -> Unit,
    onToggleRoutine: (String) -> Unit,
    onOpen: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (card.total > 0) Progress(card)
        card.tasks.forEach { task ->
            TaskRow(task = task, enabled = canTick, onToggle = { onToggleTask(task.id) }, onOpen = onOpen)
        }
        if (card.more > 0) {
            TextButton(
                onClick = onOpen,
                modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.HOME_TASKS_MORE),
            ) {
                Text(moreLabel(card.more))
            }
        }
        card.saga?.let { saga ->
            Text(
                text = sagaLabel(saga),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.HOME_TASKS_SAGA),
            )
        }
        if (card.routines.isNotEmpty()) {
            RoutinesBar(routines = card.routines, enabled = canTick, onToggle = onToggleRoutine)
        }
    }
}

/** The ring, « 3 sur 5 », and « 1 en retard » when something is late. */
@Composable
private fun Progress(card: HomeTasks) {
    val progress by animateFloatAsState(
        targetValue = if (card.total > 0) card.done.toFloat() / card.total else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "tasks-ring",
    )
    val color = homeModuleColor(HomeModules.TASKS)
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .size(40.dp)
                .testTag(UiTags.HOME_TASKS_RING)
                .semantics { contentDescription = ringLabel(card.done, card.total) },
            color = color,
            trackColor = color.copy(alpha = 0.15f),
            strokeWidth = 5.dp,
        )
        Column {
            Text(text = ringLabel(card.done, card.total), style = MaterialTheme.typography.titleMedium)
            if (card.overdue > 0) {
                Text(
                    text = overdueLabel(card.overdue),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(UiTags.HOME_TASKS_OVERDUE),
                )
            }
        }
    }
}

@Composable
private fun TaskRow(task: HomeTask, enabled: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (task.isSubtask) SUBTASK_INDENT else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TickBox(
            checked = task.done,
            enabled = enabled,
            description = task.title,
            onToggle = onToggle,
            modifier = Modifier.testTag(UiTags.homeTaskCheck(task.id)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = CHECK_BOX)
                .clickable(onClick = onOpen)
                .padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                color = if (task.done) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val late = task.overdueDays?.takeIf { it > 0 && !task.done }
            if (late != null) {
                Text(
                    text = lateLabel(late),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * A tick box: a 24 dp square in a 48 dp hit area, filled with a check that scales in when [checked]. Not
 * tickable when [enabled] is false (tomorrow).
 */
@Composable
private fun TickBox(
    checked: Boolean,
    enabled: Boolean,
    description: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = homeModuleColor(HomeModules.TASKS)
    val fill by animateColorAsState(
        targetValue = if (checked) accent else Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label = "tick-fill",
    )
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .size(CHECK_BOX)
            .alpha(if (enabled) 1f else 0.5f)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(CHECK_SQUARE)
                .background(fill, RoundedCornerShape(6.dp))
                .border(BorderStroke(2.dp, if (checked) accent else outline), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = checked,
                enter = scaleIn(tween(200)) + fadeIn(tween(200)),
                exit = scaleOut(tween(150)) + fadeOut(tween(150)),
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** The routines of the moment as chips, ticked in place; they scroll sideways when they do not fit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutinesBar(routines: List<HomeRoutine>, enabled: Boolean, onToggle: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.HOME_TASKS_ROUTINES)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tick: @Composable () -> Unit = {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        routines.forEach { routine ->
            FilterChip(
                selected = routine.checked,
                onClick = { onToggle(routine.id) },
                enabled = enabled,
                label = { Text(routine.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = tick.takeIf { routine.checked },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag(UiTags.homeRoutineCheck(routine.id)),
            )
        }
    }
}
