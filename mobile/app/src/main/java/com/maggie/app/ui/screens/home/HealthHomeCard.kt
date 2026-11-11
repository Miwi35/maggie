package com.maggie.app.ui.screens.home

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
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
 * the need and the eating window — bars and a ring, no verdict. A day with none of it draws no card (MAG-594). A tap
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
    state.workout?.let { HealthHomeWorkoutRow(it) }
    state.steps?.let { HealthHomeStepsRow(it) }
    state.balance?.let { HealthHomeBalanceRow(it, state.today) }
    state.fast?.let { HealthHomeFastRow(it, state.today) }
}

@Composable
private fun HealthHomeWorkoutRow(workout: HealthHomeWorkout) {
    Text(
        text = "${workout.title} · ${workout.time} · ${homeDuration(workout.durationMinutes)}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(UiTags.HOME_HEALTH_WORKOUT),
    )
}

@Composable
private fun HealthHomeStepsRow(steps: HealthHomeSteps) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(UiTags.HOME_HEALTH_STEPS)) {
        Text(
            text = "Pas · ${formatCount(steps.count)} · objectif ${formatCount(steps.goal)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        HealthBar(steps.count.toFloat() / steps.goal)
    }
}

@Composable
private fun HealthHomeBalanceRow(balance: HealthHomeBalance, today: Boolean) {
    val need = balance.needKcal?.let { " · besoin ${formatCount(it)} kcal" }.orEmpty()
    val text = if (today) "Bilan du jour · ${formatCount(balance.kcal)} mangées$need" else "Calories projetées · ${formatCount(balance.kcal)} kcal$need"
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(UiTags.HOME_HEALTH_BALANCE)) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
        balance.needKcal?.takeIf { it > 0 }?.let { HealthBar(balance.kcal.toFloat() / it) }
    }
}

@Composable
private fun HealthHomeFastRow(fast: HealthHomeFast, today: Boolean) {
    val left = fast.minutesToEdge?.let { " · ${homeDuration(it)}" }.orEmpty()
    val text = when {
        !today -> "Fenêtre prévue · ${fast.windowStart} – ${fast.windowEnd}"
        fast.state == FastingStatus.STATE_CLOSED -> "Fenêtre fermée"
        fast.state == FastingStatus.STATE_OPEN -> "Fenêtre ouverte · ferme à ${fast.windowEnd}$left"
        else -> "Jeûne en cours · ouvre à ${fast.windowStart}$left"
    }
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(UiTags.HOME_HEALTH_FAST),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        fast.minutesToEdge?.let { minutes ->
            CircularProgressIndicator(
                progress = { (minutes.toFloat() / windowMinutes(fast)).coerceIn(0f, 1f) },
                modifier = Modifier.size(28.dp),
                strokeWidth = 4.dp,
                color = homeModuleColor(HomeModules.HEALTH),
            )
        }
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HealthBar(fraction: Float) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
        color = homeModuleColor(HomeModules.HEALTH),
    )
}

private fun windowMinutes(fast: HealthHomeFast): Int {
    fun minutes(time: String) = time.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
    val length = minutes(fast.windowEnd) - minutes(fast.windowStart)
    return if (length <= 0) length + 24 * 60 else length
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
