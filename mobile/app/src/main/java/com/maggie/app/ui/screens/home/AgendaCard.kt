package com.maggie.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.data.model.AgendaHomeState
import com.maggie.app.data.model.AgendaRow
import com.maggie.app.data.model.AgendaRowKind
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.timeline
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.navigation.DeepLinks
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.screens.shared.parseColor
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/** How often the line and « dans 48 min » are recomputed. */
private const val TICK_MS = 20_000L

/** Past rows stay readable: dimmed, not struck out. */
private const val PAST_ALPHA = 0.6f

/** The next event sits on its agenda's colour, this faint. */
private const val NEXT_TINT_ALPHA = 0.13f

/** The owner's zone, which the « maintenant » line is read in. */
private val OWNER_ZONE: ZoneId = ZoneId.of("Europe/Paris")

private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun clockLabel(instant: Instant, zone: ZoneId = OWNER_ZONE): String = clockFormatter.format(instant.atZone(zone))

/** « dans 48 min », « dans 1 h », « dans 1 h 35 »: the minutes rounded up, one at least. */
internal fun untilLabel(start: Instant, now: Instant): String {
    val minutes = maxOf(1, ceil(Duration.between(now, start).toMillis() / 60_000.0).toInt())
    if (minutes < 60) return "dans $minutes min"
    val rest = minutes % 60
    return if (rest == 0) "dans ${minutes / 60} h" else "dans ${minutes / 60} h ${rest.toString().padStart(2, '0')}"
}

/** The time column of a row: the start; « → end » for an event begun the day before; nothing for a whole-day one. */
internal fun rowTimeLabel(event: AgendaHomeEvent, date: String): String {
    if (event.allDay) return ""
    val zone = ZoneId.of(event.timeZone)
    val start = event.start ?: return ""
    if (start.atZone(zone).toLocalDate().toString() < date) {
        return event.end?.let { "→ ${clockLabel(it, zone)}" }.orEmpty()
    }
    return clockLabel(start, zone)
}

/**
 * The Agenda card of the Accueil (MAG-562): the day as a list of hours, the « maintenant » line between what
 * is done and what follows, and the fiche of the event that is touched. The day is the Accueil's selector's. A day with no event draws no card (MAG-594).
 */
@Composable
fun AgendaCard(day: HomeDay, viewModel: AgendaHomeViewModel = koinViewModel()) {
    val ui by viewModel.uiState.collectAsState()
    val navigate = LocalHomeNavigation.current
    val compact = !rememberAppLayout().detailPaneFits

    LaunchedEffect(day) { viewModel.show(day) }

    // The line follows the clock: the server's `now`, moved on by the time elapsed since the read.
    val now by produceState(initialValue = viewModel.nowAt(ui), ui) {
        while (true) {
            value = viewModel.nowAt(ui)
            delay(TICK_MS)
        }
    }

    ReportHomeCard(HomeModules.AGENDA, ui.status)

    AgendaCardContent(
        state = ui.state,
        loading = ui.loading,
        error = ui.error,
        now = now,
        compact = compact,
        onOpenAgenda = { navigate(Screen.Calendar.route) },
        onOpenEvent = { viewModel.open(it.id) },
        onRetry = viewModel::retry,
    )

    ui.opened?.let { event ->
        AgendaEventSheet(
            event = event,
            onDismiss = viewModel::close,
            onOpenInAgenda = {
                viewModel.close()
                navigate(DeepLinks.eventRoute(event.id))
            },
            onLaunch = {
                viewModel.close()
                navigate(Screen.FinanceReviewActivity.route)
            },
        )
    }
}

@Composable
internal fun AgendaCardContent(
    state: AgendaHomeState?,
    loading: Boolean,
    error: Boolean,
    now: Instant?,
    compact: Boolean,
    onOpenAgenda: () -> Unit,
    onOpenEvent: (AgendaHomeEvent) -> Unit,
    onRetry: () -> Unit,
) {
    if (state != null && state.events.isEmpty()) return

    HomeCard(title = "Agenda", module = HomeModules.AGENDA, onOpen = onOpenAgenda) {
        when {
            state == null && error -> {
                Text("L'agenda n'a pas pu être chargé.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Réessayer") }
            }
            state == null && loading -> Text("Chargement…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state == null -> Unit
            else -> {
                val timeline = state.timeline(now, compact)
                timeline.allDay.forEach { AgendaRowItem(it, state.date, now, onOpenEvent) }
                timeline.past.forEach { AgendaRowItem(it, state.date, now, onOpenEvent) }
                if (timeline.hasNowLine && now != null) NowLine(now)
                timeline.following.forEach { AgendaRowItem(it, state.date, now, onOpenEvent) }
                if (timeline.hidden > 0) {
                    TextButton(
                        onClick = onOpenAgenda,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.HOME_AGENDA_SEE_DAY),
                    ) { Text("Voir la journée ›") }
                }
            }
        }
    }
}

@Composable
private fun NowLine(now: Instant) {
    val color = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(UiTags.HOME_AGENDA_NOW_LINE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("maintenant · ${clockLabel(now)}", style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
        HorizontalDivider(modifier = Modifier.weight(1f), thickness = 2.dp, color = color)
    }
}

@Composable
private fun AgendaRowItem(row: AgendaRow, date: String, now: Instant?, onOpen: (AgendaHomeEvent) -> Unit) {
    val event = row.event
    val past = row.kind == AgendaRowKind.PAST
    val next = row.kind == AgendaRowKind.NEXT
    val agendaColor = event.agendaColor?.let(::parseColor) ?: MaterialTheme.colorScheme.primary
    val start = event.start
    val hint = when {
        next && now != null && start != null -> untilLabel(start, now)
        row.kind == AgendaRowKind.CURRENT -> "en cours"
        else -> null
    }
    val details = listOfNotNull(event.locationShort, hint).joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .alpha(if (past) PAST_ALPHA else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (next) agendaColor.copy(alpha = NEXT_TINT_ALPHA) else Color.Transparent)
            .clickable(role = Role.Button) { onOpen(event) }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(UiTags.homeAgendaRow(event.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = rowTimeLabel(event, date).ifEmpty { if (event.allDay) "Journée" else "" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Box(Modifier.size(width = 4.dp, height = 28.dp).clip(RoundedCornerShape(2.dp)).background(agendaColor))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = event.summary + if (past) " · fait" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (next) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (past) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(14.dp))
            }
            if (details.isNotEmpty()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
