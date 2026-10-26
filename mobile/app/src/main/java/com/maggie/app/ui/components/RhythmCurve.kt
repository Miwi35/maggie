package com.maggie.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Rhythm
import com.maggie.app.data.model.RhythmDebit
import com.maggie.app.data.model.RhythmPoint
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.monthLabel
import com.maggie.app.ui.UiTags
import java.time.LocalDate

private const val WEEK = "week"
private const val MONTH = "month"

/** Where along the curve a day falls, from 0 (first day) to 1 (last). A one-day curve has only its start. */
fun dayFraction(index: Int, count: Int): Float = if (count <= 1) 0f else index.toFloat() / (count - 1)

/** The tallest value drawn, so that both curves fit; never 0, to keep a division safe. */
fun curveCeiling(rhythm: Rhythm): Int {
    val spent = rhythm.curve.mapNotNull { it.spentCents }
    val expected = rhythm.curve.map { it.expectedCents }
    return (spent + expected).maxOrNull()?.coerceAtLeast(1) ?: 1
}

/** The point of today: the last day the spending is known for. */
fun lastKnownPoint(rhythm: Rhythm): RhythmPoint? = rhythm.curve.lastOrNull { it.spentCents != null }

/** A debit sits on its date along the curve; null when the date is outside it. */
fun debitFraction(debit: RhythmDebit, curve: List<RhythmPoint>): Float? {
    val index = curve.indexOfFirst { it.date == debit.date }
    return if (index < 0) null else dayFraction(index, curve.size)
}

/** « 5 mars », from an ISO date; the date itself if it cannot be read. */
fun dayMonthLabel(date: String): String =
    runCatching { LocalDate.parse(date) }
        .map { "${it.dayOfMonth} ${monthLabel(it.monthValue).lowercase()}" }
        .getOrDefault(date)

/** The day of the month, or the date itself if it cannot be read. */
private fun dayOfMonth(date: String): String =
    runCatching { LocalDate.parse(date).dayOfMonth.toString() }.getOrDefault(date)

/** What a screen reader and Maestro get of the curve: the two figures, never a verdict. */
fun curveDescription(rhythm: Rhythm): String {
    val today = lastKnownPoint(rhythm) ?: return "Dépensé cumulé : aucune dépense connue pour le moment"
    return "Dépensé cumulé : ${formatCents(today.spentCents ?: 0)} pour ${formatCents(today.expectedCents)} attendus"
}

/** The description of one debit marker. */
fun debitDescription(debit: RhythmDebit): String =
    "${debit.label}, ${formatCents(debit.amountCents)}, prélevé le ${dayMonthLabel(debit.date)}"

/**
 * The week or the month as a curve (MAG-417), the month by default.
 *
 * Plain values only, like [ScoreRings]: the finance dashboard draws it today and the home will.
 */
@Composable
fun RhythmCurvePanel(
    week: Rhythm,
    month: Rhythm,
    modifier: Modifier = Modifier,
) {
    var period by rememberSaveable { mutableStateOf(MONTH) }

    MaggieCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Rythme", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 8.dp))
                FilterChip(
                    selected = period == WEEK,
                    onClick = { period = WEEK },
                    label = { Text("Semaine") },
                    modifier = Modifier.testTag(UiTags.financeRhythmPeriod(WEEK)),
                )
                FilterChip(
                    selected = period == MONTH,
                    onClick = { period = MONTH },
                    label = { Text("Mois") },
                    modifier = Modifier.testTag(UiTags.financeRhythmPeriod(MONTH)),
                )
            }
            RhythmCurve(rhythm = if (period == WEEK) week else month)
        }
    }
}

/** One period's spending against what was expected, up to today, with the debits marked on their dates. */
@Composable
fun RhythmCurve(
    rhythm: Rhythm,
    modifier: Modifier = Modifier,
) {
    if (rhythm.curve.isEmpty()) {
        Text(
            text = "Pas de courbe pour cette période.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(top = 8.dp),
        )
        return
    }

    val spentColor = MaterialTheme.colorScheme.primary
    val expectedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val debitColor = MaterialTheme.colorScheme.outline
    val curve = rhythm.curve
    val ceiling = curveCeiling(rhythm).toFloat()
    val marked = rhythm.debits.mapNotNull { debit -> debitFraction(debit, curve)?.let { debit to it } }

    BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        val width = maxWidth

        Column(modifier = Modifier.fillMaxWidth()) {
            // Debit labels: two rows, so that two near dates do not sit on one another.
            Box(modifier = Modifier.fillMaxWidth().height(LABEL_ROW_HEIGHT * 2)) {
                marked.forEachIndexed { index, (debit, fraction) ->
                    Text(
                        text = "${debit.label} · ${dayOfMonth(debit.date)}",
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .width(LABEL_WIDTH)
                            .offset(x = labelOffset(fraction, width), y = LABEL_ROW_HEIGHT * (index % 2))
                            .testTag(UiTags.financeRhythmDebit(debit.date))
                            .semantics { contentDescription = debitDescription(debit) },
                    )
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CURVE_HEIGHT)
                    .testTag(UiTags.FINANCE_RHYTHM_CURVE)
                    .semantics { contentDescription = curveDescription(rhythm) },
            ) {
                val pad = 4.dp.toPx()
                val usable = size.height - 2 * pad
                fun x(index: Int) = dayFraction(index, curve.size) * size.width
                fun y(cents: Int) = pad + usable * (1f - cents.coerceAtLeast(0) / ceiling)

                marked.forEach { (_, fraction) ->
                    drawLine(
                        color = debitColor,
                        start = Offset(fraction * size.width, 0f),
                        end = Offset(fraction * size.width, size.height),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                    )
                }

                val expected = Path().apply {
                    curve.forEachIndexed { i, point ->
                        if (i == 0) moveTo(x(i), y(point.expectedCents)) else lineTo(x(i), y(point.expectedCents))
                    }
                }
                drawPath(
                    expected,
                    expectedColor,
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
                )

                // A future day has no spending: the curve stops at today.
                val spent = Path()
                var started = false
                curve.forEachIndexed { i, point ->
                    val cents = point.spentCents ?: return@forEachIndexed
                    if (started) spent.lineTo(x(i), y(cents)) else spent.moveTo(x(i), y(cents))
                    started = true
                }
                drawPath(spent, spentColor, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))

                val today = curve.indexOfLast { it.spentCents != null }
                if (today >= 0) {
                    drawCircle(spentColor, 5.dp.toPx(), Offset(x(today), y(curve[today].spentCents ?: 0)))
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(dayMonthLabel(curve.first().date), style = MaterialTheme.typography.labelSmall, color = expectedColor)
                Text(dayMonthLabel(curve.last().date), style = MaterialTheme.typography.labelSmall, color = expectedColor)
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("— Dépensé", style = MaterialTheme.typography.labelSmall, color = spentColor)
                Text("- - Attendu", style = MaterialTheme.typography.labelSmall, color = expectedColor)
            }
        }
    }
}

/** The label is centred on its date, kept inside the chart. */
private fun labelOffset(fraction: Float, width: Dp): Dp =
    (width * fraction - LABEL_WIDTH / 2).coerceIn(0.dp, (width - LABEL_WIDTH).coerceAtLeast(0.dp))

private val LABEL_WIDTH = 72.dp
private val LABEL_ROW_HEIGHT = 16.dp
private val CURVE_HEIGHT = 140.dp
