package com.maggie.app.ui.screens.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kizitonwose.calendar.compose.ContentHeightMode
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.daysOfWeek
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.Task
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val frenchLocale = Locale.FRENCH

private val criticalityColors = mapOf(
    "low" to Color(0xFF4CAF50),
    "medium" to Color(0xFFFF9800),
    "high" to Color(0xFFF44336),
    "critical" to Color(0xFF9C27B0),
)

@Composable
fun CalendarScreen(viewModel: CalendarViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var calendarVisible by remember { mutableStateOf(true) }

    val currentMonth = remember { YearMonth.now() }
    val startMonth = remember { currentMonth.minusMonths(12) }
    val endMonth = remember { currentMonth.plusMonths(12) }
    val daysOfWeek = remember { daysOfWeek(firstDayOfWeek = DayOfWeek.MONDAY) }

    val calendarState = rememberCalendarState(
        startMonth = startMonth,
        endMonth = endMonth,
        firstVisibleMonth = currentMonth,
        firstDayOfWeek = DayOfWeek.MONDAY,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Calendar toggle row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = { calendarVisible = !calendarVisible }) {
                Icon(
                    imageVector = if (calendarVisible) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (calendarVisible) "Masquer le calendrier"
                    else "Afficher le calendrier",
                )
            }
        }

        AnimatedVisibility(visible = calendarVisible) {
            HorizontalCalendar(
                state = calendarState,
                contentHeightMode = ContentHeightMode.Wrap,
                monthHeader = { month ->
                    Column {
                        Text(
                            text = month.yearMonth.month
                                .getDisplayName(TextStyle.FULL, frenchLocale)
                                .replaceFirstChar { it.uppercase() } + " ${month.yearMonth.year}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        DaysOfWeekHeader(daysOfWeek)
                    }
                },
                dayContent = { day ->
                    DayCell(
                        day = day,
                        isSelected = day.date == uiState.selectedDate,
                        hasEvents = uiState.eventsByDate.containsKey(day.date),
                        hasTasks = uiState.tasksByDate.containsKey(day.date),
                        onClick = { viewModel.selectDate(day.date) },
                    )
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Events + tasks for selected day
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.error != null -> {
                    Text(
                        text = "Erreur : ${uiState.error}",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {
                    val dayEvents = uiState.eventsByDate[uiState.selectedDate].orEmpty()
                    val dayTasks = uiState.tasksByDate[uiState.selectedDate].orEmpty()
                    if (dayEvents.isEmpty() && dayTasks.isEmpty()) {
                        Text(
                            text = "Aucun événement ce jour",
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(dayTasks) { task ->
                                TaskCard(task)
                            }
                            items(dayEvents) { event ->
                                EventCard(event)
                            }
                        }
                    }
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
                text = dayOfWeek.getDisplayName(TextStyle.SHORT, frenchLocale)
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
private fun DayCell(
    day: CalendarDay,
    isSelected: Boolean,
    hasEvents: Boolean,
    hasTasks: Boolean,
    onClick: () -> Unit,
) {
    val isCurrentMonth = day.position == DayPosition.MonthDate
    val isToday = day.date == LocalDate.now()

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .then(
                when {
                    isSelected -> Modifier.background(
                        MaterialTheme.colorScheme.primary,
                        CircleShape,
                    )
                    isToday -> Modifier.background(
                        MaterialTheme.colorScheme.primaryContainer,
                        CircleShape,
                    )
                    else -> Modifier
                }
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
            if ((hasEvents || hasTasks) && isCurrentMonth) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (hasEvents) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                ),
                        )
                    }
                    if (hasTasks) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.tertiary,
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}

private fun formatTimeRange(startAt: String, endAt: String, timeZone: String): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
    val zone = ZoneId.of(timeZone)
    val start = ZonedDateTime.parse(startAt).withZoneSameInstant(zone).format(formatter)
    val end = ZonedDateTime.parse(endAt).withZoneSameInstant(zone).format(formatter)
    return "$start - $end"
}

@Composable
fun EventCard(event: Event) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (event.allDay)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = event.summary,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (event.allDay) "Toute la journée"
                else formatTimeRange(event.startAt, event.endAt, event.timeZone),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            event.location?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun TaskCard(task: Task) {
    val critColor = criticalityColors[task.criticality] ?: criticalityColors["low"]!!
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (task.isDone)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(critColor, CircleShape),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.name,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                )
                task.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = task.priority.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = task.criticality.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = critColor,
                    )
                }
            }
        }
    }
}
