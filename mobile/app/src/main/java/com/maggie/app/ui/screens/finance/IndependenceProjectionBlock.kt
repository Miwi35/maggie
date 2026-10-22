package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CoveragePoint
import com.maggie.app.data.model.IndependenceCounter
import com.maggie.app.data.model.IndependenceProjection
import com.maggie.app.data.model.independenceCurveDescription
import com.maggie.app.data.model.independenceDateN
import com.maggie.app.data.model.independenceMilestoneRows
import com.maggie.app.data.model.independenceMonthLabel
import com.maggie.app.data.model.independenceRemaining
import com.maggie.app.ui.UiTags

/**
 * Date N, the coverage curve and the dated milestones of the independence counter (MAG-549).
 * One composable for the phone card and the tablet's right pane: both draw the Planification
 * tile. Statements only — no verdict, no advice. Draws nothing when the counter has nothing to
 * project (not measurable, or a payload without projection).
 */
@Composable
internal fun IndependenceProjectionBlock(counter: IndependenceCounter, modifier: Modifier = Modifier) {
    val dateN = independenceDateN(counter) ?: return
    val projection = counter.projection

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = dateN,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(UiTags.INDEPENDENCE_DATE_N),
        )
        if (!counter.isReached) {
            independenceRemaining(projection)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (projection.curve.isNotEmpty()) {
            IndependenceCurve(projection, modifier = Modifier.padding(top = 8.dp))
        }
        IndependenceMilestones(projection, modifier = Modifier.padding(top = 8.dp))
    }
}

/** The coverage month by month, on a fixed 0–100 % axis so two curves can be compared. */
@Composable
private fun IndependenceCurve(projection: IndependenceProjection, modifier: Modifier = Modifier) {
    val points = projection.curve
    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(UiTags.INDEPENDENCE_CURVE)
                .semantics { contentDescription = independenceCurveDescription(projection) },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.height(120.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("100 %", "50 %", "0 %").forEach { Text(it, style = labelStyle, color = labelColor) }
            }
            Canvas(modifier = Modifier.weight(1f).height(120.dp)) {
                fun x(index: Int) = if (points.size == 1) size.width / 2 else size.width * index / (points.size - 1)
                fun y(point: CoveragePoint) =
                    size.height * (1f - point.coverage.toFloat().coerceIn(0f, 1f))

                listOf(0f, 0.5f, 1f).forEach { level ->
                    val gridY = size.height * (1f - level)
                    drawLine(axis, Offset(0f, gridY), Offset(size.width, gridY), strokeWidth = 1.dp.toPx())
                }

                if (points.size == 1) {
                    drawCircle(line, 3.dp.toPx(), Offset(x(0), y(points.first())))
                } else {
                    val path = Path()
                    points.forEachIndexed { index, point ->
                        if (index == 0) path.moveTo(x(index), y(point)) else path.lineTo(x(index), y(point))
                    }
                    drawPath(path, line, style = Stroke(width = 2.5.dp.toPx()))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 36.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(independenceMonthLabel(points.first().month), style = labelStyle, color = labelColor)
            Text(independenceMonthLabel(points.last().month), style = labelStyle, color = labelColor)
        }
    }
}

/** One row per step: « 25 % · mars 2028 », « 50 % · atteint » or « 75 % · non atteint d'ici 20 ans ». */
@Composable
private fun IndependenceMilestones(projection: IndependenceProjection, modifier: Modifier = Modifier) {
    val rows = independenceMilestoneRows(projection)
    if (rows.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth().testTag(UiTags.INDEPENDENCE_MILESTONES)) {
        rows.forEach { row ->
            Text(
                text = row.text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
