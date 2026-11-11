package com.maggie.app.ui.screens.home

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.FastingStatus
import com.maggie.app.data.model.HealthHomeBalance
import com.maggie.app.data.model.HealthHomeFast
import com.maggie.app.data.model.HealthHomeState
import com.maggie.app.data.model.HealthHomeSteps
import com.maggie.app.data.model.HealthHomeWorkout
import com.maggie.app.data.model.HomeDay
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.screens.health.formatCount
import org.koin.androidx.compose.koinViewModel

/**
 * The Santé card of the Accueil (MAG-566): the planned session, the steps against the goal, the day's calories against
 * the need and the eating window — lines with a bar, no verdict. A day with none of it draws no card (MAG-594). A tap
 * anywhere opens Santé.
 */
@Composable
fun HealthHomeCard(
    day: HomeDay,
    viewModel: HealthHomeViewModel = koinViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val open = LocalHomeNavigation.current
    LaunchedEffect(day) { viewModel.selectDay(day) }
    HealthHomeCardContent(ui = ui, onOpen = { open(Screen.HealthSuivi.route) }, onRetry = viewModel::refresh)
}

@Composable
internal fun HealthHomeCardContent(ui: HealthHomeUiState, onOpen: () -> Unit, onRetry: () -> Unit) {
    ReportHomeCard(HomeModules.HEALTH, ui.status)
    if (ui.status == HomeCardStatus.EMPTY) return

    HomeCard(title = "Santé", module = HomeModules.HEALTH, onOpen = onOpen) {
        val state = ui.state
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(onOpen) { detectTapGestures { onOpen() } },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                state != null && !state.isEmpty -> HealthHomeBody(state)
                ui.failed && !ui.isLoading -> Unit
                else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (ui.failed && !ui.isLoading) HealthHomeError(onRetry)
    }
}

@Composable
private fun HealthHomeBody(state: HealthHomeState) {
    val rows = listOfNotNull<@Composable () -> Unit>(
        state.workout?.let { { HealthHomeWorkoutRow(it) } },
        state.steps?.let { { HealthHomeStepsRow(it) } },
        state.balance?.let { { HealthHomeBalanceRow(it, state.today) } },
        state.fast?.let { { HealthHomeFastRow(it, state.today) } },
    )
    rows.forEachIndexed { index, row ->
        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        row()
    }
}

@Composable
private fun HealthHomeWorkoutRow(workout: HealthHomeWorkout) {
    HealthLine(
        label = workout.title,
        value = "${workout.time} · ${homeDuration(workout.durationMinutes)}",
        fraction = null,
        tag = UiTags.HOME_HEALTH_WORKOUT,
    )
}

@Composable
private fun HealthHomeStepsRow(steps: HealthHomeSteps) {
    HealthLine(
        label = "Pas",
        value = "${formatCount(steps.count)} · objectif ${formatCount(steps.goal)}",
        fraction = steps.count.toFloat() / steps.goal,
        tag = UiTags.HOME_HEALTH_STEPS,
    )
}

@Composable
private fun HealthHomeBalanceRow(balance: HealthHomeBalance, today: Boolean) {
    val need = balance.needKcal?.let { " · besoin ${formatCount(it)} kcal" }.orEmpty()
    HealthLine(
        label = if (today) "Bilan du jour" else "Calories projetées",
        value = if (today) "${formatCount(balance.kcal)} mangées$need" else "${formatCount(balance.kcal)} kcal$need",
        fraction = balance.needKcal?.takeIf { it > 0 }?.let { balance.kcal.toFloat() / it },
        tag = UiTags.HOME_HEALTH_BALANCE,
    )
}

@Composable
private fun HealthHomeFastRow(fast: HealthHomeFast, today: Boolean) {
    val left = fast.minutesToEdge?.let { " · ${homeDuration(it)}" }.orEmpty()
    val (label, value) = when {
        !today -> "Fenêtre prévue" to "${fast.windowStart} – ${fast.windowEnd}"
        fast.state == FastingStatus.STATE_CLOSED -> "Fenêtre fermée" to ""
        fast.state == FastingStatus.STATE_OPEN -> "Fenêtre ouverte" to "ferme à ${fast.windowEnd}$left"
        else -> "Jeûne en cours" to "ouvre à ${fast.windowStart}$left"
    }
    HealthLine(label = label, value = value, fraction = fast.progress, tag = UiTags.HOME_HEALTH_FAST)
}

/** The mockup's line: the label on the left, its grey value on the right, a 6 dp bar under it when there is a share. */
@Composable
private fun HealthLine(label: String, value: String, fraction: Float?, tag: String) {
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(tag),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (value.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        fraction?.let { HealthBar(it) }
    }
}

@Composable
private fun HealthBar(fraction: Float) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(6.dp),
        color = homeModuleColor(HomeModules.HEALTH),
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** « 45 min », « 3 h 18 ». */
internal fun homeDuration(minutes: Int): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${"%02d".format(minutes % 60)}"

@Composable
private fun HealthHomeError(onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.HOME_HEALTH_ERROR),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Impossible de lire la santé.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).padding(top = 12.dp),
        )
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.HOME_HEALTH_RETRY)) {
            Text("Réessayer")
        }
    }
}
