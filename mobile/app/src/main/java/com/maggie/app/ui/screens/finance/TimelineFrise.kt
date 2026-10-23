package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags

/** « 36 mois · 20 ans »: the two horizons of the planning card (MAG-554). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanningHorizonToggle(selected: PlanningHorizon, onSelect: (PlanningHorizon) -> Unit, modifier: Modifier = Modifier) {
    val horizons = PlanningHorizon.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        horizons.forEachIndexed { index, horizon ->
            SegmentedButton(
                selected = selected == horizon,
                onClick = { onSelect(horizon) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = horizons.size),
                modifier = Modifier.testTag(UiTags.timelineHorizon(if (horizon == PlanningHorizon.YEARS_20) 240 else 36)),
            ) { Text(horizon.label, maxLines = 1) }
        }
    }
}

/**
 * The 20-year frise (MAG-554): the events of the scenario the Dashboard follows month by month up to
 * independence and, when one is picked, those of a second scenario under it — the same axis, the same
 * colours — with the assumptions the figures rest on. Facts only: the gap of N is the API's sentence,
 * and no scenario is called better. The coverage curve is not in the frise's data: the date N is marked
 * on each track instead.
 */
@Composable
fun TimelineFrise(
    state: TimelineUiState,
    onCompare: (String?) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeline = state.timeline
    val frise = remember(timeline) { timeline?.let(::friseOf) }

    Column(modifier = modifier.fillMaxWidth().testTag(UiTags.TIMELINE_FRISE), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            frise != null -> {
                CompareChoices(state.compareId, onCompare)
                state.comparisonNotice?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(UiTags.TIMELINE_NOTICE),
                    )
                }
                if (state.error != null) FriseError(state.error, onRetry)
                FriseBody(frise)
            }
            state.isLoading -> Box(
                Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag(UiTags.TIMELINE_LOADING),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            state.error != null -> FriseError(state.error, onRetry)
        }
    }
}

@Composable
private fun FriseError(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(UiTags.TIMELINE_ERROR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("Réessayer") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompareChoices(compareId: String?, onCompare: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Comparer avec", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = compareId == null,
                onClick = { onCompare(null) },
                label = { Text("Aucun") },
                modifier = Modifier.testTag(UiTags.timelineCompare(null)),
            )
            AUTOMATIC_SCENARIOS.forEach { choice ->
                FilterChip(
                    selected = compareId == choice.id,
                    onClick = { onCompare(choice.id) },
                    label = { Text(choice.label) },
                    modifier = Modifier.testTag(UiTags.timelineCompare(choice.id)),
                )
            }
        }
    }
}

@Composable
private fun FriseBody(frise: Frise) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Legend(frise.legend)
        frise.lines.forEach { line -> FriseLineBlock(frise, line) }
        frise.difference?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(UiTags.TIMELINE_DIFFERENCE))
        }
        Text(
            frise.assumptions,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(UiTags.TIMELINE_ASSUMPTIONS),
        )
    }
}

@Composable
private fun Legend(items: List<FriseLegendItem>) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(10.dp).background(Color(item.color), CircleShape))
                Text(item.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun FriseLineBlock(frise: Frise, line: FriseLine) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${line.role} · ${line.name}", style = MaterialTheme.typography.titleSmall)
        Text(line.nLabel, style = MaterialTheme.typography.bodyMedium)
        FriseTrack(frise, line)
        if (line.events.isEmpty()) {
            Text("Aucun événement d'ici 20 ans.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        line.events.forEach { event -> FriseEventRow(event, line.isCompared) }
    }
}

private val TRACK_HEIGHT = 36.dp
private val COLUMN_MIN = 4.dp
private val DOT = 8.dp

/**
 * The 240 months on one line: a dot per event in its category's colour and, when N is reached, a
 * marker on its month. On a phone the track is wider than the screen and scrolls; from the tablet
 * width the 20 years fill the line. Every five years, the year.
 */
@Composable
private fun FriseTrack(frise: Frise, line: FriseLine) {
    val axis = MaterialTheme.colorScheme.outlineVariant
    val marker = MaterialTheme.colorScheme.onSurface
    val horizon = frise.horizon
    val startYear = monthNumber(frise.startMonth) / 12
    val startMonth = monthNumber(frise.startMonth) % 12

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val column: Dp = maxOf(COLUMN_MIN, maxWidth / horizon)
        val width = column * horizon
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .semantics { contentDescription = "${line.role} · ${line.name} : ${line.events.size} événements sur 20 ans. ${line.nLabel}." }
                .testTag(if (line.isCompared) UiTags.TIMELINE_COMPARED_TRACK else UiTags.TIMELINE_TRACK),
        ) {
            Box(Modifier.width(width).height(TRACK_HEIGHT)) {
                Canvas(Modifier.width(width).height(TRACK_HEIGHT)) {
                    val mid = size.height / 2
                    drawLine(axis, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 2.dp.toPx())
                    line.nIndex?.let { index ->
                        val x = (index + 0.5f) * column.toPx()
                        drawLine(marker, Offset(x, 2.dp.toPx()), Offset(x, size.height - 2.dp.toPx()), strokeWidth = 2.dp.toPx())
                    }
                }
                // The N marker, as a node a journey and a screen reader can find.
                line.nIndex?.let { index ->
                    Box(
                        Modifier
                            .offset(x = column * index)
                            .width(column)
                            .height(TRACK_HEIGHT)
                            .semantics { contentDescription = line.nLabel }
                            .testTag(UiTags.TIMELINE_N_MARKER),
                    )
                }
                line.events.forEach { event ->
                    Box(
                        Modifier
                            .offset(x = column * event.monthIndex + (column - DOT) / 2, y = (TRACK_HEIGHT - DOT) / 2)
                            .size(DOT)
                            .background(Color(event.color), CircleShape),
                    )
                }
            }
            Box(Modifier.width(width).height(16.dp)) {
                // The start year, then every fifth January after it.
                val labels = buildList {
                    add(0 to startYear)
                    var index = (12 - startMonth) % 12
                    while (index < horizon) {
                        val year = startYear + (startMonth + index) / 12
                        if (year > startYear && (year - startYear) % 5 == 0) add(index to year)
                        index += 12
                    }
                }
                labels.forEach { (index, year) ->
                    Text(
                        year.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.offset(x = column * index),
                    )
                }
            }
        }
    }
}

@Composable
private fun FriseEventRow(event: FriseEvent, compared: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(UiTags.timelineEvent(event.type, compared)),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).background(Color(event.color), CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                event.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (event.isIndependence) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                "${event.monthLabel} · ${event.categoryLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            event.sublabel?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            event.figures?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
