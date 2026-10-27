package com.maggie.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.PeriodScore
import com.maggie.app.data.model.Rhythm
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.reasonText
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.home.financeRhythmColor
import com.maggie.app.ui.theme.maggieSurface
import kotlin.math.cos
import kotlin.math.sin

private const val WEEK = "week"
private const val MONTH = "month"

/** « 2 000,00 € non classés » under a ring; null when every debit of the period has a category. */
fun unclassifiedLine(rhythm: Rhythm): String? =
    if (rhythm.unclassifiedCount > 0) "${formatCents(rhythm.unclassifiedCents)} non classés" else null

/**
 * How much of the ring is drawn: what was spent against the larger of the spending and the plan, from 0 to 1.
 * Spending more than planned fills the ring. No scale, no arc.
 */
fun ringFill(spentCents: Int, plannedCents: Int): Float {
    val scale = maxOf(spentCents, plannedCents)
    return if (scale <= 0) 0f else (spentCents.toFloat() / scale).coerceIn(0f, 1f)
}

fun ringFill(rhythm: Rhythm): Float = ringFill(rhythm.spentCents, rhythm.plannedCents)

/** « above » once what was spent passes what was expected by today, « on_track » otherwise; nothing planned, nothing to pass. */
fun ringRhythmStatus(rhythm: Rhythm): String =
    if (rhythm.plannedCents > 0 && rhythm.spentCents > rhythm.expectedSpentCents) "above" else "on_track"

/** Where the tick sits on the ring: what was expected by today on the same scale. Null when nothing is planned. */
fun ringTick(spentCents: Int, expectedSpentCents: Int, plannedCents: Int): Float? =
    if (plannedCents > 0) {
        (expectedSpentCents.toFloat() / maxOf(spentCents, plannedCents, 1)).coerceIn(0f, 1f)
    } else {
        null
    }

fun ringTick(rhythm: Rhythm): Float? = ringTick(rhythm.spentCents, rhythm.expectedSpentCents, rhythm.plannedCents)

/** « Attendu 900,00 € » under a ring; null when nothing is planned. */
fun expectedLine(expectedSpentCents: Int, plannedCents: Int): String? =
    if (plannedCents > 0) "Attendu ${formatCents(expectedSpentCents)}" else null

fun expectedLine(rhythm: Rhythm): String? = expectedLine(rhythm.expectedSpentCents, rhythm.plannedCents)

/** What a screen reader says of a ring, and what Maestro can read: the amounts, in clear, never a verdict. */
fun ringDescription(title: String, spentCents: Int, expectedSpentCents: Int, plannedCents: Int): String {
    val expected = if (plannedCents > 0) ", ${formatCents(expectedSpentCents)} attendus" else ""
    return "$title : ${formatCents(spentCents)} dépensés$expected. Afficher les raisons"
}

fun ringDescription(title: String, rhythm: Rhythm): String =
    ringDescription(title, rhythm.spentCents, rhythm.expectedSpentCents, rhythm.plannedCents)

@Composable
internal fun statusColor(status: String): Color = when (status) {
    "green" -> MaterialTheme.colorScheme.primary
    "orange" -> MaterialTheme.colorScheme.tertiary
    "red" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The track, the arc and the tick of a ring; shared by the dashboard and the home card. */
@Composable
internal fun SpentRingCanvas(
    fill: Float,
    tick: Float?,
    diameter: Dp,
    stroke: Dp,
    arcColor: Color = MaterialTheme.colorScheme.primary,
) {
    val track = maggieSurface().track
    val tickColor = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = Modifier.size(diameter)) {
        val strokePx = stroke.toPx()
        val topLeft = Offset(strokePx / 2, strokePx / 2)
        val arc = Size(size.width - strokePx, size.height - strokePx)

        drawArc(track, -90f, 360f, false, topLeft, arc, style = Stroke(strokePx))
        if (fill > 0f) {
            drawArc(arcColor, -90f, 360f * fill, false, topLeft, arc, style = Stroke(strokePx, cap = StrokeCap.Butt))
        }
        if (tick != null) {
            val angle = Math.toRadians((-90f + 360f * tick).toDouble())
            val middle = Offset(size.width / 2, size.height / 2)
            val inner = arc.width / 2 - strokePx * 0.75f
            val outer = arc.width / 2 + strokePx * 0.75f
            drawLine(
                color = tickColor,
                start = Offset(middle.x + inner * cos(angle).toFloat(), middle.y + inner * sin(angle).toFloat()),
                end = Offset(middle.x + outer * cos(angle).toFloat(), middle.y + outer * sin(angle).toFloat()),
                strokeWidth = 3.dp.toPx(),
            )
        }
    }
}

/**
 * What was spent this week and this month, against what was planned (MAG-417, MAG-643).
 *
 * Plain values only, no ViewModel: the finance dashboard draws it today and the home will draw
 * it from its own state. Each ring says the amount spent; the arc is its share of the larger of the
 * spending and the plan, the tick marks what was expected by today. The arc is mint in the rhythm and
 * orange above it, in colour only, never in words. A tap on a ring lists the reasons of its score.
 */
@Composable
fun ScoreRings(
    week: PeriodScore,
    month: PeriodScore,
    modifier: Modifier = Modifier,
    onClassify: () -> Unit = {},
) {
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val toggle = { period: String -> opened = if (opened == period) null else period }

    MaggieCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ScoreRing(Modifier.weight(1f), "Semaine", WEEK, week, opened == WEEK, onClassify) { toggle(WEEK) }
                ScoreRing(Modifier.weight(1f), "Mois", MONTH, month, opened == MONTH, onClassify) { toggle(MONTH) }
            }
            when (opened) {
                WEEK -> ScoreReasons("Semaine", week)
                MONTH -> ScoreReasons("Mois", month)
            }
        }
    }
}

/** The ring of one month on its own — the hero of the month balance. A tap lists the reasons of its score. */
@Composable
fun MonthScoreRing(
    score: PeriodScore,
    modifier: Modifier = Modifier,
    onClassify: () -> Unit = {},
) {
    var isOpen by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ScoreRing(Modifier, "Mois", MONTH, score, isOpen, onClassify) { isOpen = !isOpen }
        if (isOpen) ScoreReasons("Mois", score)
    }
}

@Composable
private fun ScoreRing(
    modifier: Modifier,
    title: String,
    period: String,
    score: PeriodScore,
    isOpen: Boolean,
    onClassify: () -> Unit,
    onClick: () -> Unit,
) {
    val rhythm = score.rhythm
    val fill = ringFill(rhythm)
    val tick = ringTick(rhythm)
    val arcColor = financeRhythmColor(ringRhythmStatus(rhythm), MaterialTheme.colorScheme.background.luminance() < 0.5f)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clickable(onClickLabel = if (isOpen) "Masquer les raisons" else "Voir les raisons", role = Role.Button, onClick = onClick)
                .padding(8.dp)
                .testTag(UiTags.financeScoreRing(period))
                .semantics(mergeDescendants = true) { contentDescription = ringDescription(title, rhythm) },
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)

            Box(modifier = Modifier.padding(vertical = 8.dp).size(RING_SIZE), contentAlignment = Alignment.Center) {
                SpentRingCanvas(fill, tick, RING_SIZE, RING_STROKE, arcColor)
                RingCenter(rhythm)
            }

            expectedLine(rhythm)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        UnclassifiedLine(period, rhythm, onClassify)
    }
}

/** What no category claims yet, said under the ring in the secondary style, with the way to the Revue (MAG-643). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnclassifiedLine(period: String, rhythm: Rhythm, onClassify: () -> Unit) {
    val line = unclassifiedLine(rhythm) ?: return
    FlowRow(
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.financeScoreUnclassified(period)),
    ) {
        Text(
            text = "$line · ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Classer",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onClassify)
                .minimumInteractiveComponentSize()
                .testTag(UiTags.financeScoreClassify(period)),
        )
    }
}

@Composable
private fun RingCenter(rhythm: Rhythm) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.size(RING_SIZE - RING_STROKE * 2 - 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = formatCents(rhythm.spentCents),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "dépensés",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScoreReasons(title: String, score: PeriodScore) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(UiTags.FINANCE_SCORE_REASONS)) {
        Text("$title : pourquoi", style = MaterialTheme.typography.titleSmall)
        if (score.reasons.isEmpty()) {
            Text(
                text = "Rien à signaler.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        score.reasons.forEach { reason ->
            Text(
                text = "• ${reasonText(reason)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private val RING_SIZE = 128.dp
private val RING_STROKE = 12.dp
