package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kizitonwose.calendar.compose.ContentHeightMode
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.daysOfWeek
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.ui.screens.dashboard.DashboardEventItem
import com.maggie.app.ui.screens.dashboard.parseColor
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

@Composable
fun MonthCalendarView(
    currentDate: LocalDate,
    events: List<ExpandedEvent>,
    onDateSelected: (LocalDate) -> Unit,
    onEventClick: (ExpandedEvent) -> Unit = {},
) {
    var selectedDate by remember { mutableStateOf(currentDate) }
    val zone = ZoneId.of("Europe/Paris")

    val currentMonth = remember(currentDate) { YearMonth.from(currentDate) }
    val startMonth = remember(currentMonth) { currentMonth.minusMonths(12) }
    val endMonth = remember(currentMonth) { currentMonth.plusMonths(12) }
    val daysOfWeek = remember { daysOfWeek(firstDayOfWeek = DayOfWeek.MONDAY) }

    // Group events by date
    val eventsByDate = remember(events) {
        events.groupBy { event ->
            ZonedDateTime.ofInstant(Instant.parse(event.startAt), zone).toLocalDate()
        }
    }

    val calendarState = rememberCalendarState(
        startMonth = startMonth,
        endMonth = endMonth,
        firstVisibleMonth = currentMonth,
        firstDayOfWeek = DayOfWeek.MONDAY,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalCalendar(
            state = calendarState,
            contentHeightMode = ContentHeightMode.Wrap,
            monthHeader = { month ->
                DaysOfWeekHeader(daysOfWeek)
            },
            dayContent = { day ->
                val dayEvents = eventsByDate[day.date].orEmpty()
                MonthDayCell(
                    day = day,
                    isSelected = day.date == selectedDate,
                    events = dayEvents,
                    onClick = {
                        selectedDate = day.date
                        onDateSelected(day.date)
                    },
                )
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Events for selected date
        val selectedEvents = eventsByDate[selectedDate].orEmpty()
        if (selectedEvents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Aucun événement",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(selectedEvents) { event ->
                    DashboardEventItem(
                        event = event,
                        onClick = { onEventClick(event) },
                    )
                }
            }
        }
    }
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
private fun MonthDayCell(
    day: CalendarDay,
    isSelected: Boolean,
    events: List<ExpandedEvent>,
    onClick: () -> Unit,
) {
    val isCurrentMonth = day.position == DayPosition.MonthDate
    val isToday = day.date == LocalDate.now()

    // Collect unique agenda colors for dot indicators
    val agendaColors = events
        .mapNotNull { it.agendaColor?.let { hex -> parseColor(hex) } }
        .distinct()
        .take(3)

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .then(
                when {
                    isSelected -> Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                    isToday -> Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    else -> Modifier
                },
            )
            .clickable(enabled = isCurrentMonth, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = day.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    !isCurrentMonth -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (events.isNotEmpty() && isCurrentMonth) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    agendaColors.ifEmpty { listOf(MaterialTheme.colorScheme.primary) }.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else color,
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}
