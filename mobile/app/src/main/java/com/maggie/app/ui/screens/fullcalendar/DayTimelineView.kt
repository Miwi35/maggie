package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.ui.UiTags
import com.maggie.app.util.DateRanges
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
) {
    val zone = ZoneId.of("Europe/Paris")
    val totalHeight = HOUR_HEIGHT * TIMELINE_HOURS.size
    val (allDayEvents, timedEvents) = remember(events, currentDate) {
        eventsForDate(events, currentDate, zone)
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

                // Now indicator
                if (currentDate == DateRanges.todayDate()) {
                    NowIndicator()
                }
            }
        }
    }
}
