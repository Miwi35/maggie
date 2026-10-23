package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.PlanningMonth
import com.maggie.app.data.model.ProjectPlanning
import com.maggie.app.data.model.formatCents
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.chartColor

/** The three sources that fund the projects, bottom to top of a bar: the salary carries the stack. */
enum class FundingSource(val label: String, val slot: Int) {
    SALARY("Salaire", 1),
    SAVINGS("Économies", 2),
    CAPITAL("Capital libéré", 3),
}

/** One bar of the chart: a month, what each source gives it, the capacity of the salary. */
data class FundingBar(
    val index: Int,
    val month: String,
    val salaryCents: Int,
    val savingsCents: Int,
    val capitalCents: Int,
    val capacityCents: Int,
    val isOverCapacity: Boolean,
    /** What the salary gives past its capacity; 0 when it stays within. */
    val excessCents: Int,
    val detail: String,
) {
    fun cents(source: FundingSource): Int = when (source) {
        FundingSource.SALARY -> salaryCents
        FundingSource.SAVINGS -> savingsCents
        FundingSource.CAPITAL -> capitalCents
    }
}

private fun detailOf(month: PlanningMonth, currency: String): String = buildList {
    add(ganttMonthLabel(month.month))
    FundingSource.entries.forEach { source ->
        val cents = when (source) {
            FundingSource.SALARY -> month.salaryCents
            FundingSource.SAVINGS -> month.savingsCents
            FundingSource.CAPITAL -> month.capitalCents
        }
        add("${source.label} ${formatCents(cents, currency)}")
    }
    if (month.rentCents > 0) add("Loyers ${formatCents(month.rentCents, currency)}")
    if (month.resaleCents > 0) add("Revente ${formatCents(month.resaleCents, currency)}")
    add("Capacité ${formatCents(month.capacityCents, currency)}")
    if (month.isOverCapacity) add("Dépassement ${formatCents(month.salaryCents - month.capacityCents, currency)}")
}.joinToString(" · ")

/** The bars to draw, one per month of the planning (MAG-528); empty while the income is unknown. */
fun fundingBars(planning: ProjectPlanning): List<FundingBar> {
    if (!planning.isIncomeKnown) return emptyList()
    return planning.months.mapIndexed { index, month ->
        FundingBar(
            index = index,
            month = month.month,
            salaryCents = month.salaryCents,
            savingsCents = month.savingsCents,
            capitalCents = month.capitalCents,
            capacityCents = month.capacityCents,
            isOverCapacity = month.isOverCapacity,
            excessCents = if (month.isOverCapacity) month.salaryCents - month.capacityCents else 0,
            detail = detailOf(month, planning.currency),
        )
    }
}

/** The height the chart's axis tops out at: the tallest bar or capacity step, never 0. */
fun fundingScale(bars: List<FundingBar>): Int =
    maxOf(1, bars.maxOfOrNull { maxOf(it.capacityCents, it.salaryCents + it.savingsCents + it.capitalCents) } ?: 0)

private val PLOT_HEIGHT = 120.dp
private val MARK_BAND = 14.dp
private val MARK_SIZE = 8.dp

/**
 * How the projects are funded month by month (MAG-528), under the Gantt and on its month columns:
 * one stacked bar per month — salary, savings, released capital — and the capacity of the salary as a
 * dashed staircase. A month where the salary goes past its capacity carries a neutral mark above its
 * bar and says the excess when touched: a fact to read, so no alert colour. Read-only; it slides with
 * the Gantt through the shared [scrollState]. Nothing without months.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectFundingChart(
    planning: ProjectPlanning,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val bars = remember(planning) { fundingBars(planning) }
    if (bars.isEmpty()) return

    var detail by remember { mutableStateOf<String?>(null) }
    val scale = fundingScale(bars)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = FundingSource.entries.associateWith { chartColor(it.slot, dark) }
    val ink = MaterialTheme.colorScheme.onSurface
    val horizon = bars.size

    Column(modifier = modifier.fillMaxWidth().testTag(UiTags.FUNDING_CHART), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            modifier = Modifier.testTag(UiTags.FUNDING_LEGEND),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FundingSource.entries.forEach { source -> LegendItem(source.label) { Box(Modifier.size(10.dp).background(colors.getValue(source))) } }
            LegendItem("Capacité sur salaire") {
                Canvas(Modifier.width(14.dp).height(10.dp)) {
                    drawLine(ink, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val column = maxOf(COLUMN_MIN_FULL, (maxWidth - LABEL_WIDTH_FULL) / horizon)
            val trackWidth = column * horizon
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(LABEL_WIDTH_FULL)) {
                    Box(Modifier.height(PLOT_HEIGHT + MARK_BAND).fillMaxWidth()) {
                        Text(
                            formatCents(scale, planning.currency),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.TopStart),
                        )
                        Text(
                            formatCents(0, planning.currency),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.BottomStart),
                        )
                    }
                }
                Column(Modifier.horizontalScroll(scrollState)) {
                    Box(Modifier.width(trackWidth).height(PLOT_HEIGHT + MARK_BAND)) {
                        Row {
                            bars.forEach { bar ->
                                MonthBar(bar, column, scale, colors, ink, onTap = { detail = it })
                            }
                        }
                        Canvas(
                            Modifier
                                .align(Alignment.BottomStart)
                                .width(trackWidth)
                                .height(PLOT_HEIGHT)
                                .testTag(UiTags.FUNDING_CAPACITY)
                                .semantics { contentDescription = "Capacité sur salaire" },
                        ) {
                            val stair = Path()
                            val step = size.width / horizon
                            bars.forEachIndexed { index, bar ->
                                val y = size.height - size.height * bar.capacityCents / scale
                                if (index == 0) stair.moveTo(0f, y) else stair.lineTo(index * step, y)
                                stair.lineTo((index + 1) * step, y)
                            }
                            drawPath(stair, ink, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f))))
                        }
                    }
                    Axis(planning, column, trackWidth, todayTag = UiTags.FUNDING_TODAY)
                }
            }
        }
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.FUNDING_DETAIL),
            )
        }
    }
}

@Composable
private fun LegendItem(label: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        swatch()
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonthBar(
    bar: FundingBar,
    column: Dp,
    scale: Int,
    colors: Map<FundingSource, Color>,
    ink: Color,
    onTap: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(column)
            .height(PLOT_HEIGHT + MARK_BAND)
            .then(if (bar.index == 0) Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) else Modifier)
            .clickable { onTap(bar.detail) }
            .semantics { contentDescription = bar.detail }
            .testTag(UiTags.FUNDING_MONTH),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(Modifier.width(column - 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (bar.isOverCapacity) {
                Box(Modifier.size(MARK_SIZE).rotate(45f).background(ink).testTag(UiTags.FUNDING_OVER))
                Spacer(Modifier.height(5.dp))
            }
            // Top to bottom: the capital rides on the savings, which ride on the salary.
            FundingSource.entries.reversed().forEach { source ->
                val cents = bar.cents(source)
                if (cents > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(PLOT_HEIGHT * (cents.toFloat() / scale))
                            .background(colors.getValue(source))
                            .testTag(
                                when (source) {
                                    FundingSource.SALARY -> UiTags.FUNDING_SALARY
                                    FundingSource.SAVINGS -> UiTags.FUNDING_SAVINGS
                                    FundingSource.CAPITAL -> UiTags.FUNDING_CAPITAL
                                },
                            ),
                    )
                }
            }
        }
    }
}
