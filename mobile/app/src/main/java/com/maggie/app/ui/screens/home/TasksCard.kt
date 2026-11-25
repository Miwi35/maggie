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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.HomeRoutine
import com.maggie.app.data.model.HomeSaga
import com.maggie.app.data.model.HomeTask
import com.maggie.app.data.model.HomeTasks
import com.maggie.app.data.model.RoutineText
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.screens.tasks.UrgencyChip
import com.maggie.app.ui.screens.tasks.UrgencyPill
import com.maggie.app.ui.screens.tasks.WhenPill
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.maggieSurface
import org.koin.androidx.compose.koinViewModel

private val CHECK_BOX = 48.dp
private val CHECK_SQUARE = 26.dp
private val SUBTASK_INDENT = 32.dp

/** « 3 sur 5 ». */
internal fun ringLabel(done: Int, total: Int): String = "$done sur $total"

/** The sentence under the ring: what the day asks, or what tomorrow holds. */
internal fun ringNext(done: Int, total: Int, tomorrow: Boolean): String = when {
    tomorrow -> "Prévues demain"
    done >= total -> "Journée bouclée"
    done == 0 -> "Une seule suffit pour lancer la journée"
    else -> "Le reste peut attendre une pause"
}

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
 * The « Tâches » card (MAG-561): the ring « x sur n » and its sentence, the tasks, each with a box to tick,
 * « + n autres », the saga of the moment and the routines to tick. The header, a task's title and
 * « + n autres » open the Tâches screen ([onOpen]); the boxes tick in place. A card with nothing
 * to say draws nothing.
 */
@Composable
fun TasksCard(viewModel: TasksCardViewModel, onOpen: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val card = state.card
    ReportHomeCard(HomeModules.TASKS, state.status)
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            RoutinesList(routines = card.routines, enabled = canTick, onToggle = onToggleRoutine)
        }
    }
}

/** The ring, « 3 sur 5 » and the sentence beside it. */
@Composable
private fun Progress(card: HomeTasks) {
    val progress by animateFloatAsState(
        targetValue = if (card.total > 0) card.done.toFloat() / card.total else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "tasks-ring",
    )
    val color = homeModuleColor(HomeModules.TASKS)
    val track = maggieSurface().track
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .semantics { contentDescription = "${card.done} tâches faites sur ${card.total}" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                val inset = stroke.width / 2
                val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
                if (progress > 0f) drawArc(color, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = stroke)
            }
            Text(text = card.done.toString(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = ringLabel(card.done, card.total),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag(UiTags.HOME_TASKS_RING),
            )
            Text(
                text = ringNext(card.done, card.total, tomorrow = !card.editable),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.HOME_TASKS_NEXT),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskRow(task: HomeTask, enabled: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val caption = maggieSurface().caption
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
            ring = when (task.urgency) {
                UrgencyChip.CRITICAL -> MaggieTokens.Signal.danger
                UrgencyChip.HIGH -> MaggieTokens.Signal.warning
                null -> null
            },
            fill = homeModuleColor(HomeModules.TASKS),
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
                fontSize = 15.sp,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                color = if (task.done) caption else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.whenChip != null || task.urgency != null || task.meta != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
                ) {
                    task.whenChip?.let { WhenPill(it, Modifier.testTag(UiTags.homeTaskWhen(task.id))) }
                    task.urgency?.let { UrgencyPill(it, Modifier.testTag(UiTags.homeTaskUrgency(task.id))) }
                    task.meta?.let { Text(it, fontSize = 12.sp, color = caption, modifier = Modifier.testTag(UiTags.homeTaskMeta(task.id))) }
                }
            }
        }
    }
}

/**
 * A tick box: a 26 dp square in a 48 dp hit area, ringed with the urgency colour ([ring], else the outline), filled
 * with [fill] and a check that scales in when [checked]. Not tickable when [enabled] is false (tomorrow).
 */
@Composable
private fun TickBox(
    checked: Boolean,
    enabled: Boolean,
    description: String,
    onToggle: () -> Unit,
    fill: Color,
    modifier: Modifier = Modifier,
    ring: Color? = null,
) {
    val background by animateColorAsState(
        targetValue = if (checked) fill else Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label = "tick-fill",
    )
    val border = if (checked) fill else ring ?: MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(9.dp)
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
                .background(background, shape)
                .border(BorderStroke(2.dp, border), shape),
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

/** The routines of the moment as rows to tick, under a rule: « Routines · après le dîner » and « 1 sur 3 ». */
@Composable
private fun RoutinesList(routines: List<HomeRoutine>, enabled: Boolean, onToggle: (String) -> Unit) {
    val caption = maggieSurface().caption
    val moments = routines.map { RoutineText.anchorOf(it.anchor, it.time) }.distinct()
    val header = if (moments.size == 1) "Routines · ${moments.first()}" else "Routines"
    Column(modifier = Modifier.fillMaxWidth().testTag(UiTags.HOME_TASKS_ROUTINES)) {
        HorizontalDivider(modifier = Modifier.padding(bottom = 6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(header, fontSize = 12.sp, color = caption)
            Text(
                ringLabel(routines.count { it.checked }, routines.size),
                fontSize = 12.sp,
                color = caption,
                modifier = Modifier.testTag(UiTags.HOME_TASKS_ROUTINES_COUNT),
            )
        }
        routines.forEach { routine ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TickBox(
                    checked = routine.checked,
                    enabled = enabled,
                    description = routine.title,
                    onToggle = { onToggle(routine.id) },
                    fill = homeModuleColor(HomeModules.FINANCE),
                    modifier = Modifier.testTag(UiTags.homeRoutineCheck(routine.id)),
                )
                Text(
                    text = routine.title,
                    fontSize = 15.sp,
                    textDecoration = if (routine.checked) TextDecoration.LineThrough else null,
                    color = if (routine.checked) caption else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
            }
        }
    }
}
