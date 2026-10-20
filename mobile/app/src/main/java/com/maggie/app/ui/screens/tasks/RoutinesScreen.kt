package com.maggie.app.ui.screens.tasks

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Routine
import com.maggie.app.data.model.RoutineDay
import com.maggie.app.data.model.RoutineGroup
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.interruption.rememberReducedMotion

/** The routines' green (MAG-511): a tick, a day done. Never a red: a day missed is only grey. */
internal val RoutineGreen = Color(0xFF7FD1AE)

/** What reads on the green. */
internal val OnRoutineGreen = Color(0xFF0E3B2A)

/**
 * The « Routines » tab (MAG-511): the daily ones by part of the day, then « Chaque semaine » and
 * « Chaque mois »; each card ticks today, says when, the last seven days and how many times. A
 * card opens the [RoutineEditSheet] on it, « Nouvelle routine » opens it empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(viewModel: RoutinesViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val reducedMotion = rememberReducedMotion()

    DisposableEffect(viewModel) {
        viewModel.onOpened()
        onDispose { viewModel.onClosed() }
    }

    Box(modifier = modifier.fillMaxSize().testTag(UiTags.ROUTINES)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.draft == null) {
                state.message?.let { message -> RoutineMessage(message, onDismiss = viewModel::dismissMessage) }
            }
            PullToRefreshBox(
                isRefreshing = state.isLoading && state.hasContent,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.error != null && !state.hasContent -> Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = state.error.orEmpty(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = viewModel::refresh) { Text("Réessayer") }
                    }
                    state.isLoading && !state.hasContent -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    !state.hasContent -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                        Text(
                            text = "Aucune routine.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(UiTags.ROUTINES_EMPTY),
                        )
                    }
                    else -> RoutineList(
                        routines = state.routines,
                        pending = state.pending,
                        animate = !reducedMotion,
                        onToggle = viewModel::toggleCheck,
                        onOpen = viewModel::openEdit,
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = viewModel::openNew,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Nouvelle routine") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag(UiTags.ROUTINES_ADD),
        )
    }

    state.draft?.let { draft ->
        RoutineEditSheet(
            draft = draft,
            isSaving = state.isSaving,
            message = state.message,
            onChange = viewModel::updateDraft,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
            onDismiss = viewModel::closeEditor,
        )
    }
}

@Composable
internal fun RoutineMessage(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("Fermer") }
    }
}

@Composable
private fun RoutineList(
    routines: List<Routine>,
    pending: Set<String>,
    animate: Boolean,
    onToggle: (Routine) -> Unit,
    onOpen: (Routine) -> Unit,
) {
    val groups = RoutineGroup.grouped(routines)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // Room under the last card for « Nouvelle routine ».
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(groups, key = { it.first.key }) { (group, members) ->
                // One node per group, the cards inside: a journey finds a card in its group.
                Column(
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.routineGroup(group.key)),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    members.forEach { routine ->
                        RoutineCard(
                            routine = routine,
                            pending = routine.id in pending,
                            animate = animate,
                            onToggle = { onToggle(routine) },
                            onOpen = { onOpen(routine) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A routine: today's round when it is due today, its title, when, the last seven days and its
 * total. An inactive one is dimmed and has no round. The total keeps its own node, so it stays
 * addressable inside the card's touch area.
 */
@Composable
internal fun RoutineCard(
    routine: Routine,
    pending: Boolean,
    animate: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (routine.active) 1f else 0.45f)
            .testTag(UiTags.routineCard(routine.id)),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (routine.dueToday && routine.active) {
                RoutineRound(
                    checked = routine.checkedToday,
                    title = routine.title,
                    enabled = !pending,
                    animate = animate,
                    onToggle = onToggle,
                    modifier = Modifier.testTag(UiTags.routineCheck(routine.id)),
                )
            } else {
                Spacer(Modifier.size(48.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(routine.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    routine.whenLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RecentDays(days = routine.recentDays)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = routine.totalLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(UiTags.routineTotal(routine.id)),
                    )
                }
            }
        }
    }
}

/** Today's round, 48 dp to the finger: green and ticked once done, with a small spring. */
@Composable
private fun RoutineRound(
    checked: Boolean,
    title: String,
    enabled: Boolean,
    animate: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fill by animateColorAsState(
        targetValue = if (checked) RoutineGreen else Color.Transparent,
        animationSpec = if (animate) spring() else snap(),
        label = "routine-round-fill",
    )
    val scale by animateFloatAsState(
        targetValue = if (checked) 1f else 0.88f,
        animationSpec = if (animate) spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium) else snap(),
        label = "routine-round-scale",
    )
    Box(
        modifier = modifier
            .size(48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = "Aujourd'hui : $title" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .scale(scale)
                .background(fill, CircleShape)
                .border(2.dp, RoutineGreen, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Default.Check, contentDescription = null, tint = OnRoutineGreen, modifier = Modifier.size(18.dp))
        }
    }
}

/** Seven dots, the oldest first: green done, grey due and not done, nothing on a day it was not due. */
@Composable
private fun RecentDays(days: List<RoutineDay>) {
    val grey = MaterialTheme.colorScheme.outlineVariant
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.clearAndSetSemantics {},
    ) {
        days.forEach { day ->
            val color = when {
                day.checked -> RoutineGreen
                day.due -> grey
                else -> Color.Transparent
            }
            Box(Modifier.size(8.dp).background(color, CircleShape))
        }
    }
}
