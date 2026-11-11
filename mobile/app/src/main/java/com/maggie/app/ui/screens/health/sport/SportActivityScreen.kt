package com.maggie.app.ui.screens.health.sport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.WorkoutExercise
import com.maggie.app.data.model.WorkoutUnit
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityImmersion
import com.maggie.app.ui.activity.ActivityScaffold
import com.maggie.app.ui.activity.rememberWindowImmersion
import com.maggie.app.ui.layout.WindowWidth
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.ModuleHue
import com.maggie.app.ui.uiTagRoot

private val Coral = MaggieTokens.Module.sport.second
private val CueColor = MaggieTokens.Signal.label
private val MutedColor = MaggieTokens.Signal.neutral

/** « Échelon 4 », « Échelon 4 » only when the exercise has a step. */
internal fun stepLabel(exercise: WorkoutExercise): String? = exercise.step.takeIf { it > 0 }?.let { "Échelon $it" }

/** « 1 / 6 »: the 1-based exercise on screen. */
internal fun exerciseCounter(index: Int, count: Int): String = "${index + 1} / $count"

/** The Sport activity (MAG-779): the session's exercises, one at a time; `maggie://health/sport/activity/{id}`. */
@Composable
fun SportActivityScreen(viewModel: SportActivityViewModel, onQuit: () -> Unit, onOpenMaggie: () -> Unit, onFinished: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(state.finished) { if (state.finished) onFinished() }
    SportActivityContent(
        state = state,
        actions = SportActions(
            onDone = viewModel::setDone,
            onSkipRest = viewModel::skipRest,
            onBack = viewModel::back,
            onStartHold = viewModel::startHold,
            onPauseHold = viewModel::pauseHold,
            onAdjustRest = viewModel::adjustRest,
            onChangeReps = viewModel::changeReps,
            onChangeLoad = viewModel::changeLoad,
        ),
        onQuit = { if (state.load == SportLoad.READY) viewModel.askQuit() else onQuit() },
        onConfirmQuit = {
            viewModel.dismissQuit()
            onQuit()
        },
        onDismissQuit = viewModel::dismissQuit,
        onRetry = viewModel::retry,
        onOpenMaggie = onOpenMaggie,
    )
}

/** What the controls do: each is one step of the session. */
data class SportActions(
    val onDone: () -> Unit = {},
    val onSkipRest: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onStartHold: () -> Unit = {},
    val onPauseHold: () -> Unit = {},
    val onAdjustRest: (Int) -> Unit = {},
    val onChangeReps: (Int) -> Unit = {},
    val onChangeLoad: (Double) -> Unit = {},
)

@Composable
fun SportActivityContent(
    state: SportActivityUiState,
    actions: SportActions = SportActions(),
    onQuit: () -> Unit,
    onConfirmQuit: () -> Unit,
    onDismissQuit: () -> Unit,
    onRetry: () -> Unit,
    onOpenMaggie: () -> Unit,
    immersion: ActivityImmersion = rememberWindowImmersion(),
) {
    val expanded = rememberAppLayout().width == WindowWidth.EXPANDED
    ActivityScaffold(
        name = "la séance",
        hue = ModuleHue.HEALTH,
        onQuit = onQuit,
        onOpenMaggie = onOpenMaggie,
        immersion = immersion,
        progress = { SportProgress(state, expanded) },
        quitFirst = true,
    ) {
        when (state.load) {
            SportLoad.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.testTag(UiTags.SPORT_ACTIVITY_LOADING))
            }
            SportLoad.ERROR -> Column(
                Modifier.fillMaxSize().testTag(UiTags.SPORT_ACTIVITY_ERROR),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("La séance n'a pas pu être chargée", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRetry, modifier = Modifier.testTag(UiTags.SPORT_ACTIVITY_RETRY)) { Text("Réessayer") }
            }
            SportLoad.NOT_FOUND -> Box(Modifier.fillMaxSize().testTag(UiTags.SPORT_ACTIVITY_EMPTY), contentAlignment = Alignment.Center) {
                Text("Cette séance n'existe plus", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SportLoad.READY ->
                if (expanded) SportTablet(state, actions) else SportPhone(state, actions)
        }
        if (state.quitAsked) QuitDialog(expanded, onConfirmQuit, onDismissQuit)
    }
}

/** The header: the session's title, « 1 / 6 », and one segment per exercise — done, in progress, to come. */
@Composable
private fun SportProgress(state: SportActivityUiState, expanded: Boolean) {
    if (state.load != SportLoad.READY) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.workout?.title.orEmpty(),
                fontSize = if (expanded) 18.sp else 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f).testTag(UiTags.SPORT_ACTIVITY_TITLE),
            )
            Text(
                text = exerciseCounter(state.exerciseIndex, state.count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.SPORT_ACTIVITY_COUNTER),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(state.count) { index ->
                val color = when {
                    index < state.exerciseIndex -> MaterialTheme.colorScheme.primary
                    index == state.exerciseIndex -> Coral
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                }
                Box(Modifier.weight(1f).height(5.dp).background(color, RoundedCornerShape(3.dp)))
            }
        }
    }
}

@Composable
private fun SportPhone(state: SportActivityUiState, actions: SportActions) {
    val exercise = state.current ?: return
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ExerciseBlock(exercise, state.lastTimes[exercise.exercise], nameSize = 32, cueSize = 18)
            SportControls(state, exercise, actions, PhoneMetrics)
        }
        Spacer(Modifier.height(12.dp))
        SportBottom(state, exercise, actions, PhoneMetrics, nextBelow = false)
    }
}

@Composable
private fun SportTablet(state: SportActivityUiState, actions: SportActions) {
    val exercise = state.current ?: return
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(320.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(UiTags.SPORT_ACTIVITY_LIST),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.exercises.forEachIndexed { index, item ->
                val current = index == state.exerciseIndex
                Row(
                    Modifier.fillMaxWidth().height(38.dp)
                        .background(if (current) Coral.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = if (index < state.exerciseIndex) "✓" else "${index + 1}",
                        color = if (current) Coral else MutedColor,
                        fontSize = 14.sp,
                        modifier = Modifier.width(20.dp),
                    )
                    Text(item.name, fontSize = 15.sp, maxLines = 1, color = if (current) Coral else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ExerciseBlock(exercise, state.lastTimes[exercise.exercise], nameSize = 56, cueSize = 26)
            SportControls(state, exercise, actions, TabletMetrics)
            SportBottom(state, exercise, actions, TabletMetrics, nextBelow = true)
        }
    }
}

/** The mockups' sizes, phone then tablet. */
private class SportMetrics(
    val pillWidth: Int, val pillHeight: Int, val pillFont: Int, val planFont: Int,
    val stepButton: Int, val repsFont: Int, val repsCaption: Int,
    val ring: Int, val ringStroke: Int, val clockFont: Int, val captionFont: Int, val adjust: Int, val adjustFont: Int,
    val loadHeight: Int, val loadRadius: Int, val loadButton: Int, val loadFont: Int,
    val button: Int, val primaryFont: Int, val nextFont: Int,
)

private val PhoneMetrics = SportMetrics(56, 44, 18, 18, 64, 104, 15, 200, 10, 60, 16, 52, 15, 60, 20, 48, 22, 64, 19, 14)
private val TabletMetrics = SportMetrics(76, 56, 22, 24, 84, 150, 18, 290, 14, 88, 20, 64, 17, 72, 22, 56, 28, 72, 21, 16)

private fun SportActivityUiState.isHeld() = current?.unit == WorkoutUnit.SECONDS

/** The plan line, one pastille per set, the stepper or the ring, the lest. */
@Composable
private fun SportControls(state: SportActivityUiState, exercise: WorkoutExercise, actions: SportActions, m: SportMetrics) {
    val resting = state.phase == SportPhase.REST
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            planLabel(exercise, state.loadKg),
            color = CueColor,
            fontSize = m.planFont.sp,
            modifier = Modifier.testTag(UiTags.SPORT_ACTIVITY_PLAN),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(exercise.sets) { set -> SetPill(state, exercise, set, m) }
        }
        if (resting || state.isHeld()) {
            TimerRing(state, exercise, actions, m)
        } else {
            RepsStepper(state, exercise, actions, m)
        }
        if (state.loadKg != null) LoadCard(state.loadKg!!, actions, m)
    }
}

@Composable
private fun SetPill(state: SportActivityUiState, exercise: WorkoutExercise, set: Int, m: SportMetrics) {
    val done = state.session.done.getOrNull(state.exerciseIndex).orEmpty()
    val current = set == state.setIndex && state.phase != SportPhase.REST
    val finished = set < done.size && !current
    val shape = RoundedCornerShape(14.dp)
    val text = when {
        finished -> done[set].let { countLabel(exercise, it.reps ?: it.seconds ?: it.meters ?: 0) }
        current && !state.isHeld() -> countLabel(exercise, state.reps)
        else -> countLabel(exercise, exercise.target)
    }
    val base = Modifier.size(m.pillWidth.dp, m.pillHeight.dp).semantics(mergeDescendants = true) {}.testTag("${UiTags.SPORT_ACTIVITY_PILL}$set")
    Box(
        when {
            finished -> base.background(Coral.copy(alpha = 0.18f), shape)
            current -> base.border(2.dp, Coral, shape)
            else -> base.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = m.pillFont.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                finished -> Coral
                current -> MaterialTheme.colorScheme.onSurface
                else -> MutedColor
            },
        )
    }
}

@Composable
private fun RepsStepper(state: SportActivityUiState, exercise: WorkoutExercise, actions: SportActions, m: SportMetrics) {
    val step = if (exercise.unit == WorkoutUnit.METERS) 5 else 1
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RoundStepper("−", m.stepButton, 26, UiTags.SPORT_ACTIVITY_REPS_MINUS) { actions.onChangeReps(-step) }
            Text(
                "${state.reps}",
                fontSize = m.repsFont.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
                modifier = Modifier.width((m.repsFont * 1.3).dp).testTag(UiTags.SPORT_ACTIVITY_REPS_VALUE),
            )
            RoundStepper("+", m.stepButton, 26, UiTags.SPORT_ACTIVITY_REPS_PLUS) { actions.onChangeReps(step) }
        }
        Text(
            if (exercise.unit == WorkoutUnit.METERS) "mètres" else "répétitions",
            color = CueColor,
            fontSize = m.repsCaption.sp,
        )
    }
}

@Composable
private fun RoundStepper(label: String, size: Int, font: Int, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = font.sp) }
}

@Composable
private fun TimerRing(state: SportActivityUiState, exercise: WorkoutExercise, actions: SportActions, m: SportMetrics) {
    val resting = state.phase == SportPhase.REST
    val remaining = state.remaining ?: 0
    val total = if (resting) maxOf(exercise.restSeconds ?: 0, remaining) else exercise.target
    val progress = if (total > 0) (remaining.toFloat() / total).coerceIn(0f, 1f) else 0f
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(m.ring.dp).testTag(UiTags.SPORT_ACTIVITY_TIMER), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = m.ringStroke.dp.toPx()
                val inset = stroke / 2
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                drawArc(Coral, -90f, 360f * progress, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    ringClock(remaining),
                    fontSize = m.clockFont.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.testTag(UiTags.SPORT_ACTIVITY_TIMER_CLOCK),
                )
                Text(
                    ringCaption(state.phase, state.timerRunning),
                    color = CueColor,
                    fontSize = m.captionFont.sp,
                    modifier = Modifier.testTag(UiTags.SPORT_ACTIVITY_TIMER_CAPTION),
                )
            }
        }
        if (resting) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AdjustButton("−15", m, UiTags.SPORT_ACTIVITY_TIMER_MINUS) { actions.onAdjustRest(-REST_STEP) }
                AdjustButton("+15", m, UiTags.SPORT_ACTIVITY_TIMER_PLUS) { actions.onAdjustRest(REST_STEP) }
            }
        }
    }
}

private const val REST_STEP = 15
private const val LOAD_STEP = 5.0

@Composable
private fun AdjustButton(label: String, m: SportMetrics, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.height(m.adjust.dp).width((m.adjust * 1.5).dp)
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = m.adjustFont.sp) }
}

@Composable
private fun LoadCard(loadKg: Double, actions: SportActions, m: SportMetrics) {
    Row(
        Modifier.fillMaxWidth().height(m.loadHeight.dp)
            .background(MaggieTokens.Night.raised, RoundedCornerShape(m.loadRadius.dp))
            .padding(horizontal = 12.dp)
            .testTag(UiTags.SPORT_ACTIVITY_LOAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Lest", color = CueColor, fontSize = (m.loadFont - 6).coerceAtLeast(16).sp, modifier = Modifier.weight(1f))
        RoundStepper("−", m.loadButton, 22, UiTags.SPORT_ACTIVITY_LOAD_MINUS) { actions.onChangeLoad(-LOAD_STEP) }
        Text(
            loadLabel(loadKg),
            fontSize = m.loadFont.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width((m.loadFont * 4.5).dp).testTag(UiTags.SPORT_ACTIVITY_LOAD_VALUE),
        )
        RoundStepper("+", m.loadButton, 22, UiTags.SPORT_ACTIVITY_LOAD_PLUS) { actions.onChangeLoad(LOAD_STEP) }
    }
}

/** « Ensuite · … » and the buttons: ‹, « Démarrer » / « Pause » on a held exercise, « Série faite » / « Passer le repos ». */
@Composable
private fun SportBottom(state: SportActivityUiState, exercise: WorkoutExercise, actions: SportActions, m: SportMetrics, nextBelow: Boolean) {
    val resting = state.phase == SportPhase.REST
    val next = if (resting) nextInRest(state.exercises, state.session) else nextInSet(state.exercises, state.session)
    val nextLine: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MutedColor)) { append("Ensuite · ") }
                    withStyle(SpanStyle(color = CueColor)) { append(next) }
                },
                fontSize = m.nextFont.sp,
                modifier = Modifier.testTag(UiTags.SPORT_ACTIVITY_NEXT),
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!nextBelow) nextLine()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SportButton(
                "‹", Modifier.width(m.button.dp).semantics { contentDescription = "Étape précédente" }.testTag(UiTags.SPORT_ACTIVITY_BACK),
                m, Color.White.copy(alpha = 0.08f), MaterialTheme.colorScheme.onSurface, 28, actions.onBack,
            )
            if (state.isHeld() && !resting) {
                SportButton(
                    if (state.timerRunning) "Pause" else "Démarrer",
                    Modifier.weight(1f).testTag(UiTags.SPORT_ACTIVITY_HOLD),
                    m, Coral.copy(alpha = 0.14f), Coral, 18,
                    if (state.timerRunning) actions.onPauseHold else actions.onStartHold,
                )
            }
            SportButton(
                if (resting) "Passer le repos" else "Série faite",
                Modifier.weight(1f).testTag(UiTags.SPORT_ACTIVITY_PRIMARY),
                m, Coral, MaggieTokens.Night.background, m.primaryFont,
                if (resting) actions.onSkipRest else actions.onDone,
            )
        }
        if (nextBelow) nextLine()
    }
}

@Composable
private fun SportButton(label: String, modifier: Modifier, m: SportMetrics, container: Color, content: Color, font: Int, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(m.button.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) { Text(label, fontSize = font.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

@Composable
private fun ExerciseBlock(exercise: WorkoutExercise, lastTime: String?, nameSize: Int, cueSize: Int) {
    Column(Modifier.testTag(UiTags.SPORT_ACTIVITY_EXERCISE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stepLabel(exercise)?.let {
            Text(it, color = Coral, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag(UiTags.SPORT_ACTIVITY_STEP))
        }
        Text(
            exercise.name,
            fontSize = nameSize.sp,
            lineHeight = (nameSize * 1.1).sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(UiTags.SPORT_ACTIVITY_NAME),
        )
        exercise.cue?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = CueColor, fontSize = cueSize.sp, lineHeight = (cueSize * 1.4).sp, modifier = Modifier.testTag(UiTags.SPORT_ACTIVITY_CUE))
        }
        lastTime?.let {
            Spacer(Modifier.size(4.dp))
            Text("La dernière fois · $it", color = MutedColor, fontSize = 14.sp, modifier = Modifier.testTag(UiTags.SPORT_ACTIVITY_LAST_TIME))
        }
    }
}

/** « Quitter la séance ? »: the sets done are kept, the status stays for the resume. */
@Composable
private fun QuitDialog(expanded: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.uiTagRoot().testTag(UiTags.SPORT_ACTIVITY_QUIT_DIALOG),
        shape = RoundedCornerShape(28.dp),
        containerColor = MaggieTokens.Night.raised,
        title = { Text("Quitter la séance ?", fontSize = if (expanded) 24.sp else 22.sp) },
        text = { Text("Les séries faites sont gardées.", fontSize = 16.sp, color = CueColor) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.height(56.dp).testTag(UiTags.SPORT_ACTIVITY_QUIT_CONFIRM),
            ) { Text("Quitter", color = Coral, fontSize = 17.sp) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.height(56.dp).testTag(UiTags.SPORT_ACTIVITY_QUIT_STAY),
            ) { Text("Continuer", fontSize = 17.sp) }
        },
    )
}
