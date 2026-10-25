package com.maggie.app.ui.screens.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.FinanceHomeProject
import com.maggie.app.data.model.FinanceHomeRing
import com.maggie.app.data.model.FinanceHomeState
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.formatCentsRounded
import com.maggie.app.data.model.progressLabel
import com.maggie.app.data.model.rhythmStatus
import com.maggie.app.data.model.toQualifyLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.NO_ENVELOPE_TEXT
import com.maggie.app.ui.components.hasEnvelope
import com.maggie.app.ui.components.rhythmMarkStatus
import com.maggie.app.ui.components.rhythmWord
import com.maggie.app.ui.components.ringFill
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.screens.finance.ganttMonthLabel
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.maggieSurface
import org.koin.androidx.compose.koinViewModel

/**
 * The Finance card of the Accueil (MAG-565), as `accueil/Accueil-Tel` draws it: one compact row with the
 * week and the month as two small rings, one rhythm pill and the orange « n à qualifier » pill, then the
 * next project under a rule. A tap anywhere opens Finance › Dashboard.
 * The card does not follow « Demain »: the figures are the same whatever the day.
 * It is drawn as soon as there is an account or an envelope, not before (MAG-594).
 */
@Composable
fun FinanceHomeCard(viewModel: FinanceHomeViewModel = koinViewModel()) {
    val ui by viewModel.uiState.collectAsState()
    val open = LocalHomeNavigation.current
    FinanceHomeCardContent(ui = ui, onOpen = { open(Screen.FinanceDashboard.route) }, onRetry = viewModel::refresh)
}

@Composable
internal fun FinanceHomeCardContent(ui: FinanceHomeUiState, onOpen: () -> Unit, onRetry: () -> Unit) {
    ReportHomeCard(HomeModules.FINANCE, ui.status)
    if (ui.status == HomeCardStatus.EMPTY) return

    HomeCard(title = "Finance", module = HomeModules.FINANCE, onOpen = onOpen) {
        val state = ui.state
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(onOpen) { detectTapGestures { onOpen() } },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state != null && !state.isEmpty -> FinanceHomeBody(state, onOpen)
                ui.failed && !ui.isLoading -> Unit
                else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (ui.failed && !ui.isLoading) FinanceHomeError(onRetry)
    }
}

@Composable
private fun FinanceHomeBody(state: FinanceHomeState, onOpen: () -> Unit) {
    // One compact row, as the mock-up draws it: the two rings, then the rhythm and what waits to be qualified.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FinanceHomeRingView("Semaine", "week", state.week)
        FinanceHomeRingView("Mois", "month", state.month)
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.rhythmStatus()?.let { status ->
                val color = financeRhythmColor(status, isDark())
                FinanceHomePill(
                    text = if (status == "above") "Au-dessus du rythme" else "Dans le rythme",
                    color = color,
                    tag = UiTags.HOME_FINANCE_RHYTHM,
                    icon = if (status == "above") Icons.Filled.Warning else Icons.Filled.Check,
                )
            }
            if (state.toQualifyCount > 0) {
                FinanceHomePill(
                    text = state.toQualifyLabel(),
                    color = financeRhythmColor("above", isDark()),
                    tag = UiTags.HOME_FINANCE_TO_QUALIFY,
                    onClick = onOpen,
                )
            }
        }
    }
    state.nextProject?.let { FinanceHomeNextProject(it) }
}

@Composable
private fun isDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** The charter's rhythm colours: mint in the rhythm, orange above it — never the accent violet. */
internal fun financeRhythmColor(status: String, dark: Boolean): Color {
    val pair = if (status == "above") MaggieTokens.Rhythm.above else MaggieTokens.Rhythm.onTrack
    return if (dark) pair.second else pair.first
}

/** A tinted pill of the card: its colour at low opacity behind, the text in the colour itself. */
@Composable
private fun FinanceHomePill(text: String, color: Color, tag: String, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = PILL_TINT_ALPHA),
        contentColor = color,
        modifier = Modifier
            .testTag(tag)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(14.dp)) }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun FinanceHomeRingView(title: String, period: String, ring: FinanceHomeRing) {
    val rhythm = ring.toRhythm()
    val status = rhythmMarkStatus(rhythm)
    val color = if (status == "neutral") statusColor(status) else financeRhythmColor(ring.status, isDark())
    // The charter's track the arc runs on: what is left of the budget is drawn, what is spent is the track.
    val track = maggieSurface().track
    val fill = ringFill(rhythm)
    val description = if (hasEnvelope(rhythm)) {
        "$title : ${rhythmWord(rhythm)}, ${formatCents(ring.remainingCents)} restants sur ${formatCents(ring.budgetCents)}"
    } else {
        "$title : $NO_ENVELOPE_TEXT"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .testTag(UiTags.homeFinanceRing(period))
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Box(modifier = Modifier.size(RING_SIZE), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(RING_SIZE)) {
                val stroke = RING_STROKE.toPx()
                val topLeft = Offset(stroke / 2, stroke / 2)
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(track, -90f, 360f, false, topLeft, arc, style = Stroke(stroke))
                if (fill > 0f) {
                    drawArc(color, -90f, 360f * fill, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Text(
                text = if (hasEnvelope(rhythm)) formatCentsRounded(ring.remainingCents) else "—",
                style = MaterialTheme.typography.labelMedium,
                color = if (ring.remainingCents < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = RING_STROKE),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun FinanceHomeNextProject(project: FinanceHomeProject) {
    Column(modifier = Modifier.fillMaxWidth().testTag(UiTags.HOME_FINANCE_NEXT_PROJECT)) {
        HorizontalDivider(modifier = Modifier.padding(bottom = 10.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = project.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = project.progressLabel(::ganttMonthLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LinearProgressIndicator(
            progress = { (project.gatheredCents.toFloat() / project.amountCents.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(PROJECT_BAR_HEIGHT),
            trackColor = maggieSurface().track,
            drawStopIndicator = {},
            gapSize = 0.dp,
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

private val RING_SIZE = 70.dp
private val RING_STROKE = 7.dp
private val PROJECT_BAR_HEIGHT = 6.dp
private const val PILL_TINT_ALPHA = 0.16f
