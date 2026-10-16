package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.UpcomingDay
import com.maggie.app.data.model.UpcomingEnvelope
import com.maggie.app.data.model.UpcomingItem
import com.maggie.app.data.model.UpcomingMonth
import com.maggie.app.data.model.UpcomingPeriod
import com.maggie.app.data.model.UpcomingProjection
import com.maggie.app.data.model.budgetMarker
import com.maggie.app.data.model.committedFraction
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.spendingShare
import com.maggie.app.data.model.treasuryDate
import com.maggie.app.data.model.upcomingMonthLabel
import com.maggie.app.data.model.upcomingRangeLabel
import com.maggie.app.data.model.upcomingSourceLabel
import com.maggie.app.ui.UiTags
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max

private const val WEEK = "week"
private const val MONTH = "month"

/**
 * « À venir » (MAG-463): the week and the month after this one, the envelopes of that month as
 * adjusted, and what is left toward the projects. A read, nothing here writes.
 */
@Composable
fun UpcomingTab(viewModel: UpcomingViewModel, twoColumns: Boolean) {
    val state by viewModel.uiState.collectAsState()
    val projection = state.projection

    when {
        projection != null -> UpcomingContent(projection, twoColumns, state.error, onRetry = viewModel::refresh)
        state.error != null -> UpcomingError(state.error.orEmpty(), onRetry = viewModel::refresh, modifier = Modifier.fillMaxSize())
        else -> Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun UpcomingError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(UiTags.UPCOMING_ERROR),
        )
        Button(onClick = onRetry, modifier = Modifier.testTag(UiTags.UPCOMING_RETRY)) { Text("Réessayer") }
    }
}

@Composable
private fun UpcomingContent(projection: UpcomingProjection, twoColumns: Boolean, error: String?, onRetry: () -> Unit) {
    val stale: LazyListScope.() -> Unit = {
        // The figures on screen are the last good read: say so rather than hide them.
        if (error != null) item { UpcomingError(error, onRetry) }
    }
    val periods: LazyListScope.() -> Unit = {
        item {
            PeriodCard(
                title = "La semaine suivante",
                range = upcomingRangeLabel(projection.week.from, projection.week.until),
                period = projection.week,
                tag = UiTags.UPCOMING_WEEK,
                kind = WEEK,
                emptyText = "Rien de prévu la semaine suivante.",
            )
        }
        item {
            PeriodCard(
                title = "Le mois suivant",
                range = upcomingMonthLabel(projection.month.month),
                period = projection.month,
                tag = UiTags.UPCOMING_MONTH,
                kind = MONTH,
                emptyText = "Rien de prévu le mois suivant.",
            )
        }
    }
    val budget: LazyListScope.() -> Unit = {
        item { EnvelopesCard(projection.month) }
        item { RemainingCard(projection.month) }
    }

    if (twoColumns) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (error != null) UpcomingError(error, onRetry)
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = periods,
                )
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = budget,
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            stale()
            periods()
            budget()
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}

@Composable
private fun PeriodCard(
    title: String,
    range: String,
    period: UpcomingPeriod,
    tag: String,
    kind: String,
    emptyText: String,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Hint(range)

            if (period.incomes.isEmpty() && period.expenses.isEmpty()) {
                Hint(emptyText, modifier = Modifier.padding(top = 8.dp))
                return@Column
            }

            Text(
                text = signed(period.netCents),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Hint("revenus ${formatCents(period.incomeCents)} − dépenses ${formatCents(period.expenseCents)}")

            DayBars(period.days, tag = UiTags.upcomingDays(kind), weekdays = kind == WEEK)

            ItemSection("Revenus attendus", period.incomes)
            ItemSection("Dépenses prévues", period.expenses)
        }
    }
}

private fun signed(cents: Int): String = if (cents > 0) "+${formatCents(cents)}" else formatCents(cents)

@Composable
private fun ItemSection(title: String, items: List<UpcomingItem>) {
    if (items.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
    items.forEach { item ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = treasuryDate(item.date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(88.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.bodyMedium)
                Hint(upcomingSourceLabel(item.source))
            }
            Text(formatCents(item.amountCents), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** One pair of bars per day, income then expense, on the same scale. */
@Composable
private fun DayBars(days: List<UpcomingDay>, tag: String, weekdays: Boolean) {
    if (days.isEmpty()) return
    val ceiling = days.maxOf { max(it.incomeCents, it.expenseCents) }.coerceAtLeast(1)
    val incomeColor = MaterialTheme.colorScheme.primary
    val expenseColor = MaterialTheme.colorScheme.tertiary
    val description = "Jour par jour : revenus ${formatCents(days.sumOf { it.incomeCents })}, " +
        "dépenses ${formatCents(days.sumOf { it.expenseCents })}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(72.dp)
            .testTag(tag)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            Row(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                DayBar(day.incomeCents.toFloat() / ceiling, incomeColor)
                DayBar(day.expenseCents.toFloat() / ceiling, expenseColor)
            }
        }
    }
    if (weekdays) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            days.forEach { day ->
                Text(
                    text = weekdayInitial(day.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Hint(treasuryDate(days.first().date))
            Hint(treasuryDate(days.last().date))
        }
    }
    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Legend("revenus", incomeColor)
        Legend("dépenses", expenseColor)
    }
}

@Composable
private fun RowScope.DayBar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(fraction.coerceIn(0f, 1f))
            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
            .background(color),
    )
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Hint(label)
    }
}

private fun weekdayInitial(date: String): String =
    runCatching { LocalDate.parse(date).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.FRENCH) }.getOrDefault("")

@Composable
private fun EnvelopesCard(month: UpcomingMonth) {
    Card(modifier = Modifier.fillMaxWidth().testTag(UiTags.UPCOMING_ENVELOPES)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Enveloppes du mois suivant", style = MaterialTheme.typography.titleSmall)
            Hint("${upcomingMonthLabel(month.month)}, telles qu'ajustées")

            if (month.envelopes.isEmpty()) {
                Hint("Aucune enveloppe sur ce mois.", modifier = Modifier.padding(top = 8.dp))
            }
            month.envelopes.forEach { envelope -> EnvelopeLine(envelope) }

            if (month.unbudgetedCents > 0) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(UiTags.UPCOMING_UNBUDGETED)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Hors enveloppe", style = MaterialTheme.typography.bodyMedium)
                        Text(formatCents(month.unbudgetedCents), style = MaterialTheme.typography.bodyMedium)
                    }
                    Bar(fraction = 1f, marker = null, color = MaterialTheme.colorScheme.tertiary)
                    Hint("engagé sans enveloppe", modifier = Modifier.padding(top = 2.dp))
                }
            }

            Hint(
                "Enveloppes : ${formatCents(month.envelopesCents)}",
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun EnvelopeLine(envelope: UpcomingEnvelope) {
    val color = if (envelope.isOvercommitted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .testTag(UiTags.upcomingEnvelope(envelope.categoryId)),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(envelope.categoryName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(formatCents(envelope.amountCents), style = MaterialTheme.typography.bodyMedium)
        }
        Bar(fraction = envelope.committedFraction(), marker = envelope.budgetMarker(), color = color)
        Hint(
            text = if (envelope.isOvercommitted) {
                "${formatCents(envelope.committedCents)} engagés, au-delà de l'enveloppe"
            } else {
                "${formatCents(envelope.committedCents)} engagés"
            },
            color = if (envelope.isOvercommitted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** A horizontal bar filled up to [fraction]; [marker] draws a tick where the budgeted amount ends. */
@Composable
private fun Bar(fraction: Float, marker: Float?, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(modifier = Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
        if (marker != null) {
            Box(modifier = Modifier.fillMaxWidth(marker).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface))
            }
        }
    }
}

/** What the month leaves once its expected income has paid for the projected spending: the share that goes to the projects. */
@Composable
private fun RemainingCard(month: UpcomingMonth) {
    val short = month.remainingCents < 0
    Card(modifier = Modifier.fillMaxWidth().testTag(UiTags.UPCOMING_REMAINING)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Reste vers les projets", style = MaterialTheme.typography.titleSmall)
            Hint(upcomingMonthLabel(month.month))
            Text(
                text = formatCents(month.remainingCents),
                style = MaterialTheme.typography.headlineMedium,
                color = if (short) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
            Bar(
                fraction = month.spendingShare(),
                marker = null,
                color = if (short) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Hint(
                "revenus attendus ${formatCents(month.incomeCents)} − dépenses projetées ${formatCents(month.projectedExpenseCents)}",
                modifier = Modifier.padding(top = 4.dp),
            )
            if (short) {
                Hint("Le mois est court de ${formatCents(-month.remainingCents)}.", modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
