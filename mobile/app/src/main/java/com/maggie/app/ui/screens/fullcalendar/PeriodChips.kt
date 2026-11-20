package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Period
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.maggieSurface
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The periods that touch [days], a series' occurrences once each: what the chips of a day or a week list. */
fun periodsOnDays(periods: List<Period>, days: List<LocalDate>): List<Period> =
    periods
        .filter { period -> days.any(period::coversDay) }
        .distinctBy { it.id }
        .sortedWith(compareBy({ it.startsAt() }, { it.name }))

/** The chips of a Day view: on today, a period whose end has passed is no longer the one running. */
fun periodsOfTheDay(periods: List<Period>, day: LocalDate, now: Instant, zone: ZoneId): List<Period> {
    val isToday = day == now.atZone(zone).toLocalDate()
    return periodsOnDays(periods, listOf(day))
        .filterNot { isToday && it.endsAt()?.isAfter(now) == false }
}

/**
 * The chips under the date in the Day and Week views (MAG-815): hatching, a square in the agenda's colour, the
 * name and « jusqu'au … ». Never rows of the grid — a period has no hour to hang on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeriodChips(
    periods: List<Period>,
    agendas: List<Agenda>,
    onClick: (Period) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (periods.isEmpty()) return
    val fallback = MaterialTheme.colorScheme.primary
    val muted = maggieSurface().textMuted
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        periods.forEach { period ->
            val color = periodColor(period, agendas, fallback)
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(shape)
                    .border(1.dp, color.copy(alpha = 0.45f), shape)
                    .testTag(UiTags.periodChip(period.id))
                    .clickable { onClick(period) },
            ) {
                HatchedBar(color, Modifier.matchParentSize(), spacing = 6.dp)
                Row(
                    Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
                    Text(
                        text = period.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(text = periodUntilLabel(period), fontSize = 14.sp, color = muted, maxLines = 1)
                }
            }
        }
    }
}
