package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.TreasuryDay
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.treasuryDate

/** What a screen reader says of the chart: the range, the lowest day and when the overdraft starts. */
fun treasuryChartDescription(days: List<TreasuryDay>, limitCents: Int): String {
    val lowest = days.minByOrNull { it.balanceCents } ?: return "Aucune projection"
    val first = days.first()
    val last = days.last()
    val under = days.firstOrNull { it.balanceCents < -limitCents }

    return buildString {
        append("Solde prévu du ${treasuryDate(first.date)} au ${treasuryDate(last.date)}, ")
        append("de ${formatCents(first.balanceCents)} à ${formatCents(last.balanceCents)}. ")
        append("Plus bas : ${formatCents(lowest.balanceCents)} le ${treasuryDate(lowest.date)}. ")
        append(
            if (under != null) {
                "Zone de découvert atteinte le ${treasuryDate(under.date)}."
            } else {
                "Zone de découvert non atteinte."
            },
        )
    }
}

/**
 * The balance day by day. The overdraft zone — below minus the allowed limit — is shaded,
 * bordered by a dashed line, named in the legend, and its days are squares instead of
 * dots: nothing hangs on the colour alone.
 */
@Composable
fun TreasuryChart(
    days: List<TreasuryDay>,
    limitCents: Int,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) return

    val line = MaterialTheme.colorScheme.primary
    val zone = MaterialTheme.colorScheme.error
    val axis = MaterialTheme.colorScheme.outline

    val top = maxOf(days.maxOf { it.balanceCents }, 0)
    val bottom = minOf(days.minOf { it.balanceCents }, -limitCents)
    val pad = maxOf((top - bottom) * 0.08f, 100f)
    val high = top + pad
    val low = bottom - pad
    val zoneVisible = bottom < -limitCents

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .semantics { contentDescription = treasuryChartDescription(days, limitCents) },
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                fun y(cents: Int) = size.height * (high - cents) / (high - low)
                fun x(index: Int) = if (days.size == 1) size.width / 2 else size.width * index / (days.size - 1)

                if (zoneVisible) {
                    val from = y(-limitCents)
                    drawRect(zone.copy(alpha = 0.18f), Offset(0f, from), Size(size.width, size.height - from))
                }

                drawLine(axis, Offset(0f, y(0)), Offset(size.width, y(0)), strokeWidth = 1.dp.toPx())
                drawLine(
                    color = zone,
                    start = Offset(0f, y(-limitCents)),
                    end = Offset(size.width, y(-limitCents)),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                )

                val path = Path()
                days.forEachIndexed { index, day ->
                    if (index == 0) path.moveTo(x(index), y(day.balanceCents)) else path.lineTo(x(index), y(day.balanceCents))
                }
                drawPath(path, line, style = Stroke(width = 2.5.dp.toPx()))

                if (days.size <= 31) {
                    val radius = 3.dp.toPx()
                    days.forEachIndexed { index, day ->
                        val center = Offset(x(index), y(day.balanceCents))
                        if (day.balanceCents < -limitCents) {
                            drawRect(zone, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2))
                        } else {
                            drawCircle(line, radius, center)
                        }
                    }
                }
            }
            if (zoneVisible) {
                Text(
                    text = "Zone de découvert",
                    style = MaterialTheme.typography.labelSmall,
                    color = zone,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                treasuryDate(days.first().date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                treasuryDate(days.last().date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "Zone de découvert : solde sous ${formatCents(-limitCents)} (trait en pointillés, jours en carrés)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
