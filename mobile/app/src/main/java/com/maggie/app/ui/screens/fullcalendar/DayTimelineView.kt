package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.Period
import com.maggie.app.data.model.Trip
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.shared.parseColor
import com.maggie.app.ui.theme.maggieSurface
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val EVENT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The day as a list (MAG-859): the all-day events on top, then the timed events and the trips in the order of
 * the clock — a trip sits at its departure, before its event on a tie, and overlaps nothing.
 */
@Composable
fun DayTimelineView(
    currentDate: LocalDate,
    events: List<ExpandedEvent>,
    onNavigateForward: () -> Unit,
    onNavigateBackward: () -> Unit,
    onEventClick: (ExpandedEvent) -> Unit = {},
    periods: List<Period> = emptyList(),
    agendas: List<Agenda> = emptyList(),
    onPeriodClick: (Period) -> Unit = {},
    trips: List<Trip> = emptyList(),
    onTripGo: (Trip) -> Unit = {},
    clock: () -> Instant = Instant::now,
) {
    val zone = ZoneId.of("Europe/Paris")
    val (allDayEvents, timedEvents) = remember(events, currentDate) {
        eventsForDate(events, currentDate, zone)
    }

    // Read when the day is drawn or its trips change: the « Parti à » switch is not a ticking clock.
    val now = remember(trips, events) { clock() }
    val rows = remember(allDayEvents, timedEvents, trips, now) { dayRows(allDayEvents + timedEvents, trips, now) }

    var dragAccum by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.calendarDayView(currentDate))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(dragAccum) > 100f) {
                            if (dragAccum > 0) onNavigateBackward() else onNavigateForward()
                        }
                        dragAccum = 0f
                    },
                    onDragCancel = { dragAccum = 0f },
                ) { _, dragAmount ->
                    dragAccum += dragAmount
                }
            },
    ) {
        PeriodChips(periodsOnDays(periods, listOf(currentDate)), agendas, onPeriodClick)

        AllDayRow(
            events = allDayEvents,
            tag = UiTags.calendarAllDay(currentDate),
            onEventClick = onEventClick,
        )

        if (allDayEvents.isNotEmpty()) {
            HorizontalDivider()
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(rows, key = { row ->
                when (row) {
                    is DayRow.Event -> "event-${row.event.id}-${row.event.startAt}"
                    is DayRow.Trip -> "trip-${row.trip.id}"
                }
            }) { row ->
                when (row) {
                    is DayRow.Event -> DayEventRow(
                        row = row,
                        zone = zone,
                        tag = UiTags.calendarEvent(currentDate),
                        onClick = { onEventClick(row.event) },
                    )
                    is DayRow.Trip -> TripLine(
                        trip = row.trip,
                        now = now,
                        zone = zone,
                        isNext = row.next,
                        event = row.event,
                        onGo = { onTripGo(row.trip) },
                    )
                }
            }
        }
    }
}

/** An event of the day: its time, a bar in its agenda's colour, its title, then « lieu · agenda ». */
@Composable
private fun DayEventRow(row: DayRow.Event, zone: ZoneId, tag: String, onClick: () -> Unit) {
    val event = row.event
    val muted = maggieSurface().textMuted
    val color = event.agendaColor?.let { parseColor(it) } ?: MaterialTheme.colorScheme.primary
    val weight = if (row.highlighted) FontWeight.Medium else FontWeight.Normal
    val start = Instant.parse(event.startAt).atZone(zone).toLocalTime().format(EVENT_TIME)
    val subtitle = listOfNotNull(event.location?.takeIf { it.isNotBlank() }, event.agendaName?.takeIf { it.isNotBlank() })
        .joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (row.past) 0.6f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .heightIn(min = 60.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = start,
            fontSize = 15.sp,
            fontWeight = weight,
            style = TextStyle(fontFeatureSettings = "tnum"),
            color = if (row.past) muted else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(48.dp),
        )
        Box(Modifier.size(width = 4.dp, height = 32.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Column(Modifier.weight(1f)) {
            // Tag and click sit on the Text itself: on a parent the merged node carries the id but not the text.
            Text(
                text = event.title,
                fontSize = 15.sp,
                fontWeight = weight,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(tag).clickable(onClick = onClick),
            )
            if (subtitle.isNotEmpty()) {
                Text(text = subtitle, fontSize = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
