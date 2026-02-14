package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kizitonwose.calendar.compose.ContentHeightMode
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.daysOfWeek
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.ui.screens.dashboard.parseColor
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

private const val MAX_VISIBLE_SLOTS = 3
private val SLOT_HEIGHT = 15.dp

/** Pre-computed position for one event on one day. */
private data class EventSlot(
    val event: ExpandedEvent,
    val isStart: Boolean,
    val isEnd: Boolean,
    val slot: Int,
)

@Composable
fun MonthCalendarView(
    currentDate: LocalDate,
    events: List<ExpandedEvent>,
    onDateSelected: (LocalDate) -> Unit,
    onEventClick: (ExpandedEvent) -> Unit = {},
    onMonthChange: (YearMonth) -> Unit = {},
) {
    val zone = ZoneId.of("Europe/Paris")
    val startMonth = remember { YearMonth.now().minusMonths(24) }
    val endMonth = remember { YearMonth.now().plusMonths(24) }
    val initialMonth = remember { YearMonth.from(currentDate) }
    val daysOfWeek = remember { daysOfWeek(firstDayOfWeek = DayOfWeek.MONDAY) }

    // Compute slot layout for continuous multi-day events
    val slotsByDate = remember(events) {
        computeSlots(events, zone)
    }

    val calendarState = rememberCalendarState(
        startMonth = startMonth,
        endMonth = endMonth,
        firstVisibleMonth = initialMonth,
        firstDayOfWeek = DayOfWeek.MONDAY,
    )

    LaunchedEffect(calendarState) {
        snapshotFlow { calendarState.firstVisibleMonth.yearMonth }
            .collect { onMonthChange(it) }
    }

    HorizontalCalendar(
        state = calendarState,
        contentHeightMode = ContentHeightMode.Fill,
        monthHeader = { DaysOfWeekHeader(daysOfWeek) },
        dayContent = { day ->
            val slots = slotsByDate[day.date].orEmpty()
            FullMonthDayCell(
                day = day,
                slots = slots,
                onEventClick = onEventClick,
                onDayClick = { onDateSelected(day.date) },
            )
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// ---------------------------------------------------------------------------
// Slot computation — assigns a consistent vertical position to each event
// across all days it spans within a week row, so bars look continuous.
// ---------------------------------------------------------------------------

private data class EventRange(
    val event: ExpandedEvent,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

private fun computeSlots(
    events: List<ExpandedEvent>,
    zone: ZoneId,
): Map<LocalDate, List<EventSlot>> {
    val ranges = events.map { event ->
        val startDate = ZonedDateTime.ofInstant(Instant.parse(event.startAt), zone).toLocalDate()
        val endZoned = ZonedDateTime.ofInstant(Instant.parse(event.endAt), zone)
        val endDate = if (event.allDay && endZoned.hour == 0 && endZoned.minute == 0) {
            endZoned.toLocalDate().minusDays(1)
        } else {
            endZoned.toLocalDate()
        }
        EventRange(event, startDate, maxOf(startDate, endDate))
    }

    if (ranges.isEmpty()) return emptyMap()

    // Collect all week starts (Monday) that contain events
    val weekStarts = mutableSetOf<LocalDate>()
    for (range in ranges) {
        var d = range.startDate.with(DayOfWeek.MONDAY)
        val lastWeek = range.endDate.with(DayOfWeek.MONDAY)
        while (d <= lastWeek) {
            weekStarts.add(d)
            d = d.plusWeeks(1)
        }
    }

    val result = mutableMapOf<LocalDate, MutableList<EventSlot>>()

    for (weekStart in weekStarts.sorted()) {
        val weekEnd = weekStart.plusDays(6)

        // Events overlapping this week, sorted:
        // 1. Multi-day events first (startDate != endDate), earliest start first (cascade)
        // 2. Single-day events after: all-day first, then timed by start time
        val weekRanges = ranges
            .filter { it.startDate <= weekEnd && it.endDate >= weekStart }
            .sortedWith(Comparator { a, b ->
                val aMulti = a.startDate != a.endDate
                val bMulti = b.startDate != b.endDate
                if (aMulti != bMulti) return@Comparator if (aMulti) -1 else 1
                if (aMulti) {
                    // Both multi-day: earliest start first, then longest span
                    val startCmp = a.startDate.compareTo(b.startDate)
                    if (startCmp != 0) return@Comparator startCmp
                    return@Comparator (b.endDate.toEpochDay() - b.startDate.toEpochDay())
                        .compareTo(a.endDate.toEpochDay() - a.startDate.toEpochDay())
                }
                // Both single-day: all-day first, then by start time
                if (a.event.allDay != b.event.allDay) return@Comparator if (a.event.allDay) -1 else 1
                a.event.startAt.compareTo(b.event.startAt)
            })

        // Track occupied slots: day → slot → true
        val occupied = (0..6).associate {
            weekStart.plusDays(it.toLong()) to mutableSetOf<Int>()
        }

        for (range in weekRanges) {
            val clampedStart = maxOf(range.startDate, weekStart)
            val clampedEnd = minOf(range.endDate, weekEnd)

            // Find lowest available slot across all days of this event in this week
            var slot = 0
            outer@ while (true) {
                var d = clampedStart
                while (d <= clampedEnd) {
                    if (occupied[d]?.contains(slot) == true) {
                        slot++
                        continue@outer
                    }
                    d = d.plusDays(1)
                }
                break
            }

            // Reserve slot and create entries
            var d = clampedStart
            while (d <= clampedEnd) {
                occupied[d]?.add(slot)
                result.getOrPut(d) { mutableListOf() }.add(
                    EventSlot(
                        event = range.event,
                        isStart = d == clampedStart,
                        isEnd = d == clampedEnd,
                        slot = slot,
                    )
                )
                d = d.plusDays(1)
            }
        }
    }

    return result
}

// ---------------------------------------------------------------------------
// Composables
// ---------------------------------------------------------------------------

@Composable
private fun DaysOfWeekHeader(daysOfWeek: List<DayOfWeek>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        for (dayOfWeek in daysOfWeek) {
            Text(
                text = dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.FRENCH)
                    .replaceFirstChar { it.uppercase() },
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FullMonthDayCell(
    day: CalendarDay,
    slots: List<EventSlot>,
    onEventClick: (ExpandedEvent) -> Unit,
    onDayClick: () -> Unit,
) {
    val isCurrentMonth = day.position == DayPosition.MonthDate
    val isToday = day.date == LocalDate.now()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = isCurrentMonth) { onDayClick() }
            .then(
                if (!isCurrentMonth)
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                else Modifier
            ),
    ) {
        // Day number
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 1.dp)
                .then(
                    if (isToday) Modifier
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = day.date.dayOfMonth.toString(),
                fontSize = 11.sp,
                color = when {
                    isToday -> MaterialTheme.colorScheme.onPrimary
                    !isCurrentMonth -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }

        if (!isCurrentMonth) return@Column

        // Render slots in order — empty spacers keep alignment across days
        val maxSlot = slots.maxOfOrNull { it.slot } ?: -1
        val visibleMax = minOf(maxSlot, MAX_VISIBLE_SLOTS - 1)

        for (slotIndex in 0..visibleMax) {
            val entry = slots.find { it.slot == slotIndex }
            if (entry != null) {
                ContinuousEventChip(
                    event = entry.event,
                    isStart = entry.isStart,
                    isEnd = entry.isEnd,
                    onClick = { onEventClick(entry.event) },
                )
            } else {
                Spacer(modifier = Modifier
                    .fillMaxWidth()
                    .height(SLOT_HEIGHT))
            }
        }

        // Overflow count
        val hiddenCount = slots.count { it.slot >= MAX_VISIBLE_SLOTS }
        if (hiddenCount > 0) {
            Text(
                text = "+$hiddenCount",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

@Composable
private fun ContinuousEventChip(
    event: ExpandedEvent,
    isStart: Boolean,
    isEnd: Boolean,
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
                top = 0.5.dp,
                bottom = 0.5.dp,
            )
            .height(SLOT_HEIGHT - 1.dp)
            .background(color.copy(alpha = 0.85f), shape)
            .clickable { onClick() }
            .padding(horizontal = if (isStart) 2.dp else 0.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (isStart) {
            Text(
                text = event.summary,
                fontSize = 9.sp,
                lineHeight = 10.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
