package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.Trip
import com.maggie.app.ui.UiTags
import com.maggie.app.util.DateRanges
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

@Composable
fun DayTimelineView(
    currentDate: LocalDate,
    events: List<ExpandedEvent>,
    onNavigateForward: () -> Unit,
    onNavigateBackward: () -> Unit,
    onEventClick: (ExpandedEvent) -> Unit = {},
    onEmptySlotClick: (Int) -> Unit = {},
    trips: List<Trip> = emptyList(),
    onTripGo: (Trip) -> Unit = {},
    clock: () -> Instant = Instant::now,
) {
    val zone = ZoneId.of("Europe/Paris")
    val totalHeight = HOUR_HEIGHT * TIMELINE_HOURS.size
    val (allDayEvents, timedEvents) = remember(events, currentDate) {
        eventsForDate(events, currentDate, zone)
    }

    // Read when the day is drawn or its trips change: the « Parti à » switch is not a ticking clock.
    val now = remember(trips) { clock() }
    val tripByEvent = remember(trips) { trips.associateBy { it.eventId } }
    val nextTripId = remember(trips, now) {
        trips.filter { it.departInstant?.isAfter(now) == true }.minByOrNull { it.departInstant!! }?.id
    }

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
        // All-day events
        AllDayRow(
            events = allDayEvents,
            tag = UiTags.calendarAllDay(currentDate),
            onEventClick = onEventClick,
        )

        if (allDayEvents.isNotEmpty()) {
            HorizontalDivider()
        }

        // Timeline grid
        val scrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            HourLabels()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(totalHeight),
            ) {
                TimeGridBackground(modifier = Modifier.fillMaxSize())

                // Timed events
                timedEvents.forEach { event ->
                    val (topOffset, height) = calculateEventPosition(event, currentDate, zone)
                    EventBlock(
                        event = event,
                        topOffset = topOffset,
                        height = height,
                        tag = UiTags.calendarEvent(currentDate),
                        onClick = { onEventClick(event) },
                    )
                }

                // The trip line sits under the event it leads to
                timedEvents.forEach { event ->
                    val trip = tripByEvent[event.id] ?: return@forEach
                    val (topOffset, height) = calculateEventPosition(event, currentDate, zone)
                    TripLine(
                        trip = trip,
                        now = now,
                        zone = zone,
                        isNext = trip.id == nextTripId,
                        modifier = Modifier
                            .offset(y = topOffset + height.coerceAtLeast(20.dp))
                            .padding(horizontal = 4.dp),
                        onGo = { onTripGo(trip) },
                    )
                }

                // Now indicator
                if (currentDate == DateRanges.todayDate()) {
                    NowIndicator()
                }
            }
        }
    }
}
