package com.maggie.app.ui.screens.health.sport

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityImmersion
import com.maggie.app.ui.activity.ActivityScaffold
import com.maggie.app.ui.activity.rememberWindowImmersion
import com.maggie.app.ui.interruption.rememberReducedMotion
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.screens.health.formatDose
import com.maggie.app.ui.theme.ModuleHue

private val Coral = MaggieTokens.Module.sport.second
private val CardColor = MaggieTokens.surfaceDark.paper
private val CellColor = MaggieTokens.surfaceDark.raised
private val LabelColor = MaggieTokens.surfaceDark.textMuted
private val CaptionColor = MaggieTokens.surfaceDark.caption
private val InkOnCoral = MaggieTokens.surfaceDark.background

private val FEELINGS = listOf("too_easy" to "Trop facile", "right" to "Bien", "too_hard" to "Trop dur")

/** « Séance tenue · 32 min · 18 séries ». */
internal fun endSummary(minutes: Int, sets: Int): String =
    "Séance tenue · $minutes min · $sets ${if (sets == 1) "série" else "séries"}"

/** « 7 séances d'affilée »; the number is shown in coral by the screen. */
internal fun streakLabel(streak: Int): String = if (streak == 1) "séance d'affilée" else "séances d'affilée"

/** The end of the Sport activity (MAG-781): `maggie://health/sport/activity-end`, reached after the last set. */
@Composable
fun SportActivityEndScreen(viewModel: SportEndViewModel, onQuit: () -> Unit, onSaved: () -> Unit, onOpenMaggie: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    LaunchedEffect(state.load) { if (state.load == SportEndLoad.GONE) onQuit() }
    SportActivityEndContent(
        state = state,
        actions = SportEndActions(
            onDifficulty = viewModel::chooseDifficulty,
            onFeeling = viewModel::chooseFeeling,
            onSave = viewModel::save,
            onRetry = viewModel::retry,
        ),
        onQuit = { if (state.load == SportEndLoad.READY) viewModel.askQuit() else onQuit() },
        onConfirmQuit = {
            viewModel.dismissQuit()
            onQuit()
        },
        onDismissQuit = viewModel::dismissQuit,
        onOpenMaggie = onOpenMaggie,
    )
}

data class SportEndActions(
    val onDifficulty: (Int) -> Unit = {},
    val onFeeling: (Int, String) -> Unit = { _, _ -> },
    val onSave: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

@Composable
fun SportActivityEndContent(
    state: SportEndUiState,
    actions: SportEndActions = SportEndActions(),
    onQuit: () -> Unit,
    onConfirmQuit: () -> Unit,
    onDismissQuit: () -> Unit,
    onOpenMaggie: () -> Unit,
    immersion: ActivityImmersion = rememberWindowImmersion(),
) {
    ActivityScaffold(
        name = "la séance",
        hue = ModuleHue.HEALTH,
        onQuit = onQuit,
        onOpenMaggie = onOpenMaggie,
        immersion = immersion,
        progress = { EndHeader(state) },
        quitFirst = true,
    ) {
        when (state.load) {
            SportEndLoad.LOADING, SportEndLoad.GONE -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.testTag(UiTags.SPORT_END_LOADING))
            }
            SportEndLoad.ERROR -> Column(
                Modifier.fillMaxSize().testTag(UiTags.SPORT_END_ERROR),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("La séance n'a pas pu être enregistrée", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = actions.onRetry, modifier = Modifier.testTag(UiTags.SPORT_END_RETRY)) { Text("Réessayer") }
            }
            SportEndLoad.READY -> EndBody(state, actions)
        }
        if (state.quitAsked) QuitDialog(expanded = false, onConfirm = onConfirmQuit, onDismiss = onDismissQuit)
    }
}

/** « Terminé » in place of the counter, every segment coral. */
@Composable
private fun EndHeader(state: SportEndUiState) {
    val planned = state.planned ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(planned.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            Text("Terminé", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(planned.exercises.size) { Box(Modifier.weight(1f).height(5.dp).background(Coral, RoundedCornerShape(3.dp))) }
        }
    }
}

/** A centred column of 480 dp at most: the phone's screen, as is on a tablet. */
@Composable
private fun EndBody(state: SportEndUiState, actions: SportEndActions) {
    Box(Modifier.fillMaxSize().testTag(UiTags.SPORT_ACTIVITY_END), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Medal()
                Text(
                    endSummary(state.minutes, state.setCount).uppercase(),
                    color = Coral,
                    fontSize = 13.sp,
                    letterSpacing = 0.14.em,
                    modifier = Modifier.semantics { contentDescription = endSummary(state.minutes, state.setCount) }.testTag(UiTags.SPORT_END_SUMMARY),
                )
                Text(
                    if (state.soft) "Faite, et en version douce : la série tient." else "Tenue. La semaine prend forme.",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp).testTag(UiTags.SPORT_END_TITLE),
                )
                state.streak?.let { Streak(it) }
                DifficultyCard(state, actions)
                ExercisesCard(state, actions)
                Spacer(Modifier.height(4.dp))
            }
            if (state.saveFailed) {
                Text(
                    "L'enregistrement a échoué",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(UiTags.SPORT_END_SAVE_ERROR),
                )
            }
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(56.dp)
                    .background(MaggieTokens.surfaceDark.text, CircleShape)
                    .clickable(enabled = !state.saving, role = Role.Button, onClick = actions.onSave)
                    .testTag(UiTags.SPORT_END_SAVE),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.saveFailed) "Réessayer" else "Enregistrer",
                    color = MaggieTokens.Night.background,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** The medallion: 104 dp coral, a check, a halo of 12 dp; it pops, sparks fly — nothing moves with « Supprimer les animations ». */
@Composable
private fun Medal() {
    val reduced = rememberReducedMotion()
    val scale = remember { Animatable(if (reduced) 1f else 0.6f) }
    val sparks = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        sparks.animateTo(1f, tween(700))
    }
    Box(Modifier.padding(top = 20.dp).size(176.dp).testTag(UiTags.SPORT_END_MEDAL), contentAlignment = Alignment.Center) {
        if (!reduced && sparks.value < 1f) {
            Canvas(Modifier.fillMaxSize()) {
                val progress = sparks.value
                repeat(8) { i ->
                    val angle = Math.toRadians(i * 45.0 + 22.5)
                    val distance = size.minDimension / 2 * (0.62f + 0.38f * progress)
                    val center = Offset(size.width / 2 + (Math.cos(angle) * distance).toFloat(), size.height / 2 + (Math.sin(angle) * distance).toFloat())
                    drawCircle(Coral.copy(alpha = 1f - progress), radius = 4.dp.toPx(), center = center)
                }
            }
        }
        Box(Modifier.scale(scale.value).size(128.dp).background(Coral.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(104.dp).background(Coral, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaggieTokens.Night.background, modifier = Modifier.size(56.dp))
            }
        }
    }
}

/** One pastille per session kept in a row, at most seven — the last one full — and « 7 séances d'affilée ». */
@Composable
private fun Streak(streak: Int) {
    Column(Modifier.testTag(UiTags.SPORT_END_STREAK), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val shown = minOf(streak, 7)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(shown) { i ->
                Box(Modifier.size(30.dp, 10.dp).background(if (i == shown - 1) Coral else Coral.copy(alpha = 0.45f), RoundedCornerShape(5.dp)))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = Coral, modifier = Modifier.size(18.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Coral, fontWeight = FontWeight.Medium)) { append("$streak") }
                    append(" ${streakLabel(streak)}")
                },
                fontSize = 16.sp,
                modifier = Modifier.testTag(UiTags.SPORT_END_STREAK_TEXT),
            )
        }
    }
}

@Composable
private fun DifficultyCard(state: SportEndUiState, actions: SportEndActions) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(CardColor, RoundedCornerShape(22.dp)).padding(16.dp).testTag(UiTags.SPORT_END_DIFFICULTY),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Difficulté de la séance", color = LabelColor, fontSize = 14.sp, modifier = Modifier.weight(1f))
            state.difficulty?.let { Text("$it / 10", fontSize = 15.sp, modifier = Modifier.testTag(UiTags.SPORT_END_DIFFICULTY_VALUE)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (1..10).forEach { n ->
                val chosen = state.difficulty == n
                Box(
                    Modifier.weight(1f).height(44.dp).background(if (chosen) Coral else CellColor, RoundedCornerShape(10.dp))
                        .semantics { selected = chosen; role = Role.RadioButton }
                        .clickable(onClick = { actions.onDifficulty(n) })
                        .testTag("${UiTags.SPORT_END_DIFFICULTY_CELL_PREFIX}$n"),
                    contentAlignment = Alignment.Center,
                ) { Text("$n", fontSize = 15.sp, color = if (chosen) InkOnCoral else LabelColor) }
            }
        }
    }
}

@Composable
private fun ExercisesCard(state: SportEndUiState, actions: SportEndActions) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(CardColor, RoundedCornerShape(22.dp)).padding(16.dp).testTag(UiTags.SPORT_END_EXERCISES),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Par exercice", color = LabelColor, fontSize = 14.sp)
        state.rows.forEachIndexed { index, row ->
            Column(Modifier.testTag("${UiTags.SPORT_END_ROW_PREFIX}$index"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.exercise.name, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        formatDose(row.exercise),
                        color = CaptionColor,
                        fontSize = 13.sp,
                        modifier = Modifier.testTag("${UiTags.SPORT_END_DOSE_PREFIX}$index"),
                    )
                    row.delta?.let {
                        Text(it.text, color = if (it.same) CaptionColor else Coral, fontSize = 13.sp, modifier = Modifier.testTag("${UiTags.SPORT_END_DELTA_PREFIX}$index"))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FEELINGS.forEach { (key, label) ->
                        val chosen = row.feeling == key
                        Box(
                            Modifier.weight(1f).height(44.dp)
                                .background(if (chosen) Coral.copy(alpha = 0.18f) else CellColor, RoundedCornerShape(22.dp))
                                .semantics { selected = chosen; role = Role.RadioButton }
                                .clickable(onClick = { actions.onFeeling(index, key) })
                                .testTag("${UiTags.SPORT_END_FEELING_PREFIX}${index}_$key"),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, fontSize = 14.sp, color = if (chosen) Coral else LabelColor) }
                    }
                }
            }
        }
    }
}
