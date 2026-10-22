package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CushionPlanState
import com.maggie.app.data.model.ProjectDateMode
import com.maggie.app.data.model.ProjectPlanning
import com.maggie.app.data.model.formatCents
import com.maggie.app.ui.UiTags

/** A bar over months `from..to` (both included, 0 = the current month). [runsOn]: it goes past the right edge. */
data class GanttBar(val from: Int, val to: Int, val runsOn: Boolean, val detail: String)

data class GanttMark(val at: Int, val detail: String)

/** One line of the Gantt: a project, or the « Matelas » line while the cushion builds or recharges. */
data class GanttRow(
    val id: String,
    val label: String,
    val isCushion: Boolean = false,
    val saving: GanttBar? = null,
    val purchase: GanttMark? = null,
    val loan: GanttBar? = null,
    val deadline: GanttMark? = null,
    val isFixed: Boolean = false,
    val hasUnmetDependency: Boolean = false,
)

/** The compact variant (home tile, MAG-529) keeps the first projects only. */
const val GANTT_COMPACT_PROJECT_COUNT = 3

private val MONTH_NAMES = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")
private val MONTH_INITIALS = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")

/** `YYYY-MM` as a month count, so two months subtract. */
internal fun monthNumber(month: String): Int {
    val parts = month.split('-')
    val year = parts.getOrNull(0)?.toIntOrNull() ?: return 0
    val number = parts.getOrNull(1)?.toIntOrNull() ?: 1
    return year * 12 + number - 1
}

/** « oct. 2026 » from `2026-10`. */
fun ganttMonthLabel(month: String): String {
    val number = monthNumber(month)
    return "${MONTH_NAMES[number % 12]} ${number / 12}"
}

/**
 * The rows to draw, from the planning (MAG-527). A project moved later is a fact drawn, not an
 * alert: its bar runs on, with the same colours and no word of reproach. Empty while the income is
 * unknown — the tab's own empty state stays.
 */
fun ganttRows(planning: ProjectPlanning, compact: Boolean = false): List<GanttRow> {
    if (!planning.isIncomeKnown || planning.projects.isEmpty()) return emptyList()
    val horizon = planning.horizonMonths
    val start = monthNumber(planning.startMonth)
    fun column(month: String) = monthNumber(month) - start
    fun clamp(index: Int) = index.coerceIn(0, horizon - 1)

    val rows = mutableListOf<GanttRow>()
    val cushion = planning.cushion
    if (!compact && cushion.state != CushionPlanState.COMPLETE) {
        val complete = cushion.completeMonth
        rows += GanttRow(
            id = "cushion",
            label = "Matelas",
            isCushion = true,
            saving = GanttBar(
                from = 0,
                to = if (complete == null) horizon - 1 else clamp(column(complete)),
                runsOn = complete == null,
                detail = if (complete == null) {
                    "Matelas · complet au-delà de $horizon mois"
                } else {
                    "Matelas · ${if (cushion.state == CushionPlanState.BUILDING) "constitué" else "rechargé"} en ${ganttMonthLabel(complete)}"
                },
            ),
        )
    }

    val projects = if (compact) planning.projects.take(GANTT_COMPACT_PROJECT_COUNT) else planning.projects
    projects.forEach { project ->
        val amounts = "Réuni ${formatCents(project.gatheredCents, planning.currency)} sur ${formatCents(project.amountCents, planning.currency)}"
        val purchaseMonth = project.purchaseMonth
        val purchase = purchaseMonth?.let(::column)
        val savingFrom = clamp(column(project.savingFromMonth))
        val savingTo = if (purchase == null) horizon - 1 else clamp(purchase)
        val loan = project.loan
        val loanEnd = loan?.endMonth?.let(::column)
        val deadline = if (project.dateMode == ProjectDateMode.DEADLINE.key) project.targetOn?.take(7)?.let(::column) else null

        rows += GanttRow(
            id = project.projectId,
            label = project.name,
            saving = GanttBar(
                from = minOf(savingFrom, savingTo),
                to = savingTo,
                runsOn = purchase == null,
                detail = if (purchaseMonth == null) {
                    "${project.name} · épargne dès ${ganttMonthLabel(project.savingFromMonth)} · achat au-delà de $horizon mois · $amounts"
                } else {
                    "${project.name} · épargne de ${ganttMonthLabel(project.savingFromMonth)} à ${ganttMonthLabel(purchaseMonth)} · $amounts"
                },
            ),
            purchase = if (purchaseMonth != null && purchase != null && purchase < horizon) {
                GanttMark(at = clamp(purchase), detail = "${project.name} · achat en ${ganttMonthLabel(purchaseMonth)} · $amounts")
            } else {
                null
            },
            loan = if (loan != null && column(loan.startMonth) < horizon) {
                GanttBar(
                    from = clamp(column(loan.startMonth)),
                    to = if (loanEnd == null) horizon - 1 else clamp(loanEnd),
                    runsOn = loanEnd == null || loanEnd >= horizon,
                    detail = "${project.name} · emprunt dès ${ganttMonthLabel(loan.startMonth)}" +
                        (loan.endMonth?.let { " jusqu'en ${ganttMonthLabel(it)}" } ?: "") +
                        " · ${formatCents(loan.monthlyPaymentCents, planning.currency)} par mois",
                )
            } else {
                null
            },
            deadline = if (deadline != null && deadline in 0 until horizon) {
                GanttMark(at = deadline, detail = "${project.name} · date limite en ${ganttMonthLabel(project.targetOn.orEmpty().take(7))}")
            } else {
                null
            },
            isFixed = project.dateMode == ProjectDateMode.FIXED.key,
            hasUnmetDependency = project.unmetDependencies.isNotEmpty(),
        )
    }
    return rows
}

internal val LABEL_WIDTH_FULL = 112.dp
internal val COLUMN_MIN_FULL = 18.dp
private val LABEL_WIDTH_COMPACT = 84.dp

/**
 * The projects over the next 36 months (MAG-527), read-only: a « Matelas » line while the cushion
 * builds or recharges, then one line per project in priority order — saving bar, purchase mark,
 * loan bar. The names stay on the left while the months scroll sideways on a phone; a tap on a bar
 * says the month and the amounts under the chart. Nothing when there is nothing to draw.
 */
@Composable
fun ProjectGantt(
    planning: ProjectPlanning,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** Shared with the funding chart below it, so both slide together (MAG-528). */
    scrollState: ScrollState = rememberScrollState(),
) {
    val rows = remember(planning, compact) { ganttRows(planning, compact) }
    if (rows.isEmpty()) return

    var detail by remember { mutableStateOf<String?>(null) }
    val horizon = planning.horizonMonths
    val labelWidth = if (compact) LABEL_WIDTH_COMPACT else LABEL_WIDTH_FULL
    val minColumn = if (compact) 8.dp else COLUMN_MIN_FULL
    val rowHeight = if (compact) 22.dp else 32.dp
    val barHeight = if (compact) 8.dp else 14.dp

    Column(modifier = modifier.fillMaxWidth().testTag(UiTags.PROJECT_GANTT), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // From the tablet width the 36 months fill the line; on a phone the track is wider than the screen and scrolls.
            val column = maxOf(minColumn, (maxWidth - labelWidth) / horizon)
            val trackWidth = column * horizon
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(labelWidth)) {
                    if (!compact) Spacer(Modifier.height(AXIS_HEIGHT))
                    rows.forEach { row -> RowLabel(row, rowHeight, compact) }
                }
                Column(Modifier.horizontalScroll(scrollState)) {
                    if (!compact) Axis(planning, column, trackWidth)
                    rows.forEach { row ->
                        TrackRow(row, column, trackWidth, rowHeight, barHeight, onTap = { detail = it })
                    }
                }
            }
        }
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.GANTT_DETAIL),
            )
        }
    }
}

internal val AXIS_HEIGHT = 32.dp

@Composable
private fun RowLabel(row: GanttRow, height: Dp, compact: Boolean) {
    Row(
        modifier = Modifier.height(height).fillMaxWidth().testTag(UiTags.GANTT_ROW),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            row.label,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (row.isFixed) {
            Icon(
                Icons.Default.Lock,
                contentDescription = "Date fixe",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp).testTag(UiTags.GANTT_FIXED),
            )
        }
        if (row.hasUnmetDependency) {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Dépendance non respectée",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(12.dp).testTag(UiTags.GANTT_UNMET),
            )
        }
    }
}

@Composable
internal fun Axis(planning: ProjectPlanning, column: Dp, trackWidth: Dp, todayTag: String = UiTags.GANTT_TODAY) {
    val start = monthNumber(planning.startMonth)
    Row(Modifier.width(trackWidth).height(AXIS_HEIGHT)) {
        repeat(planning.horizonMonths) { index ->
            val total = start + index
            val month = total % 12
            Column(
                modifier = Modifier
                    .width(column)
                    .then(if (index == 0) Modifier.background(MaterialTheme.colorScheme.surfaceVariant).testTag(todayTag) else Modifier)
                    .semantics { contentDescription = ganttMonthLabel("${total / 12}-${(month + 1).toString().padStart(2, '0')}") },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The year shows at the start and at every January, over the month's initial.
                Box(Modifier.height(14.dp).width(column), contentAlignment = Alignment.CenterStart) {
                    if (index == 0 || month == 0) {
                        Text(
                            (total / 12).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                Text(
                    MONTH_INITIALS[month],
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    row: GanttRow,
    column: Dp,
    trackWidth: Dp,
    height: Dp,
    barHeight: Dp,
    onTap: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .width(trackWidth)
            .height(height)
            .background(Color.Transparent),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The current month, in the background of every line.
        Box(Modifier.width(column).height(height).background(colors.surfaceVariant.copy(alpha = 0.5f)))
        row.deadline?.let { mark ->
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
            val lineColor = colors.onSurface
            Box(
                Modifier
                    .offset(x = column * (mark.at + 1) - 2.dp)
                    .width(2.dp)
                    .height(height)
                    .drawBehind {
                        drawLine(lineColor, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = size.width, pathEffect = dash)
                    }
                    .clickable { onTap(mark.detail) }
                    .semantics { contentDescription = mark.detail }
                    .testTag(UiTags.GANTT_DEADLINE),
            )
        }
        row.saving?.let { bar ->
            Bar(
                bar = bar,
                column = column,
                height = barHeight,
                color = if (row.isCushion) colors.outline else colors.primary,
                tag = if (row.isCushion) UiTags.GANTT_CUSHION else UiTags.GANTT_SAVING,
                onTap = onTap,
            )
        }
        row.purchase?.let { mark ->
            Box(
                Modifier
                    .offset(x = column * mark.at + (column - barHeight) / 2)
                    .size(barHeight)
                    .rotate(45f)
                    .background(colors.onSurface)
                    .clickable { onTap(mark.detail) }
                    .semantics { contentDescription = mark.detail }
                    .testTag(UiTags.GANTT_PURCHASE),
            )
        }
        row.loan?.let { bar ->
            Bar(bar = bar, column = column, height = barHeight, color = colors.tertiary, tag = UiTags.GANTT_LOAN, onTap = onTap)
        }
    }
}

@Composable
private fun Bar(
    bar: GanttBar,
    column: Dp,
    height: Dp,
    color: Color,
    tag: String,
    onTap: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .offset(x = column * bar.from)
            .width(column * (bar.to - bar.from + 1))
            .height(height)
            .background(color, RoundedCornerShape(height / 2))
            .clickable { onTap(bar.detail) }
            .semantics { contentDescription = bar.detail }
            .testTag(tag),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (bar.runsOn) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = contentColorFor(color),
                modifier = Modifier.size(height).testTag(UiTags.GANTT_BEYOND),
            )
        }
    }
}
