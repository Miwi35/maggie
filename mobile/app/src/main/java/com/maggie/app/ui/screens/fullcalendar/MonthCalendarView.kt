package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.dashboard.parseColor
import com.maggie.app.ui.theme.readableTextOn
import com.maggie.app.util.DateRanges
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

private val SLOT_HEIGHT = 15.dp

/** The day number's row — the same on every day, so the slots below line up from one column to the next. */
private val DAY_NUMBER_HEIGHT = 20.dp
private val DAY_NUMBER_TOP_PADDING = 1.dp
private val COUNTER_LINE_HEIGHT = 10.sp

/** Pre-computed position for one event on one day. */
internal data class EventSlot(
    val event: ExpandedEvent,
    val isStart: Boolean,
    val isEnd: Boolean,
    val slot: Int,
)

/** The first day of [shown] when the grid scrolled to another month than the one [current] is in; null when it only reports where it opened. */
internal fun monthChangeTarget(shown: YearMonth, current: LocalDate): LocalDate? =
    if (shown == YearMonth.from(current)) null else shown.atDay(1)

@Composable
fun MonthCalendarView(
    currentDate: LocalDate,
    events: List<ExpandedEvent>,
    onDateSelected: (LocalDate) -> Unit,
    onEventClick: (ExpandedEvent) -> Unit = {},
    onMonthChange: (YearMonth) -> Unit = {},
    today: LocalDate = DateRanges.todayDate(),
) {
    val latestDate by rememberUpdatedState(currentDate)
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
            .collect { month -> if (monthChangeTarget(month, latestDate) != null) onMonthChange(month) }
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
                isToday = day.date == today,
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

internal fun computeSlots(
    events: List<ExpandedEvent>,
    zone: ZoneId,
): Map<LocalDate, List<EventSlot>> {
    val ranges = events.mapNotNull { event ->
        val days = event.days(zone) ?: return@mapNotNull null
        EventRange(event, days.start, days.endInclusive)
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
                a.event.sortKey.compareTo(b.event.sortKey)
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
    isToday: Boolean,
    onEventClick: (ExpandedEvent) -> Unit,
    onDayClick: () -> Unit,
) {
    val isCurrentMonth = day.position == DayPosition.MonthDate

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.calendarMonthDay(day.date))
            .clickable(enabled = isCurrentMonth) { onDayClick() }
            .then(
                if (!isCurrentMonth)
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                else Modifier
            ),
    ) {
        val cellHeight = maxHeight
        val counterHeight = with(LocalDensity.current) { COUNTER_LINE_HEIGHT.toDp() }

        Column(modifier = Modifier.fillMaxSize()) {
            // The number is a target of its own: a tap on the cell's centre may land on an event.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = DAY_NUMBER_TOP_PADDING)
                    .testTag(UiTags.calendarMonthDate(day.date))
                    .clickable(enabled = isCurrentMonth) { onDayClick() }
                    .height(DAY_NUMBER_HEIGHT)
                    .then(
                        if (isToday) Modifier
                            .size(DAY_NUMBER_HEIGHT)
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

            // As many slots as the cell holds; « +N » takes its own line only when something is left out.
            val room = cellHeight - DAY_NUMBER_TOP_PADDING - DAY_NUMBER_HEIGHT
            val needed = (slots.maxOfOrNull { it.slot } ?: -1) + 1
            val visibleCount =
                if (SLOT_HEIGHT * needed <= room) needed
                else ((room - counterHeight) / SLOT_HEIGHT).toInt().coerceAtLeast(0)

            // Empty spacers keep alignment across days
            for (slotIndex in 0 until visibleCount) {
                val entry = slots.find { it.slot == slotIndex }
                if (entry != null) {
                    ContinuousEventChip(
                        event = entry.event,
                        isStart = entry.isStart,
                        isEnd = entry.isEnd,
                        onClick = { onEventClick(entry.event) },
                        modifier = Modifier.testTag(UiTags.calendarMonthSlot(day.date, slotIndex)),
                    )
                } else {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SLOT_HEIGHT)
                            .testTag(UiTags.calendarMonthSlot(day.date, slotIndex)),
                    )
                }
            }

            val hiddenCount = slots.count { it.slot >= visibleCount }
            if (hiddenCount > 0) {
                Text(
                    text = "+$hiddenCount",
                    fontSize = 9.sp,
                    lineHeight = COUNTER_LINE_HEIGHT,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .height(counterHeight)
                        .testTag(UiTags.calendarMonthMore(day.date)),
                )
            }
        }
    }
}

@Composable
private fun ContinuousEventChip(
    event: ExpandedEvent,
    isStart: Boolean,
    isEnd: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = event.agendaColor?.let { parseColor(it) } ?: MaterialTheme.colorScheme.primary

    val shape = RoundedCornerShape(
        topStart = if (isStart) 3.dp else 0.dp,
        bottomStart = if (isStart) 3.dp else 0.dp,
        topEnd = if (isEnd) 3.dp else 0.dp,
        bottomEnd = if (isEnd) 3.dp else 0.dp,
    )

    Box(
        modifier = modifier
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
                color = readableTextOn(color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
