package com.maggie.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.PeriodScore
import com.maggie.app.data.model.Rhythm
import com.maggie.app.data.model.reasonText
import com.maggie.app.ui.UiTags
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private const val WEEK = "week"
private const val MONTH = "month"

/** Shown in a ring that has no envelope behind it. */
const val NO_ENVELOPE_TEXT = "Aucune enveloppe"

/** The glyph that goes with a status: a status is never told by its colour alone. */
enum class ScoreMark { CHECK, INFO, WARNING, ERROR }

fun scoreMark(status: String): ScoreMark = when (status) {
    "green" -> ScoreMark.CHECK
    "orange" -> ScoreMark.WARNING
    "red" -> ScoreMark.ERROR
    else -> ScoreMark.INFO
}

/** The word next to the glyph: the API's own label, or a fallback if it sent none. */
fun scoreWord(status: String, label: String): String = label.ifBlank {
    when (status) {
        "green" -> "Dans le vert"
        "orange" -> "Attention"
        "red" -> "Dépassement"
        else -> "Dans les clous"
    }
}

/** The word of a ring that has a budget: where spending stands against its pace (`RhythmStatus`, MAG-537). */
fun rhythmWord(rhythm: Rhythm): String = rhythm.label.ifBlank {
    if (rhythm.status == "above") "Au-dessus du rythme" else "Dans le rythme"
}

/** An amount of a ring, in « € » as the mock-ups write it: « 120,00 € ». */
fun ringAmount(cents: Int): String = NumberFormat.getCurrencyInstance(Locale.FRANCE).format(cents / 100.0)

/** The word under a ring: the pace when there is a budget, the period's own label otherwise. */
internal fun ringWord(score: PeriodScore): String =
    if (hasEnvelope(score.rhythm)) rhythmWord(score.rhythm) else scoreWord(score.status, score.label)

/**
 * The status that colours a ring and picks its glyph: the pace when there is a budget (mint check or
 * orange warning, the mock-ups' two states), like its word; the period's own status otherwise.
 */
fun ringStatus(score: PeriodScore): String = when {
    !hasEnvelope(score.rhythm) -> score.status
    score.rhythm.status == "above" -> "orange"
    else -> "green"
}

/** A ring needs a budget to be proportional to; without one there is nothing to draw. */
fun hasEnvelope(rhythm: Rhythm): Boolean = rhythm.budgetCents > 0

/**
 * The share of the ring that is still to spend: what remains of the budget, from 0 to 1.
 * An overspent period (negative remainder) fills the whole ring.
 */
fun ringFill(rhythm: Rhythm): Float = when {
    !hasEnvelope(rhythm) -> 0f
    rhythm.remainingCents < 0 -> 1f
    else -> (rhythm.remainingCents.toFloat() / rhythm.budgetCents).coerceIn(0f, 1f)
}

/** Where the tick sits on the ring: what should remain today, as a share of the budget. Null without a budget. */
fun ringTick(rhythm: Rhythm): Float? =
    if (hasEnvelope(rhythm)) (rhythm.expectedRemainingCents.toFloat() / rhythm.budgetCents).coerceIn(0f, 1f) else null

/** What a screen reader says of a ring, and what Maestro can read: the word, the amounts, in clear. */
fun ringDescription(title: String, score: PeriodScore): String {
    val word = ringWord(score)
    val rhythm = score.rhythm
    if (!hasEnvelope(rhythm)) return "$title : $word. $NO_ENVELOPE_TEXT."

    val left = if (rhythm.remainingCents < 0) {
        "dépassé de ${ringAmount(-rhythm.remainingCents)}"
    } else {
        "il reste ${ringAmount(rhythm.remainingCents)}"
    }
    return "$title : $word, $left sur ${ringAmount(rhythm.budgetCents)}, " +
        "attendu aujourd'hui ${ringAmount(rhythm.expectedRemainingCents)}"
}

private fun ScoreMark.icon(): ImageVector = when (this) {
    ScoreMark.CHECK -> Icons.Default.CheckCircle
    ScoreMark.INFO -> Icons.Default.Info
    ScoreMark.WARNING -> Icons.Default.Warning
    ScoreMark.ERROR -> Icons.Default.Error
}

@Composable
internal fun statusColor(status: String): Color = when (status) {
    "green" -> MaterialTheme.colorScheme.primary
    "orange" -> MaterialTheme.colorScheme.tertiary
    "red" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The glyph and the word of a status, side by side. */
@Composable
internal fun StatusLabel(status: String, label: String, modifier: Modifier = Modifier, color: Color = statusColor(status)) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = scoreMark(status).icon(),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = scoreWord(status, label),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * What is left to spend this week and this month (MAG-417).
 *
 * Plain values only, no ViewModel: the finance dashboard draws it today and the home will draw
 * it from its own state. Each ring is the remainder of the budget, as an arc proportional to the
 * budget; the tick marks what should remain today. A tap on a ring lists the reasons of its score.
 */
@Composable
fun ScoreRings(
    week: PeriodScore,
    month: PeriodScore,
    modifier: Modifier = Modifier,
) {
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val toggle = { period: String -> opened = if (opened == period) null else period }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ScoreRing("Semaine", WEEK, week, opened == WEEK) { toggle(WEEK) }
                ScoreRing("Mois", MONTH, month, opened == MONTH) { toggle(MONTH) }
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
) {
    var isOpen by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ScoreRing("Dépensé", MONTH, score, isOpen) { isOpen = !isOpen }
        if (isOpen) ScoreReasons("Mois", score)
    }
}

@Composable
private fun ScoreRing(
    title: String,
    period: String,
    score: PeriodScore,
    isOpen: Boolean,
    onClick: () -> Unit,
) {
    val rhythm = score.rhythm
    val status = ringStatus(score)
    val color = statusColor(status)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurface
    val fill = ringFill(rhythm)
    val tick = ringTick(rhythm)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClickLabel = if (isOpen) "Masquer les raisons" else "Voir les raisons", role = Role.Button, onClick = onClick)
            .padding(8.dp)
            .testTag(UiTags.financeScoreRing(period))
            .semantics(mergeDescendants = true) { contentDescription = ringDescription(title, score) },
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)

        Box(modifier = Modifier.padding(vertical = 8.dp).size(RING_SIZE), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(RING_SIZE)) {
                val stroke = RING_STROKE.toPx()
                val topLeft = Offset(stroke / 2, stroke / 2)
                val arc = Size(size.width - stroke, size.height - stroke)

                drawArc(track, -90f, 360f, false, topLeft, arc, style = Stroke(stroke))
                if (fill > 0f) {
                    drawArc(color, -90f, 360f * fill, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Butt))
                }
                if (tick != null) {
                    val angle = Math.toRadians((-90f + 360f * tick).toDouble())
                    val middle = Offset(size.width / 2, size.height / 2)
                    val inner = arc.width / 2 - stroke * 0.75f
                    val outer = arc.width / 2 + stroke * 0.75f
                    drawLine(
                        color = tickColor,
                        start = Offset(middle.x + inner * cos(angle).toFloat(), middle.y + inner * sin(angle).toFloat()),
                        end = Offset(middle.x + outer * cos(angle).toFloat(), middle.y + outer * sin(angle).toFloat()),
                        strokeWidth = 3.dp.toPx(),
                    )
                }
            }
            RingCenter(rhythm)
        }

        StatusLabel(status, ringWord(score))
    }
}

@Composable
private fun RingCenter(rhythm: Rhythm) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.size(RING_SIZE - RING_STROKE * 2 - 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (!hasEnvelope(rhythm)) {
            Text(
                text = NO_ENVELOPE_TEXT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            return@Column
        }

        Text(
            text = ringAmount(rhythm.remainingCents),
            style = MaterialTheme.typography.titleSmall,
            color = if (rhythm.remainingCents < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (rhythm.remainingCents < 0) "dépassé" else "restant",
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
