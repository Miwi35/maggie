package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.ui.screens.dashboard.parseColor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

val HOUR_HEIGHT = 60.dp
val TIMELINE_START_HOUR = 7
val TIMELINE_END_HOUR = 24
val TIMELINE_HOURS = (TIMELINE_START_HOUR until TIMELINE_END_HOUR).toList()
val HOUR_LABEL_WIDTH = 48.dp

@Composable
fun HourLabels(modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(HOUR_LABEL_WIDTH)) {
        for (hour in TIMELINE_HOURS) {
            Box(
                modifier = Modifier.height(HOUR_HEIGHT),
                contentAlignment = Alignment.TopEnd,
            ) {
                Text(
                    text = "${hour.toString().padStart(2, '0')}:00",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
    }
}

@Composable
fun TimeGridBackground(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        for (hour in TIMELINE_HOURS) {
            Box(modifier = Modifier.height(HOUR_HEIGHT)) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
fun EventBlock(
    event: ExpandedEvent,
    topOffset: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    tag: String? = null,
    onClick: () -> Unit = {},
) {
    val bgColor = event.agendaColor?.let { parseColor(it) }
        ?: MaterialTheme.colorScheme.primaryContainer

    Box(
        modifier = modifier
            .offset(y = topOffset)
            .height(height.coerceAtLeast(20.dp))
            .fillMaxWidth()
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor.copy(alpha = 0.85f))
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Text(
            text = event.summary,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = if (height > 30.dp) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
        )
    }
}

@Composable
fun NowIndicator(modifier: Modifier = Modifier) {
    val now = LocalTime.now()
    val minutesSinceStart = (now.hour - TIMELINE_START_HOUR) * 60 + now.minute
    if (minutesSinceStart < 0) return

    val topOffset = (minutesSinceStart.toFloat() / 60f) * HOUR_HEIGHT.value

    Box(
        modifier = modifier.offset(y = topOffset.dp),
    ) {
        HorizontalDivider(
            color = Color.Red,
            thickness = 2.dp,
        )
    }
}

@Composable
fun AllDayRow(
    events: List<ExpandedEvent>,
    modifier: Modifier = Modifier,
    onEventClick: (ExpandedEvent) -> Unit = {},
) {
    if (events.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HOUR_LABEL_WIDTH, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        events.forEach { event ->
            val bgColor = event.agendaColor?.let { parseColor(it) }
                ?: MaterialTheme.colorScheme.secondaryContainer
            Surface(
                color = bgColor.copy(alpha = 0.85f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.clickable { onEventClick(event) },
            ) {
                Text(
                    text = event.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Calculate the Y offset and height for an event block based on its time.
 */
fun calculateEventPosition(
    event: ExpandedEvent,
    zone: ZoneId = ZoneId.of("Europe/Paris"),
): Pair<Dp, Dp> {
    val startZdt = ZonedDateTime.ofInstant(Instant.parse(event.startAt), zone)
    val endZdt = ZonedDateTime.ofInstant(Instant.parse(event.endAt), zone)

    val startMinutes = (startZdt.hour - TIMELINE_START_HOUR) * 60 + startZdt.minute
    val endMinutes = (endZdt.hour - TIMELINE_START_HOUR) * 60 + endZdt.minute
    val durationMinutes = (endMinutes - startMinutes).coerceAtLeast(15)

    val topOffset = (startMinutes.toFloat() / 60f) * HOUR_HEIGHT.value
    val height = (durationMinutes.toFloat() / 60f) * HOUR_HEIGHT.value

    return topOffset.dp to height.dp
}

/**
 * Get events for a specific date, split into all-day and timed.
 * Multi-day events appear on every day they span.
 */
fun eventsForDate(
    events: List<ExpandedEvent>,
    date: LocalDate,
    zone: ZoneId = ZoneId.of("Europe/Paris"),
): Pair<List<ExpandedEvent>, List<ExpandedEvent>> {
    val dayEvents = events.filter { event ->
        val startDate = ZonedDateTime.ofInstant(Instant.parse(event.startAt), zone).toLocalDate()
        val endZoned = ZonedDateTime.ofInstant(Instant.parse(event.endAt), zone)
        val endDate = if (event.allDay && endZoned.hour == 0 && endZoned.minute == 0) {
            endZoned.toLocalDate().minusDays(1)
        } else {
            endZoned.toLocalDate()
        }
        date in startDate..maxOf(startDate, endDate)
    }
    return dayEvents.partition { it.allDay }
}
