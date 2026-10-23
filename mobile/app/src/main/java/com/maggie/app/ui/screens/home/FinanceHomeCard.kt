package com.maggie.app.ui.screens.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.FinanceHomeProject
import com.maggie.app.data.model.FinanceHomeRing
import com.maggie.app.data.model.FinanceHomeState
import com.maggie.app.data.model.toQualifyLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.NO_ENVELOPE_TEXT
import com.maggie.app.ui.components.StatusLabel
import com.maggie.app.ui.components.hasEnvelope
import com.maggie.app.ui.components.rhythmWord
import com.maggie.app.ui.components.ringAmount
import com.maggie.app.ui.components.ringFill
import com.maggie.app.ui.components.statusColor
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.screens.finance.purchaseLabel
import org.koin.androidx.compose.koinViewModel

/**
 * The Finance card of the Accueil (MAG-565): the week and the month as two rings with their rhythm
 * status, the lines still to qualify, and the next project. A tap anywhere opens Finance › Dashboard.
 * The card does not follow « Demain »: the figures are the same whatever the day.
 */
@Composable
fun FinanceHomeCard(viewModel: FinanceHomeViewModel = koinViewModel()) {
    val ui by viewModel.uiState.collectAsState()
    val open = LocalHomeNavigation.current
    FinanceHomeCardContent(ui = ui, onOpen = { open(Screen.FinanceDashboard.route) }, onRetry = viewModel::refresh)
}

@Composable
internal fun FinanceHomeCardContent(ui: FinanceHomeUiState, onOpen: () -> Unit, onRetry: () -> Unit) {
    HomeCard(title = "Finance", module = HomeModules.FINANCE, onOpen = onOpen) {
        val state = ui.state
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(onOpen) { detectTapGestures { onOpen() } },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state != null -> FinanceHomeBody(state, onOpen)
                ui.failed && !ui.isLoading -> Unit
                else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (ui.failed && !ui.isLoading) FinanceHomeError(onRetry)
    }
}

@Composable
private fun FinanceHomeBody(state: FinanceHomeState, onOpen: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        FinanceHomeRingView("Semaine", "week", state.week)
        FinanceHomeRingView("Mois", "month", state.month)
    }
    if (state.toQualifyCount > 0) {
        AssistChip(
            onClick = onOpen,
            label = { Text(state.toQualifyLabel(), style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.testTag(UiTags.HOME_FINANCE_TO_QUALIFY),
        )
    }
    state.nextProject?.let { FinanceHomeNextProject(it) }
}

@Composable
private fun FinanceHomeRingView(title: String, period: String, ring: FinanceHomeRing) {
    val rhythm = ring.toRhythm()
    val status = if (ring.status == "above") "orange" else "green"
    val color = statusColor(status)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = ringFill(rhythm)
    val word = rhythmWord(rhythm)
    val description = if (hasEnvelope(rhythm)) {
        "$title : $word, ${ringAmount(ring.remainingCents)} restants sur ${ringAmount(ring.budgetCents)}"
    } else {
        "$title : $NO_ENVELOPE_TEXT"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(4.dp)
            .testTag(UiTags.homeFinanceRing(period))
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Box(modifier = Modifier.padding(vertical = 6.dp).size(RING_SIZE), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(RING_SIZE)) {
                val stroke = RING_STROKE.toPx()
                val topLeft = Offset(stroke / 2, stroke / 2)
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(track, -90f, 360f, false, topLeft, arc, style = Stroke(stroke))
                if (fill > 0f) {
                    drawArc(color, -90f, 360f * fill, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Butt))
                }
            }
            if (hasEnvelope(rhythm)) {
                Text(
                    text = ringAmount(ring.remainingCents),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (ring.remainingCents < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = RING_STROKE + 2.dp),
                )
            } else {
                Text(
                    text = NO_ENVELOPE_TEXT,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = RING_STROKE + 2.dp),
                )
            }
        }
        if (hasEnvelope(rhythm)) StatusLabel(status, word)
    }
}

@Composable
private fun FinanceHomeNextProject(project: FinanceHomeProject) {
    Column(modifier = Modifier.fillMaxWidth().testTag(UiTags.HOME_FINANCE_NEXT_PROJECT)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = project.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = purchaseLabel(project.purchaseMonth),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LinearProgressIndicator(
            progress = { (project.gatheredCents.toFloat() / project.amountCents.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Text(
            text = "${ringAmount(project.gatheredCents)} sur ${ringAmount(project.amountCents)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun FinanceHomeError(onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.HOME_FINANCE_ERROR),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Impossible de lire les finances.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp),
        )
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.HOME_FINANCE_RETRY)) {
            Text("Réessayer")
        }
    }
}

private val RING_SIZE = 104.dp
private val RING_STROKE = 10.dp
