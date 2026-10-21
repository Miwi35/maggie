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
import com.maggie.app.data.model.EarlyRepayment
import com.maggie.app.data.model.LoanCurvePoint
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.formatLoanMonth
import com.maggie.app.ui.UiTags

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
        Box(modifier = Modifier.fillMaxWidth().height(10.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))) {
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
