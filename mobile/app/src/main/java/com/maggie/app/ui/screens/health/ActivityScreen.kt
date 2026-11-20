package com.maggie.app.ui.screens.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.Workout
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.components.ModuleBreadcrumb
import com.maggie.app.ui.layout.WindowWidth
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.navigation.HealthSection
import com.maggie.app.ui.navigation.LocalModuleBar
import com.maggie.app.ui.theme.maggieSurface
import java.time.LocalTime
import kotlin.math.max

private val PHONE_RING = 112.dp
private val TABLET_RING = 168.dp

/**
 * « Santé › Activité » (MAG-898): « Pas du jour » with its ring and its projection, « Sessions actives », « 7 jours ».
 * On a tablet two columns: the steps then the week on the left, the sessions on the right. « Relier » opens
 * Configuration, where Health Connect is linked; « Sport › » opens Sport.
 */
@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel,
    onSelectSection: (HealthSection) -> Unit = {},
) {
    val expanded = rememberAppLayout().width == WindowWidth.EXPANDED
    val bar = LocalModuleBar.current
    Scaffold(
        topBar = {
            ModuleBreadcrumb(
                module = "Santé",
                sections = HealthSection.entries.map { it.breadcrumb },
                currentKey = HealthSection.ACTIVITY.key,
                onSelectSection = { picked ->
                    HealthSection.byKey(picked.key)?.takeIf { it != HealthSection.ACTIVITY }?.let(onSelectSection)
                },
                onOpenDrawer = bar.onOpenDrawer,
                actions = { bar.actions(this) },
            )
        },
    ) { paddingValues ->
        ActivityContent(viewModel, expanded, onSelectSection, Modifier.padding(paddingValues))
    }
}

@Composable
private fun ActivityContent(
    viewModel: ActivityViewModel,
    expanded: Boolean,
    onSelectSection: (HealthSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbar(
        error = state.error.takeIf { state.hasLoaded },
        snackbarHostState = snackbarHostState,
        onDismiss = {},
        onRetry = null,
        message = state.error.orEmpty(),
    )
    val link = { onSelectSection(HealthSection.CONFIGURATION) }
    val sport = { onSelectSection(HealthSection.SPORT) }

    Box(modifier = modifier.fillMaxSize().testTag(UiTags.ACTIVITY)) {
        when {
            !state.hasLoaded && state.error == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            !state.hasLoaded -> EmptyState(
                modifier = Modifier.testTag(UiTags.ACTIVITY_EMPTY),
                title = "Activité indisponible",
                description = state.error.orEmpty(),
                actionLabel = "Réessayer",
                onAction = viewModel::refresh,
            )
            expanded -> Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    StepsCard(state, TABLET_RING, onLink = link)
                    WeekCard(state)
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    SessionsCard(state, viewModel, onOpenSport = sport)
                }
            }
            else -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StepsCard(state, PHONE_RING, onLink = link)
                SessionsCard(state, viewModel, onOpenSport = sport)
                WeekCard(state)
            }
        }
    }
}

/** « 12 h 40 », « 20 h » on the hour. */
internal fun clockWords(time: LocalTime): String =
    if (time.minute == 0) "${time.hour} h" else "${time.hour} h ${time.minute.toString().padStart(2, '0')}"

private fun windowEndWords(windowEnd: String): String =
    runCatching { clockWords(LocalTime.parse(windowEnd)) }.getOrDefault(windowEnd)

@Composable
private fun StepsCard(state: ActivityUiState, ring: Dp, onLink: () -> Unit, modifier: Modifier = Modifier) {
    val surface = maggieSurface()
    MaggieCard(modifier = modifier.fillMaxWidth().semantics { contentDescription = "Pas du jour" }.testTag(UiTags.ACTIVITY_STEPS)) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Pas du jour", fontSize = 14.sp, color = surface.textMuted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StepsRing(state, ring)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.noStepSource) {
                        Text("Aucune source de pas", fontSize = 14.sp, modifier = Modifier.testTag(UiTags.ACTIVITY_NO_SOURCE))
                        TextButton(onClick = onLink, modifier = Modifier.testTag(UiTags.ACTIVITY_LINK)) { Text("Relier") }
                    } else {
                        Text("Pas · aujourd’hui à ${clockWords(state.readAt)}", fontSize = 13.sp, color = surface.textMuted)
                        Text(
                            "À ce rythme : ${state.projection?.let { "~${formatCount(it)}" } ?: "—"} / ${formatCount(state.goal)} à ${windowEndWords(state.windowEnd)}",
                            fontSize = 14.sp,
                            modifier = Modifier.testTag(UiTags.ACTIVITY_PROJECTION),
                        )
                        state.gap?.let {
                            Text(
                                "Il manquerait ~${formatCount(it)} pas",
                                fontSize = 13.sp,
                                color = surface.textMuted,
                                modifier = Modifier.testTag(UiTags.ACTIVITY_GAP),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepsRing(state: ActivityUiState, size: Dp) {
    val surface = maggieSurface()
    val accent = MaterialTheme.colorScheme.primary
    val track = surface.caption.copy(alpha = 0.25f)
    val goal = max(state.goal, 1).toFloat()
    val done = (state.steps / goal).coerceIn(0f, 1f)
    val projected = ((state.projection ?: state.steps) / goal).coerceIn(0f, 1f)
    Box(modifier = Modifier.size(size).testTag(UiTags.ACTIVITY_RING), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.11f
            val inset = stroke / 2f
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            if (!state.noStepSource) {
                if (projected > done) {
                    drawArc(accent.copy(alpha = 0.3f), -90f, 360f * projected, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                if (done > 0f) {
                    drawArc(accent, -90f, 360f * done, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (state.noStepSource) "—" else formatCount(state.steps),
                fontSize = if (size > PHONE_RING) 30.sp else 22.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.testTag(UiTags.ACTIVITY_STEPS_VALUE),
            )
            Text("/ ${formatCount(state.goal)}", fontSize = 12.sp, color = surface.caption)
        }
    }
}

@Composable
private fun SessionsCard(state: ActivityUiState, viewModel: ActivityViewModel, onOpenSport: () -> Unit, modifier: Modifier = Modifier) {
    val surface = maggieSurface()
    val done = state.sessions.count { !it.isPlanned }
    val planned = state.sessions.count { it.isPlanned }
    MaggieCard(modifier = modifier.fillMaxWidth().semantics { contentDescription = "Sessions actives" }.testTag(UiTags.ACTIVITY_SESSIONS)) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Sessions actives", fontSize = 14.sp, color = surface.textMuted)
                Text(
                    "$done ${if (done > 1) "faites" else "faite"} · $planned ${if (planned > 1) "prévues" else "prévue"}",
                    fontSize = 12.sp,
                    color = surface.caption,
                )
            }
            if (state.sessions.isEmpty() && state.cited.isEmpty()) {
                Text("Aucune session aujourd’hui", fontSize = 14.sp, color = surface.textMuted, modifier = Modifier.testTag(UiTags.ACTIVITY_SESSIONS_EMPTY))
            }
            state.sessions.forEach { SessionRow(it, viewModel) }
            state.cited.forEach { workout ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSport).padding(vertical = 4.dp).testTag(UiTags.activityCited(workout.id)),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${startTime(workout, viewModel)} · Séance prévue · ${workout.title}",
                        fontSize = 13.sp,
                        color = surface.textMuted,
                        modifier = Modifier.weight(1f),
                    )
                    Text("Sport ›", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun startTime(workout: Workout, viewModel: ActivityViewModel): String =
    intakeTime(workout.startedAt, viewModel.zone)

@Composable
private fun SessionRow(workout: Workout, viewModel: ActivityViewModel) {
    val surface = maggieSurface()
    val planned = workout.isPlanned
    val kcal = workout.kcal?.let { if (planned) "~$it kcal" else "$it kcal" }
    val meta = listOfNotNull(formatWorkoutDuration(workout.durationMinutes), kcal).joinToString(" · ")
    Row(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.activitySession(workout.id)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${startTime(workout, viewModel)} · ${workout.title}",
                fontSize = 14.sp,
                color = if (planned) surface.textMuted else surface.text,
            )
            Text(meta, fontSize = 12.sp, color = surface.caption)
        }
        Text(
            if (planned) "Prévue" else "Fait",
            fontSize = 12.sp,
            color = if (planned) surface.caption else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun WeekCard(state: ActivityUiState, modifier: Modifier = Modifier) {
    val surface = maggieSurface()
    val accent = MaterialTheme.colorScheme.primary
    val week = state.week
    val scale = max(state.goal, week.maxOfOrNull { it.steps } ?: 0).coerceAtLeast(1) * 1.1f
    MaggieCard(modifier = modifier.fillMaxWidth().semantics { contentDescription = "7 jours" }.testTag(UiTags.ACTIVITY_WEEK)) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("7 jours", fontSize = 14.sp, color = surface.textMuted)
                Text("objectif ${formatCount(state.goal)}", fontSize = 12.sp, color = surface.caption)
            }
            Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
                if (week.isEmpty()) return@Canvas
                val slot = size.width / week.size
                val bar = slot * 0.5f
                week.forEachIndexed { i, day ->
                    val h = (day.steps / scale * size.height).coerceAtLeast(if (day.steps > 0) 2f else 0f)
                    drawRoundRect(
                        if (day.isToday) accent else accent.copy(alpha = 0.45f),
                        Offset(i * slot + (slot - bar) / 2f, size.height - h),
                        Size(bar, h),
                        CornerRadius(3f),
                    )
                }
                val y = size.height - state.goal / scale * size.height
                drawLine(
                    surface.caption,
                    Offset(0f, y),
                    Offset(size.width, y),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Text(day.label, fontSize = 11.sp, color = surface.caption, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
        }
    }
}
