package com.maggie.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.ACTIVITY_SPORT
import com.maggie.app.ui.screens.health.sport.SportResumeViewModel
import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.data.model.AgendaHomeState
import com.maggie.app.data.model.AgendaRow
import com.maggie.app.data.model.AgendaTimeline
import com.maggie.app.data.model.AgendaRowKind
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.Trip
import com.maggie.app.data.model.timeline
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.navigation.DeepLinks
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.screens.fullcalendar.openNavigation
import com.maggie.app.ui.screens.shared.parseColor
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/** How often the line and « dans 48 min » are recomputed. */
private const val TICK_MS = 20_000L

/** Past rows stay readable: dimmed, not struck out. */
private const val PAST_ALPHA = 0.72f

/** The next event sits on the Agenda's turquoise, this faint. */
private const val NEXT_TINT_ALPHA = 0.10f

/** The share of the Agenda's turquoise at the top of the card's gradient. */
private const val CARD_TINT_ALPHA = 0.12f

/** The owner's zone, which the « maintenant » line is read in. */
private val OWNER_ZONE: ZoneId = ZoneId.of("Europe/Paris")

private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun clockLabel(instant: Instant, zone: ZoneId = OWNER_ZONE): String = clockFormatter.format(instant.atZone(zone))

/** « mer. 22 oct. »: the short day of the card's title. */
internal fun agendaDayLabel(date: String): String =
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH))

/** « 2 passés · 4 à venir » today (a zero part omitted), « 3 prévus » for another day. */
internal fun agendaCountLabel(timeline: AgendaTimeline): String {
    val ahead = timeline.allDay.size + timeline.following.size
    if (!timeline.hasNowLine) return "$ahead prévu${if (ahead > 1) "s" else ""}"
    val done = timeline.past.size
    return listOfNotNull(
        done.takeIf { it > 0 }?.let { "$it passé${if (it > 1) "s" else ""}" },
        ahead.takeIf { it > 0 }?.let { "$it à venir" },
    ).joinToString(" · ")
}

/** « lieu · agenda », with « · fait » at the end of a done event's. */
internal fun agendaRowSub(event: AgendaHomeEvent, past: Boolean): String =
    listOfNotNull(event.locationShort, event.agendaName, if (past) "fait" else null).joinToString(" · ")

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
    val context = LocalContext.current
    val sportResume by koinViewModel<SportResumeViewModel>().resume.collectAsState()

    LaunchedEffect(day) { viewModel.show(day) }

    // The line follows the clock: the device's instant at the read, moved on by the time elapsed since.
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
        day = day,
        onOpenAgenda = { navigate(Screen.Calendar.route) },
        onOpenEvent = { viewModel.open(it.id) },
        onLaunch = { route -> navigate(route) },
        onRetry = viewModel::retry,
        resumeSport = sportResume != null,
        onGo = { openNavigation(context, it) },
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
                navigate(launchRouteFor(event.activity, event.id) ?: Screen.FinanceReviewActivity.route)
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
    day: HomeDay,
    onOpenAgenda: () -> Unit,
    onOpenEvent: (AgendaHomeEvent) -> Unit,
    onLaunch: (String) -> Unit,
    onRetry: () -> Unit,
    resumeSport: Boolean = false,
    onGo: (Trip) -> Unit = {},
) {
    if (state != null && state.events.isEmpty()) return

    val agenda = homeModuleColor(HomeModules.AGENDA)
    val surface = MaterialTheme.colorScheme.surface
    val countLabel = state?.let { agendaCountLabel(it.timeline(now, compact = false)) }
    HomeCard(
        title = state?.let { "Agenda · ${agendaDayLabel(it.date)}" } ?: "Agenda",
        module = HomeModules.AGENDA,
        onOpen = onOpenAgenda,
        trailing = countLabel?.let { label ->
            {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        containerBrush = Brush.linearGradient(
            0f to agenda.copy(alpha = CARD_TINT_ALPHA).compositeOver(surface),
            0.55f to surface,
            start = Offset(0f, 0f),
            end = Offset(0f, Float.POSITIVE_INFINITY),
        ),
    ) {
        when {
            state == null && error -> {
                Text("L'agenda n'a pas pu être chargé.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Réessayer") }
            }
            state == null && loading -> Text("Chargement…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state == null -> Unit
            else -> {
                val timeline = state.timeline(now, compact)
                timeline.allDay.forEach { AgendaRowItem(it, state.date, now, day, timeline.nextTripId, onOpenEvent, onLaunch, onGo, resumeSport) }
                timeline.past.forEach { AgendaRowItem(it, state.date, now, day, timeline.nextTripId, onOpenEvent, onLaunch, onGo, resumeSport) }
                if (timeline.hasNowLine && now != null) NowLine(now)
                timeline.following.forEach { AgendaRowItem(it, state.date, now, day, timeline.nextTripId, onOpenEvent, onLaunch, onGo, resumeSport) }
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
    val color = homeModuleColor(HomeModules.AGENDA)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(UiTags.HOME_AGENDA_NOW_LINE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("maintenant · ${clockLabel(now)}", fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
        Box(
            Modifier
                .weight(1f)
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0f)))),
        )
    }
}

@Composable
private fun AgendaRowItem(
    row: AgendaRow,
    date: String,
    now: Instant?,
    day: HomeDay,
    nextTripId: String?,
    onOpen: (AgendaHomeEvent) -> Unit,
    onLaunch: (String) -> Unit,
    onGo: (Trip) -> Unit,
    resumeSport: Boolean,
) {
    if (row.trips.isEmpty()) {
        AgendaEventRow(row, date, now, day, onOpen, onLaunch, resumeSport)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        row.trips.forEach { trip ->
            if (now != null && trip.id == nextTripId) HomeDepartureTile(trip, now, onGo = { onGo(trip) }) else HomeTripLine(trip, now)
        }
        AgendaEventRow(row, date, now, day, onOpen, onLaunch, resumeSport)
    }
}

@Composable
private fun AgendaEventRow(row: AgendaRow, date: String, now: Instant?, day: HomeDay, onOpen: (AgendaHomeEvent) -> Unit, onLaunch: (String) -> Unit, resumeSport: Boolean) {
    val event = row.event
    val past = row.kind == AgendaRowKind.PAST
    val next = row.kind == AgendaRowKind.NEXT
    val agendaModule = homeModuleColor(HomeModules.AGENDA)
    val agendaColor = event.agendaColor?.let(::parseColor) ?: MaterialTheme.colorScheme.primary
    val start = event.start
    val hint = when {
        next && now != null && start != null -> untilLabel(start, now)
        row.kind == AgendaRowKind.CURRENT -> "en cours"
        else -> null
    }
    val sub = agendaRowSub(event, past)

    val launchRoute = launchRouteFor(event.activity, event.id)
    val sport = event.activity == ACTIVITY_SPORT

    // The row's own tap and the ▶ are siblings, never nested: two buttons, each its own semantics node.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .alpha(if (past) PAST_ALPHA else 1f)
            .clip(RoundedCornerShape(14.dp))
            .background(if (next) agendaModule.copy(alpha = NEXT_TINT_ALPHA) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button) { onOpen(event) }
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag(UiTags.homeAgendaRow(event.id)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = rowTimeLabel(event, date).ifEmpty { if (event.allDay) "Journée" else "" },
                fontSize = 15.sp,
                fontWeight = if (next) FontWeight.Medium else FontWeight.Normal,
                color = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(48.dp),
            )
            Box(Modifier.size(width = 4.dp, height = 32.dp).clip(RoundedCornerShape(4.dp)).background(agendaColor))
            Column(Modifier.weight(1f)) {
                Text(
                    text = event.summary,
                    fontSize = 15.sp,
                    fontWeight = if (next) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sub.isNotEmpty()) {
                    Text(
                        text = sub,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (past) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            } else if (hint != null) {
                Text(text = hint, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = agendaModule, maxLines = 1)
            }
        }
        // « ▶ Lancer » on an event that is an activity — today only; the pill draws nothing for tomorrow.
        if (launchRoute != null) {
            ActivityLaunchButton(
                module = if (sport) HomeModules.HEALTH else HomeModules.FINANCE,
                activity = event.summary,
                day = day,
                onLaunch = { onLaunch(launchRoute) },
                resume = sport && resumeSport,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}
