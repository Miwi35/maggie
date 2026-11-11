package com.maggie.app.ui.screens.health.sport

import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.WorkoutExercise
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityImmersion
import com.maggie.app.ui.activity.ActivityScaffold
import com.maggie.app.ui.activity.rememberWindowImmersion
import com.maggie.app.ui.layout.WindowWidth
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.theme.ModuleHue
import com.maggie.app.ui.uiTagRoot

private val Coral = Color(0xFFFF8A80)
private val CueColor = Color(0xFFE4E0EC)
private val MutedColor = Color(0xFF8E899C)

/** « Échelon 4 », « Échelon 4 » only when the exercise has a step. */
internal fun stepLabel(exercise: WorkoutExercise): String? = exercise.step.takeIf { it > 0 }?.let { "Échelon $it" }

/** « 1 / 6 »: the 1-based exercise on screen. */
internal fun exerciseCounter(index: Int, count: Int): String = "${index + 1} / $count"

/** The Sport activity (MAG-779): the session's exercises, one at a time; `maggie://health/sport/activity/{id}`. */
@Composable
fun SportActivityScreen(viewModel: SportActivityViewModel, onQuit: () -> Unit, onOpenMaggie: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    SportActivityContent(
        state = state,
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

@Composable
fun SportActivityContent(
    state: SportActivityUiState,
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
                if (expanded) SportTablet(state) else SportPhone(state)
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
private fun SportPhone(state: SportActivityUiState) {
    val exercise = state.current ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
        ExerciseBlock(exercise, state.lastTimes[exercise.exercise], nameSize = 32, cueSize = 18)
    }
}

@Composable
private fun SportTablet(state: SportActivityUiState) {
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
        Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp)) {
            ExerciseBlock(exercise, state.lastTimes[exercise.exercise], nameSize = 56, cueSize = 26)
        }
    }
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
        containerColor = Color(0xFF1A1824),
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
