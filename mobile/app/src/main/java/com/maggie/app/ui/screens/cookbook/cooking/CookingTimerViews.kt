package com.maggie.app.ui.screens.cookbook.cooking

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.CookingTimer
import com.maggie.app.data.model.CookingTimerStatus
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.ui.UiTags

/** Where the timers go (MAG-582): a strip of chips on a phone held upright, a « Minuteurs » column otherwise. */
enum class TimersLayout {
    STRIP,

    /** Beside the step: a phone or a tablet held sideways. */
    SIDE,

    /** Under the step: a tablet held upright. */
    BELOW,
}

internal fun timersLayout(smallestWidthDp: Int, landscape: Boolean): TimersLayout = when {
    landscape -> TimersLayout.SIDE
    smallestWidthDp >= 600 -> TimersLayout.BELOW
    else -> TimersLayout.STRIP
}

/** The share of the time still to run, the ring's arc. */
private fun fractionOf(remaining: Int, duration: Int): Float =
    if (duration <= 0) 0f else (remaining.toFloat() / maxOf(duration, remaining)).coerceIn(0f, 1f)

@Composable
private fun TimerRing(fraction: Float, size: Dp, stroke: Dp, content: @Composable () -> Unit = {}) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val arc = MaterialTheme.colorScheme.primary
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val width = stroke.toPx()
            val inset = width / 2
            val arcSize = Size(this.size.width - width, this.size.height - width)
            val topLeft = Offset(inset, inset)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width))
            drawArc(arc, -90f, 360f * fraction, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width, cap = StrokeCap.Round))
        }
        content()
    }
}

/** A rung timer pulses until « OK ». */
@Composable
private fun pulseBorder(active: Boolean): BorderStroke? {
    if (!active) return null
    val pulse by rememberInfiniteTransition(label = "timer-pulse").animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "timer-pulse-alpha",
    )
    return BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = pulse))
}

private fun chipValue(state: CookingUiState, timer: CookingTimer): String =
    if (timer.status == CookingTimerStatus.DONE) "c'est prêt" else clockOf(state.remainingOf(timer))

/** « Pâtes · 09:12 », one chip per timer, under the header (phone, upright). */
@Composable
internal fun TimerStrip(state: CookingUiState) {
    if (state.timers.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { contentDescription = "Minuteurs en cours" }
            .testTag(UiTags.COOKING_TIMER_STRIP),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.timers.forEach { timer ->
            val done = timer.status == CookingTimerStatus.DONE
            val paused = timer.status == CookingTimerStatus.PAUSED
            Surface(
                shape = RoundedCornerShape(50),
                color = if (done) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                border = pulseBorder(done),
                modifier = Modifier.heightIn(min = 44.dp).testTag(UiTags.COOKING_TIMER_CHIP),
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        "${timer.label} · ${chipValue(state, timer)}" + if (paused) " · en pause" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The « Minuteurs » column (phone sideways, tablet): a ring, the name, the step, the rest; « Aucun minuteur » when none. */
@Composable
internal fun TimersColumn(state: CookingUiState, modifier: Modifier, ring: Dp) {
    val steps = state.steps
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp).testTag(UiTags.COOKING_TIMERS_COLUMN),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Text(
            "MINUTEURS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "Minuteurs" },
        )
        if (state.timers.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(UiTags.COOKING_TIMERS_EMPTY),
            ) {
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
                    Text("Aucun minuteur", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        state.timers.forEach { timer ->
            val done = timer.status == CookingTimerStatus.DONE
            val remaining = state.remainingOf(timer)
            // A timer of another recipe of the meal reads « en fond » only when its own step says so: unknown here.
            val background = timer.recipeId == state.session?.current?.recipeId && steps.getOrNull(timer.stepPosition - 1)?.background == true
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (done) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainer,
                border = pulseBorder(done),
                modifier = Modifier.fillMaxWidth().testTag(UiTags.COOKING_TIMER_ROW),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TimerRing(fractionOf(remaining, timer.durationSeconds), ring, ring / 10)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(timer.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(timerRowLine(timer, background), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        clockOf(remaining),
                        fontSize = if (ring >= 56.dp) 26.sp else 20.sp,
                        fontWeight = FontWeight.Light,
                        color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag(UiTags.COOKING_TIMER_ROW_CLOCK),
                    )
                }
            }
        }
    }
}

/**
 * The timer of the step on screen: ring, rest, name and what it does. A step that cooks in the background
 * is started by the main button; any other by « Démarrer », and once going it takes −1 / Pause / +1.
 */
@Composable
internal fun StepTimerCard(state: CookingUiState, step: RecipeStep, actions: CookingActions, buttonHeight: Dp) {
    val minutes = step.durationMinutes ?: return
    val timer = state.timerOf(step.position)
    val duration = timer?.durationSeconds ?: (minutes * 60)
    val remaining = timer?.let(state::remainingOf) ?: duration
    val counting = timer?.status?.isCounting == true
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.COOKING_STEP_TIMER),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TimerRing(fractionOf(remaining, duration), 84.dp, 7.dp) {
                    Text(
                        clockOf(remaining),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag(UiTags.COOKING_STEP_TIMER_CLOCK),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(timer?.label ?: timerLabel(step.text, step.position), style = MaterialTheme.typography.titleMedium)
                    Text(
                        timerSubLine(timer, step.background),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(UiTags.COOKING_STEP_TIMER_SUB),
                    )
                }
            }
            if (counting || (timer == null && !step.background)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = { actions.addMinutes(-1) },
                        enabled = counting,
                        modifier = Modifier.heightIn(min = buttonHeight).semantics { contentDescription = "Retirer une minute" }
                            .testTag(UiTags.COOKING_TIMER_MINUS),
                    ) { Text("−1") }
                    Button(
                        onClick = actions.toggleTimer,
                        enabled = !state.starting,
                        modifier = Modifier.weight(1f).heightIn(min = buttonHeight).testTag(UiTags.COOKING_TIMER_TOGGLE),
                    ) {
                        Text(
                            when (timer?.status) {
                                CookingTimerStatus.RUNNING -> "Pause"
                                CookingTimerStatus.PAUSED -> "Reprendre"
                                else -> "Démarrer"
                            },
                        )
                    }
                    FilledTonalButton(
                        onClick = { actions.addMinutes(1) },
                        enabled = counting,
                        modifier = Modifier.heightIn(min = buttonHeight).semantics { contentDescription = "Ajouter une minute" }
                            .testTag(UiTags.COOKING_TIMER_PLUS),
                    ) { Text("+1") }
                }
            }
        }
    }
}

/** « Pâtes : c'est prêt · minuteur de l'étape 3 · OK », over the step, one timer at a time. */
@Composable
internal fun TimerAlert(timer: CookingTimer, onOk: () -> Unit, buttonHeight: Dp, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        border = pulseBorder(true),
        shadowElevation = 12.dp,
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 12.dp).testTag(UiTags.COOKING_TIMER_ALERT),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(doneTitle(timer), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(UiTags.COOKING_TIMER_ALERT_TITLE))
                Text("minuteur de l'étape ${timer.stepPosition}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Button(
                onClick = onOk,
                modifier = Modifier.heightIn(min = buttonHeight).testTag(UiTags.COOKING_TIMER_ALERT_OK),
            ) { Text("OK") }
        }
    }
}
