package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.DebtTimeline
import com.maggie.app.data.model.EarlyRepayment
import com.maggie.app.data.model.LoanCurvePoint
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.formatCentsRounded
import com.maggie.app.data.model.formatLoanMonth
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.maggieSurface
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale

/** « Fin en Mars 2034 », or the dash when the schedule never reaches zero. */
fun loanEndLabel(freedOn: String?): String = if (freedOn == null) "Pas de fin calculée" else "Fin en ${formatLoanMonth(freedOn)}"

/** What a screen reader says of the curve. */
fun loanCurveDescription(points: List<LoanCurvePoint>, freedOn: String?, currency: String): String {
    val first = points.firstOrNull() ?: return "Aucun échéancier"
    return "Capital restant dû de ${formatCents(first.remainingCents, currency)} en ${formatLoanMonth(first.month)} à 0. ${loanEndLabel(freedOn)}."
}

/** The capital still owed month by month, down to the month it ends. */
@Composable
fun LoanCurveChart(
    points: List<LoanCurvePoint>,
    freedOn: String?,
    currency: String,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return

    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    val top = maxOf(points.maxOf { it.remainingCents }, 1).toFloat()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .testTag(UiTags.LOAN_CURVE)
                .semantics { contentDescription = loanCurveDescription(points, freedOn, currency) },
        ) {
            fun x(index: Int) = if (points.size == 1) size.width / 2 else size.width * index / (points.size - 1)
            fun y(cents: Int) = size.height * (1f - cents / top)

            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())

            val path = Path()
            points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(x(index), y(point.remainingCents)) else path.lineTo(x(index), y(point.remainingCents))
            }
            val area = Path().apply {
                addPath(path)
                lineTo(x(points.size - 1), size.height)
                lineTo(x(0), size.height)
                close()
            }
            drawPath(area, line.copy(alpha = 0.12f))
            drawPath(path, line, style = Stroke(width = 2.5.dp.toPx()))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatCents(points.first().remainingCents, currency),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                loanEndLabel(freedOn),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(UiTags.LOAN_END),
            )
        }
    }
}

/** The interest still to pay against the IRA that repaying now would cost, and the difference between them. */
@Composable
fun LoanIraBar(
    repayment: EarlyRepayment,
    currency: String,
    modifier: Modifier = Modifier,
) {
    val scale = maxOf(repayment.remainingInterestCents, repayment.penaltyCents, 1).toFloat()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BarLine("Intérêts restants", repayment.remainingInterestCents, scale, currency, MaterialTheme.colorScheme.primary)
        BarLine("IRA", repayment.penaltyCents, scale, currency, MaterialTheme.colorScheme.tertiary)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Gain réel", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                formatCents(repayment.realGainCents, currency),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun BarLine(label: String, cents: Int, scale: Float, currency: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(formatCents(cents, currency), style = MaterialTheme.typography.bodySmall)
        }
        Box(modifier = Modifier.fillMaxWidth().height(10.dp).background(maggieSurface().track, RoundedCornerShape(5.dp))) {
            if (cents > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((cents / scale).coerceIn(0.02f, 1f))
                        .height(10.dp)
                        .background(color, RoundedCornerShape(5.dp)),
                )
            }
        }
    }
}

internal data class ReliefStep(val monthIndex: Int, val cumulativeCents: Int)

private fun monthsBetween(from: YearMonth, month: String): Int =
    ChronoUnit.MONTHS.between(from, YearMonth.parse(month)).toInt()

/** The months the staircase covers (60 at least) and one step per relief, indexed from [from]. */
internal fun reliefSteps(timeline: DebtTimeline, from: YearMonth): Pair<Int, List<ReliefStep>> {
    val steps = timeline.reliefByMonth.map { ReliefStep(monthsBetween(from, it.month), it.cumulativeFreedCents) }
    val last = steps.maxOfOrNull { it.monthIndex } ?: 0

    return maxOf(60, last + 1) to steps
}

/** What a screen reader says of the staircase. */
internal fun reliefDescription(timeline: DebtTimeline): String =
    "Mensualités libérées cumulées : " + timeline.reliefByMonth.joinToString(", ") {
        "${formatCentsRounded(it.cumulativeFreedCents)} en ${formatLoanMonth(it.month).lowercase(Locale.FRANCE)}"
    }

private val SHORT_MONTHS = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")

private fun shortMonth(month: YearMonth) = "${SHORT_MONTHS[month.monthValue - 1]} ${month.year}"

/** The monthly payments freed so far, as a staircase from [from] up to the total of the payments. */
@Composable
fun ReliefStepChart(timeline: DebtTimeline, from: YearMonth, modifier: Modifier = Modifier) {
    val (months, steps) = reliefSteps(timeline, from)
    val line = MaggieTokens.Signal.success
    val axis = MaterialTheme.colorScheme.outline
    val top = maxOf(timeline.totalMonthlyPaymentCents, steps.maxOfOrNull { it.cumulativeCents } ?: 0, 1).toFloat()
    val end = from.plusMonths((months - 1).toLong())
    val years = steps.map { from.plusMonths(it.monthIndex.toLong()).year }.distinct()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .semantics { contentDescription = reliefDescription(timeline) },
        ) {
            fun x(index: Int) = size.width * index / maxOf(months - 1, 1)
            fun y(cents: Int) = size.height * (1f - cents / top)

            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())

            val path = Path().apply {
                moveTo(0f, y(0))
                var current = 0
                steps.forEach { step ->
                    lineTo(x(step.monthIndex.coerceAtLeast(0)), y(current))
                    lineTo(x(step.monthIndex.coerceAtLeast(0)), y(step.cumulativeCents))
                    current = step.cumulativeCents
                }
                lineTo(size.width, y(current))
            }
            val area = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(area, line.copy(alpha = 0.10f))
            drawPath(path, line, style = Stroke(width = 2.5.dp.toPx()))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Text(shortMonth(from), style = MaterialTheme.typography.labelSmall, color = muted)
            years.forEach { Text("$it", style = MaterialTheme.typography.labelSmall, color = muted) }
            Text(shortMonth(end), style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}
