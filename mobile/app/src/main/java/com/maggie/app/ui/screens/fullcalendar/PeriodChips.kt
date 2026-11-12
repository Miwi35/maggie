package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Period
import com.maggie.app.ui.UiTags
import java.time.LocalDate

/** The periods that touch [days], a series' occurrences once each: what the chips of a day or a week list. */
fun periodsOnDays(periods: List<Period>, days: List<LocalDate>): List<Period> =
    periods
        .filter { period -> days.any(period::coversDay) }
        .distinctBy { it.id }
        .sortedWith(compareBy({ it.startsAt() }, { it.name }))

/**
 * The chips under the date in the Day and Week views (MAG-815): hatching, a square in the agenda's colour, the
 * name and « jusqu'au … ». Never rows of the grid — a period has no hour to hang on.
 */
@Composable
fun PeriodChips(
    periods: List<Period>,
    agendas: List<Agenda>,
    onClick: (Period) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (periods.isEmpty()) return
    val fallback = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        periods.forEach { period ->
            val color = periodColor(period, agendas, fallback)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .testTag(UiTags.periodChip(period.id))
                    .clickable { onClick(period) },
            ) {
                HatchedBar(color, Modifier.matchParentSize(), spacing = 6.dp)
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
                    Text(
                        text = period.name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = periodUntilLabel(period),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
