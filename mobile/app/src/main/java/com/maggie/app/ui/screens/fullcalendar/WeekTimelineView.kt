package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ExpandedEvent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val dayHeaderFormatter = DateTimeFormatter.ofPattern("EEE\nd", Locale.FRENCH)

@Composable
fun WeekTimelineView(
    currentDate: LocalDate,
    events: List<ExpandedEvent>,
    onNavigateForward: () -> Unit,
    onNavigateBackward: () -> Unit,
    onEventClick: (ExpandedEvent) -> Unit = {},
    onEmptySlotClick: (LocalDate, Int) -> Unit = { _, _ -> },
) {
    val monday = currentDate.with(DayOfWeek.MONDAY)
    val days = (0L..6L).map { monday.plusDays(it) }
    val zone = ZoneId.of("Europe/Paris")
    val totalHeight = HOUR_HEIGHT * TIMELINE_HOURS.size

    // Split events by date
    val eventsByDay = remember(events, days) {
        days.associateWith { date ->
            eventsForDate(events, date, zone)
        }
    }

    // All-day events across the week
    val allDayEvents = eventsByDay.values.flatMap { it.first }.distinctBy { it.id }

    var dragAccum by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
        // All-day row
        AllDayRow(
            events = allDayEvents,
            onEventClick = onEventClick,
        )

        // Day headers
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = HOUR_LABEL_WIDTH),
        ) {
            days.forEach { date ->
                val isToday = date == LocalDate.now()
                Text(
                    text = date.format(dayHeaderFormatter).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        HorizontalDivider()

        // Timeline grid
        val scrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            // Hour labels
            HourLabels()

            // Day columns
            days.forEach { date ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(totalHeight),
                ) {
                    TimeGridBackground(modifier = Modifier.fillMaxSize())

                    // Timed events
                    val (_, timedEvents) = eventsByDay[date] ?: (emptyList<ExpandedEvent>() to emptyList())
                    timedEvents.forEach { event ->
                        val (topOffset, height) = calculateEventPosition(event, zone)
                        EventBlock(
                            event = event,
                            topOffset = topOffset,
                            height = height,
                            onClick = { onEventClick(event) },
                        )
                    }

                    // Now indicator (only on today)
                    if (date == LocalDate.now()) {
                        NowIndicator()
                    }
                }
            }
        }
    }
}
