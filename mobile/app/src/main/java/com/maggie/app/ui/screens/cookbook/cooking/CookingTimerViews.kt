package com.maggie.app.ui.screens.cookbook.cooking

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.CookingTimerStatus
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.ui.UiTags

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

/** A rung timer's line pulses until it is touched. */
@Composable
internal fun pulseBorder(active: Boolean): BorderStroke? {
    if (!active) return null
    val pulse by rememberInfiniteTransition(label = "timer-pulse").animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "timer-pulse-alpha",
    )
    return BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = pulse))
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
