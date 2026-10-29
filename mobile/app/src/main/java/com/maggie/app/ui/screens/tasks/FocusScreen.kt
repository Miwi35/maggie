package com.maggie.app.ui.screens.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.maggie.app.data.model.Task
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.shared.minutesLabel

private val WIDE_FROM = 600.dp

/** What a screen reader says of the ring: the time left, never the bare digits. */
internal fun timerDescriptionOf(remainingMs: Long, phase: FocusTimerPhase, totalMs: Long): String = when (phase) {
    FocusTimerPhase.FINISHED -> "Les ${minutesLabel((totalMs / 60_000).toInt())} sont passées"
    FocusTimerPhase.PAUSED -> "Minuteur en pause, ${timerLabelOf(remainingMs)} restantes"
    else -> "Minuteur, ${timerLabelOf(remainingMs)} restantes"
}

/**
 * The Focus (MAG-499): one task, its first step, the 10-minute ring and its petits pas. On a wide
 * window the timer takes the left and the petits pas the right; the same screen otherwise, in one column.
 * [onExit] is called once the Focus is over — « Plus tard » or back; « C'est fait » calls [onDone]
 * with the task ticked, for its Bravo (MAG-508).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(
    taskId: String,
    viewModel: FocusViewModel,
    onExit: () -> Unit,
    onDone: (BravoRequest) -> Unit = { onExit() },
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(taskId) { viewModel.open(taskId) }
    // The ticks may have been held back while the app was away: the time left is read again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshTimer() }
    LaunchedEffect(state.leaves) {
        if (!state.leaves) return@LaunchedEffect
        val task = state.task
        if (state.completed && task != null) onDone(BravoRequest(task.id, task.title)) else onExit()
    }
    BackHandler(onBack = onExit)

    Scaffold(
        modifier = Modifier.testTag(UiTags.FOCUS),
        topBar = {
            TopAppBar(
                title = { Text("Focus") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val task = state.task
            when {
                state.error != null -> Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = state.error.orEmpty(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    TextButton(onClick = onExit) { Text("Retour à la journée") }
                }
                task == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else -> FocusContent(task = task, state = state, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun FocusContent(task: Task, state: FocusUiState, viewModel: FocusViewModel) {
    var splitting by rememberSaveable(task.id) { mutableStateOf(false) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_FROM
        val timer: @Composable () -> Unit = {
            TimerBlock(state = state, viewModel = viewModel)
        }
        val steps: @Composable () -> Unit = {
            StepsBlock(
                state = state,
                splitting = splitting,
                onSplit = { splitting = true },
                onToggle = viewModel::toggleStep,
                onAdd = viewModel::addStep,
            )
        }
        val actions: @Composable () -> Unit = { ActionsBlock(state = state, viewModel = viewModel) }

        if (wide) {
            Row(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TitleBlock(task)
                    timer()
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    steps()
                    actions()
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TitleBlock(task)
                timer()
                steps()
                actions()
            }
        }
    }
}

@Composable
private fun TitleBlock(task: Task) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = task.title, style = MaterialTheme.typography.headlineSmall)
        task.estimatedMinutes?.takeIf { it > 0 }?.let { minutes ->
            Text(
                text = "≈ ${minutesLabel(minutes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.FOCUS_ESTIMATE),
            )
        }
        task.firstStep?.takeIf { it.isNotBlank() }?.let { step ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.FOCUS_FIRST_STEP),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "2 min",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(text = step, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** « Activer le minuteur », and once on, the ring with the one button that starts, pauses, resumes or restarts it. */
@Composable
private fun TimerBlock(state: FocusUiState, viewModel: FocusViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val label = if (state.timerEnabled) "Minuteur · ${minutesLabel((state.timer.totalMs / 60_000).toInt())}" else "Activer le minuteur"
        if (state.timerEnabled) {
            FilledTonalButton(
                onClick = viewModel::toggleTimer,
                modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.FOCUS_TIMER_TOGGLE),
            ) { Text(label) }
        } else {
            OutlinedButton(
                onClick = viewModel::toggleTimer,
                modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.FOCUS_TIMER_TOGGLE),
            ) { Text(label) }
        }
        if (state.timerEnabled) {
            TimerRing(remainingMs = state.remainingMs, totalMs = state.timer.totalMs, phase = state.phase)
            when (state.phase) {
                FocusTimerPhase.IDLE -> TimerButton("Démarrer", Icons.Default.PlayArrow, UiTags.FOCUS_START, viewModel::start)
                FocusTimerPhase.RUNNING -> TimerButton("Pause", Icons.Default.Pause, UiTags.FOCUS_PAUSE, viewModel::pause)
                FocusTimerPhase.PAUSED -> TimerButton("Reprendre", Icons.Default.PlayArrow, UiTags.FOCUS_RESUME, viewModel::resume)
                FocusTimerPhase.FINISHED -> TimerButton(
                    "Encore ${minutesLabel((state.timer.totalMs / 60_000).toInt())}",
                    Icons.Default.Replay,
                    UiTags.FOCUS_RESTART,
                    viewModel::restart,
                )
            }
        }
    }
}

@Composable
private fun TimerButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 56.dp).testTag(tag),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Text(label, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
    }
}

/** The time left as an arc that empties: the number has its picture. */
@Composable
internal fun TimerRing(remainingMs: Long, totalMs: Long, phase: FocusTimerPhase) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val arc = if (phase == FocusTimerPhase.FINISHED) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val fraction = if (totalMs <= 0) 0f else remainingMs.toFloat() / totalMs
    Box(
        modifier = Modifier
            .size(220.dp)
            .testTag(UiTags.FOCUS_RING)
            .clearAndSetSemantics { contentDescription = timerDescriptionOf(remainingMs, phase, totalMs) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val bounds = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = bounds,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = arc,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = bounds,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = timerLabelOf(remainingMs),
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.testTag(UiTags.FOCUS_TIME),
        )
    }
}

/** The petits pas — the task's subtasks — each ticked on its own, and « Je bloque : découpe-la » to write more. */
@Composable
private fun StepsBlock(
    state: FocusUiState,
    splitting: Boolean,
    onSplit: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onAdd: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.steps.isNotEmpty()) {
            Text("Petits pas", style = MaterialTheme.typography.titleSmall)
            state.steps.forEach { step ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = step.isDone,
                        onCheckedChange = { onToggle(step.id, it) },
                        modifier = Modifier
                            .testTag(UiTags.focusStep(step.id))
                            .semantics { contentDescription = "Terminer « ${step.title} »" },
                    )
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (step.isDone) TextDecoration.LineThrough else null,
                        color = if (step.isDone) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (splitting) {
            var title by rememberSaveable { mutableStateOf("") }
            val submit = {
                onAdd(title)
                title = ""
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Un petit pas de plus") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (title.isNotBlank()) submit() }),
                trailingIcon = {
                    IconButton(
                        onClick = submit,
                        enabled = title.isNotBlank(),
                        modifier = Modifier.testTag(UiTags.FOCUS_ADD_SUBMIT),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Ajouter le petit pas")
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.FOCUS_ADD_INPUT),
            )
        } else {
            OutlinedButton(
                onClick = onSplit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(UiTags.FOCUS_BLOCKED),
            ) { Text("Je bloque : découpe-la") }
        }
    }
}

@Composable
private fun ActionsBlock(state: FocusUiState, viewModel: FocusViewModel) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.message?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::dismissMessage) { Text("Fermer") }
            }
        }
        Button(
            onClick = viewModel::complete,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(UiTags.FOCUS_DONE),
        ) { Text("C'est fait", style = MaterialTheme.typography.titleMedium) }
        TextButton(
            onClick = viewModel::later,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(UiTags.FOCUS_LATER),
        ) { Text("Plus tard, sans souci") }
    }
}
