package com.maggie.app.ui.screens.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.DayTask
import com.maggie.app.data.reward.Celebration
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.interruption.rememberReducedMotion
import com.maggie.app.ui.screens.shared.minutesLabel
import com.maggie.app.ui.theme.MaggieTokens
import kotlinx.coroutines.delay

/** « Suivante : Appeler le garage · 10 min »; the duration only when the task has one. */
internal fun nextLabelOf(task: DayTask): String =
    "Suivante : ${task.title}" + (task.estimatedMinutes?.let { " · ${minutesLabel(it)}" } ?: "")

/** « 3 sur 5 aujourd'hui ». */
internal fun dayBarLabelOf(done: Int, total: Int): String = "${ringLabelOf(done, total)} aujourd'hui"

/** How long the banner stays when nobody touches it. */
private const val BANNER_MS = 4_000L

/**
 * The Bravo (MAG-508), after a task of the day ticked from « Mes 5 », « Maintenant » or the Focus:
 * a green circle that appears, small stars, « {titre} · faite », the reward's message, the day's bar,
 * « Suivante » (the Focus of the next task) and « Pause méritée » (back to the day). Nothing moves
 * when the user turned the animation off or the system removes animations.
 */
@Composable
fun BravoScreen(
    viewModel: BravoViewModel,
    onNext: (taskId: String) -> Unit,
    onPause: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val reducedMotion = rememberReducedMotion()

    LaunchedEffect(state.exit) {
        when (val exit = state.exit) {
            is BravoExit.Next -> onNext(exit.taskId)
            BravoExit.Day -> onPause()
            null -> Unit
        }
    }
    BackHandler(onBack = viewModel::pause)

    BravoContent(
        state = state,
        animate = !reducedMotion && state.celebration?.animate != false,
        onNext = viewModel::next,
        onPause = viewModel::pause,
    )
}

@Composable
internal fun BravoContent(
    state: BravoUiState,
    animate: Boolean,
    onNext: () -> Unit,
    onPause: () -> Unit,
) {
    val celebration = state.celebration
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
            .testTag(UiTags.BRAVO),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The circle waits for the settings, so it does not move for a user who turned it off.
                if (!state.isLoading) DoneCircle(animate = animate)
                Text(
                    text = "${state.title} · faite",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(UiTags.BRAVO_TITLE),
                )
                celebration?.let {
                    Text(
                        text = it.reward.message,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag(UiTags.BRAVO_MESSAGE),
                    )
                }
                state.day?.takeIf { it.total > 0 }?.let { day -> DayBar(done = day.done, total = day.total) }
                state.next?.let { next ->
                    Button(
                        onClick = onNext,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(UiTags.BRAVO_NEXT),
                    ) {
                        Text(nextLabelOf(next), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    }
                }
                TextButton(
                    onClick = onPause,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(UiTags.BRAVO_PAUSE),
                ) { Text("Pause méritée", style = MaterialTheme.typography.titleMedium) }
            }
        }
        if (celebration?.reward?.isShootingStar == true) ShootingStar(animate = animate)
    }
}

private val starPlaces = listOf(
    // x, y in dp from the circle's centre, and the star's size.
    Triple(-78f, -52f, 18f),
    Triple(80f, -60f, 14f),
    Triple(-92f, 34f, 12f),
    Triple(88f, 40f, 18f),
    Triple(4f, -96f, 12f),
)

/** The green circle and its check, popping in with a spring, then the small stars around it. */
@Composable
private fun DoneCircle(animate: Boolean) {
    val green = if (isSystemInDarkTheme()) MaggieTokens.feedbackDark.success else MaggieTokens.feedbackLight.success
    val scale = remember { Animatable(if (animate) 0f else 1f) }
    val stars = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (!animate) {
            scale.snapTo(1f)
            stars.snapTo(1f)
            return@LaunchedEffect
        }
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        stars.animateTo(1f, tween(durationMillis = 400))
    }
    Box(modifier = Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        starPlaces.forEach { (x, y, size) ->
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = MaggieTokens.Signal.warning,
                modifier = Modifier
                    .offset(x.dp * stars.value, y.dp * stars.value)
                    .size(size.dp)
                    .alpha(stars.value),
            )
        }
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(scale.value)
                .background(green, CircleShape)
                .testTag(UiTags.BRAVO_CIRCLE)
                .semantics { contentDescription = "Faite" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
        }
    }
}

/** [total] segments, [done] of them filled, and the words: the count has its picture. */
@Composable
private fun DayBar(done: Int, total: Int) {
    val filled = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.BRAVO_DAY_BAR),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(total) { segment ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .background(if (segment < done) filled else empty, RoundedCornerShape(5.dp)),
                )
            }
        }
        Text(text = dayBarLabelOf(done, total), style = MaterialTheme.typography.titleMedium)
    }
}

/** The étoile filante: a star crossing the screen from the top left; still, at the top, when nothing may move. */
@Composable
private fun ShootingStar(animate: Boolean) {
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (animate) progress.animateTo(1f, tween(durationMillis = 1_400)) else progress.snapTo(1f)
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val modifier = if (animate) {
            Modifier.offset(
                x = (-48).dp + (maxWidth + 96.dp) * progress.value,
                y = maxHeight * (0.05f + 0.25f * progress.value),
            )
        } else {
            Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
        }
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = "Étoile filante",
            tint = MaggieTokens.Signal.warning,
            modifier = modifier.size(40.dp).rotate(-20f).testTag(UiTags.BRAVO_SHOOTING_STAR),
        )
    }
}

/**
 * A task ticked out of the day (À venir, the lists): the same reward as a short banner, the
 * étoile filante with its own star. It goes by itself, or at a touch.
 */
@Composable
internal fun RewardBanner(celebration: Celebration, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(celebration) {
        delay(BANNER_MS)
        onDismiss()
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onDismiss)
            .testTag(UiTags.TASKS_REWARD_BANNER),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (celebration.reward.isShootingStar) Icons.Default.AutoAwesome else Icons.Default.Star,
                contentDescription = if (celebration.reward.isShootingStar) "Étoile filante" else "Étoile",
                tint = MaggieTokens.Signal.warning,
            )
            Text(
                text = celebration.reward.message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
