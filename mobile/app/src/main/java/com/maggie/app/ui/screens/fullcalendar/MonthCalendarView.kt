package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

private const val MAX_VISIBLE_EVENTS = 2

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

    // Group events by ALL dates they span (multi-day support)
    val eventsByDate = remember(events) {
        val map = mutableMapOf<LocalDate, MutableList<ExpandedEvent>>()
        events.forEach { event ->
            val startDate = ZonedDateTime.ofInstant(Instant.parse(event.startAt), zone).toLocalDate()
            val endZoned = ZonedDateTime.ofInstant(Instant.parse(event.endAt), zone)
            val endDate = if (event.allDay && endZoned.hour == 0 && endZoned.minute == 0) {
                endZoned.toLocalDate().minusDays(1)
            } else {
                endZoned.toLocalDate()
            }
            val actualEnd = if (endDate < startDate) startDate else endDate
            var date = startDate
            while (date <= actualEnd) {
                map.getOrPut(date) { mutableListOf() }.add(event)
                date = date.plusDays(1)
            }
        }
        map
    }

    val calendarState = rememberCalendarState(
        startMonth = startMonth,
        endMonth = endMonth,
        firstVisibleMonth = initialMonth,
        firstDayOfWeek = DayOfWeek.MONDAY,
    )

    // Sync visible month with ViewModel
    LaunchedEffect(calendarState) {
        snapshotFlow { calendarState.firstVisibleMonth.yearMonth }
            .collect { visibleMonth ->
                onMonthChange(visibleMonth)
            }
    }

    HorizontalCalendar(
        state = calendarState,
        contentHeightMode = ContentHeightMode.Fill,
        monthHeader = { DaysOfWeekHeader(daysOfWeek) },
        dayContent = { day ->
            val dayEvents = eventsByDate[day.date].orEmpty()
            FullMonthDayCell(
                day = day,
                events = dayEvents,
                zone = zone,
                onEventClick = onEventClick,
                onDayClick = { onDateSelected(day.date) },
            )
        },
        modifier = Modifier.fillMaxSize(),
    )
}

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
    events: List<ExpandedEvent>,
    zone: ZoneId,
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
                .padding(top = 2.dp)
                .then(
                    if (isToday) Modifier
                        .size(22.dp)
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

        // Event chips
        if (isCurrentMonth) {
            val visible = events.take(MAX_VISIBLE_EVENTS)
            val overflow = events.size - MAX_VISIBLE_EVENTS

            visible.forEach { event ->
                EventChip(
                    event = event,
                    zone = zone,
                    onClick = { onEventClick(event) },
                )
            }

            if (overflow > 0) {
                Text(
                    text = "+$overflow",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun EventChip(
    event: ExpandedEvent,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    val color = event.agendaColor?.let { parseColor(it) } ?: MaterialTheme.colorScheme.primary

    val label = if (!event.allDay) {
        val start = ZonedDateTime.ofInstant(Instant.parse(event.startAt), zone)
        "${String.format("%02d:%02d", start.hour, start.minute)} ${event.summary}"
    } else {
        event.summary
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1.dp, vertical = 0.5.dp)
            .height(14.dp)
            .background(color.copy(alpha = 0.85f), RoundedCornerShape(2.dp))
            .clickable { onClick() }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            lineHeight = 10.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
