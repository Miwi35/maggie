package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.dashboard.parseColor
import com.maggie.app.util.DateRanges
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val dayHeaderFormatter = DateTimeFormatter.ofPattern("EEE\nd", Locale.FRENCH)
private val SPANNING_ROW_HEIGHT = 18.dp

@Composable
fun WeekTimelineView(
    currentDate: LocalDate,
    events: List<ExpandedEvent>,
    onNavigateForward: () -> Unit,
    onNavigateBackward: () -> Unit,
    onEventClick: (ExpandedEvent) -> Unit = {},
    onDayClick: (LocalDate) -> Unit = {},
    onEmptySlotClick: (LocalDate, Int) -> Unit = { _, _ -> },
) {
    val monday = currentDate.with(DayOfWeek.MONDAY)
    val days = (0L..6L).map { monday.plusDays(it) }
    val zone = ZoneId.of("Europe/Paris")
    val totalHeight = HOUR_HEIGHT * TIMELINE_HOURS.size

    // Separate spanning events (all-day or multi-day) from single-day timed events
    val (spanningEvents, timedOnlyEvents) = remember(events) {
        events.partition { event ->
            if (event.allDay) return@partition true
            val startDate = ZonedDateTime.ofInstant(Instant.parse(event.startAt), zone).toLocalDate()
            val endDate = ZonedDateTime.ofInstant(Instant.parse(event.endAt), zone).toLocalDate()
            endDate > startDate
        }
    }

    // Compute spanning event slots
    val spanSlots = remember(spanningEvents, days) {
        computeWeekSpanSlots(spanningEvents, days, zone)
    }

    // Timed events per day (only single-day non-all-day events)
    val timedByDay = remember(timedOnlyEvents, days) {
        days.associateWith { date ->
            timedOnlyEvents.filter { event ->
                val startDate = ZonedDateTime.ofInstant(Instant.parse(event.startAt), zone).toLocalDate()
                startDate == date
            }
        }
    }

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
        // Day headers
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = HOUR_LABEL_WIDTH),
        ) {
            days.forEach { date ->
                val isToday = date == DateRanges.todayDate()
                Text(
                    text = date.format(dayHeaderFormatter).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(UiTags.calendarDay(date))
                        .clickable { onDayClick(date) },
                )
            }
        }

        // Spanning events section (all-day + multi-day)
        if (spanSlots.isNotEmpty()) {
            val maxSlot = spanSlots.maxOf { it.slot }
            for (slotIdx in 0..maxSlot) {
                val slotEvents = spanSlots.filter { it.slot == slotIdx }.sortedBy { it.startDayIndex }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = HOUR_LABEL_WIDTH)
                        .height(SPANNING_ROW_HEIGHT),
                ) {
                    var currentDay = 0
                    for (se in slotEvents) {
                        if (se.startDayIndex > currentDay) {
                            Spacer(modifier = Modifier.weight((se.startDayIndex - currentDay).toFloat()))
                        }
                        val span = se.endDayIndex - se.startDayIndex + 1
                        Box(modifier = Modifier.weight(span.toFloat())) {
                            SpanningEventBar(
                                event = se.event,
                                isStart = se.isStart,
                                isEnd = se.isEnd,
                                tag = UiTags.calendarSpan(days[se.startDayIndex], days[se.endDayIndex]),
                                onClick = { onEventClick(se.event) },
                            )
                        }
                        currentDay = se.endDayIndex + 1
                    }
                    if (currentDay < days.size) {
                        Spacer(modifier = Modifier.weight((days.size - currentDay).toFloat()))
                    }
                }
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
            HourLabels()

            days.forEach { date ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(totalHeight),
                ) {
                    TimeGridBackground(modifier = Modifier.fillMaxSize())

                    val dayTimedEvents = timedByDay[date].orEmpty()
                    dayTimedEvents.forEach { event ->
                        val (topOffset, height) = calculateEventPosition(event, zone)
                        EventBlock(
                            event = event,
                            topOffset = topOffset,
                            height = height,
                            onClick = { onEventClick(event) },
                        )
                    }

                    if (date == DateRanges.todayDate()) {
                        NowIndicator()
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Spanning event slot computation
// ---------------------------------------------------------------------------

private data class SpanSlot(
    val event: ExpandedEvent,
    val startDayIndex: Int,
    val endDayIndex: Int,
    val isStart: Boolean,
    val isEnd: Boolean,
    val slot: Int,
)

private fun computeWeekSpanSlots(
    events: List<ExpandedEvent>,
    days: List<LocalDate>,
    zone: ZoneId,
): List<SpanSlot> {
    data class EventDayRange(
        val event: ExpandedEvent,
        val startIdx: Int,
        val endIdx: Int,
        val realStart: LocalDate,
        val realEnd: LocalDate,
    )

    val ranges = events.mapNotNull { event ->
        val startDate = ZonedDateTime.ofInstant(Instant.parse(event.startAt), zone).toLocalDate()
        val endZoned = ZonedDateTime.ofInstant(Instant.parse(event.endAt), zone)
        val endDate = if (event.allDay && endZoned.hour == 0 && endZoned.minute == 0) {
            endZoned.toLocalDate().minusDays(1)
        } else {
            endZoned.toLocalDate()
        }
        val actualEnd = maxOf(startDate, endDate)

        val startIdx = days.indexOfFirst { it >= startDate }.let { if (it == -1) return@mapNotNull null else it }
        val endIdx = days.indexOfLast { it <= actualEnd }.let { if (it == -1) return@mapNotNull null else it }
        if (startIdx > endIdx) return@mapNotNull null

        EventDayRange(event, startIdx, endIdx, startDate, actualEnd)
    }.sortedWith(
        compareByDescending<EventDayRange> { it.endIdx - it.startIdx }
            .thenBy { it.startIdx }
            .thenBy { it.event.startAt }
    )

    if (ranges.isEmpty()) return emptyList()

    val occupied = Array(days.size) { mutableSetOf<Int>() }
    val result = mutableListOf<SpanSlot>()

    for (range in ranges) {
        var slot = 0
        while (true) {
            val available = (range.startIdx..range.endIdx).all { slot !in occupied[it] }
            if (available) break
            slot++
        }
        for (i in range.startIdx..range.endIdx) {
            occupied[i].add(slot)
        }
        result.add(
            SpanSlot(
                event = range.event,
                startDayIndex = range.startIdx,
                endDayIndex = range.endIdx,
                isStart = range.realStart >= days[range.startIdx],
                isEnd = range.realEnd <= days[range.endIdx],
                slot = slot,
            )
        )
    }

    return result
}

// ---------------------------------------------------------------------------
// Spanning event bar composable
// ---------------------------------------------------------------------------

@Composable
private fun SpanningEventBar(
    event: ExpandedEvent,
    isStart: Boolean,
    isEnd: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val color = event.agendaColor?.let { parseColor(it) } ?: MaterialTheme.colorScheme.primary

    val shape = RoundedCornerShape(
        topStart = if (isStart) 3.dp else 0.dp,
        bottomStart = if (isStart) 3.dp else 0.dp,
        topEnd = if (isEnd) 3.dp else 0.dp,
        bottomEnd = if (isEnd) 3.dp else 0.dp,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isStart) 1.dp else 0.dp,
                end = if (isEnd) 1.dp else 0.dp,
                top = 1.dp,
                bottom = 1.dp,
            )
            .height(SPANNING_ROW_HEIGHT - 2.dp)
            .background(color.copy(alpha = 0.85f), shape),
    ) {
        Text(
            text = event.summary,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxSize()
                .testTag(tag)
                .clickable { onClick() }
                .padding(horizontal = 4.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
    }
}
