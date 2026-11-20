package com.maggie.app.ui.screens.fullcalendar

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.Trip
import com.maggie.app.data.model.TripStep
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.isMaggieDark
import com.maggie.app.ui.theme.maggieSurface
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A trip of the day's list, at its departure (« Partir à 13:34 · depuis la maison », then one chip per step),
 * from `Periodes-Jour-Tel` (MAG-859). Past trips read « Parti à » and the whole block is dimmed. [isNext], the
 * next departure of the day, becomes a card with the event it leads to, the time left and the « Y aller » button.
 */
@Composable
fun TripLine(
    trip: Trip,
    now: Instant,
    zone: ZoneId,
    isNext: Boolean,
    modifier: Modifier = Modifier,
    event: ExpandedEvent? = null,
    onGo: () -> Unit = {},
) {
    val past = trip.departInstant?.isAfter(now) == false
    val surface = maggieSurface()
    val muted = surface.textMuted
    val dark = isMaggieDark()
    val accent = if (dark) MaggieTokens.Module.agenda.second else MaggieTokens.Module.agenda.first

    if (isNext) {
        Surface(
            modifier = modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 6.dp),
            shape = RoundedCornerShape(22.dp),
            color = surface.paper,
            border = BorderStroke(1.dp, accent),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = trip.departureHeadline(zone, now),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f, fill = false).alignByBaseline().testTag(UiTags.tripLine(trip.eventId)),
                    )
                    minutesLeft(trip, now)?.let {
                        Text(text = timeLeftLabel(it), fontSize = 14.sp, color = accent, softWrap = false, maxLines = 1, modifier = Modifier.alignByBaseline())
                    }
                }
                eventLine(event, zone)?.let { Text(text = it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface) }
                Text(text = trip.originLine(), fontSize = 13.sp, color = muted)
                StepChips(trip.steps, withLabels = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (trip.navigationUrl.isNotBlank()) {
                        Surface(
                            shape = CircleShape,
                            color = surface.text,
                            contentColor = surface.background,
                            modifier = Modifier
                                .height(44.dp)
                                .testTag(UiTags.TRIP_GO)
                                .clickable(onClick = onGo),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 18.dp),
                            ) {
                                Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Y aller", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    trip.computedTime(zone)?.let {
                        Text("à jour $it", fontSize = 12.sp, color = surface.caption, modifier = Modifier.testTag(UiTags.TRIP_UPDATED))
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (past) 0.6f else 1f)
            .padding(start = 76.dp, top = 4.dp, bottom = 8.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val headline = trip.headline(zone, now)
        val text = buildAnnotatedString {
            val split = headline.indexOf(" · ")
            if (split < 0 || past) {
                append(headline)
            } else {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(headline.substring(0, split)) }
                withStyle(SpanStyle(color = muted)) { append(headline.substring(split)) }
            }
        }
        Text(
            text = text,
            fontSize = if (past) 13.sp else 14.sp,
            color = if (past) muted else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(UiTags.tripLine(trip.eventId)),
        )
        StepChips(trip.steps, withLabels = false)
    }
}

/** « Récupérer Ael à 17:00 · Centre équestre du Thabor »; nothing for an event without a start hour. */
private fun eventLine(event: ExpandedEvent?, zone: ZoneId): String? {
    val start = event?.startAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val place = event.location?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    return "${event.title} à ${start.atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))}$place"
}

private fun minutesLeft(trip: Trip, now: Instant): Long? {
    val depart = trip.departInstant ?: return null
    return Duration.between(now, depart).toMinutes().takeIf { it >= 0 }
}

/** « dans 12 min », « dans 3 h 34 » past the hour. */
internal fun timeLeftLabel(minutes: Long): String =
    if (minutes < 60) "dans $minutes min" else "dans ${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StepChips(steps: List<TripStep>, withLabels: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEach { step ->
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.height(26.dp).padding(horizontal = 8.dp),
                ) {
                    Icon(iconOf(step.kind), contentDescription = null, modifier = Modifier.size(14.dp))
                    Text("${step.minutes} min", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    if (withLabels && !step.label.isNullOrBlank()) {
                        Text(step.label, fontSize = 12.sp, color = maggieSurface().caption)
                    }
                }
            }
        }
    }
}

private fun iconOf(kind: String): ImageVector = when (kind) {
    "drive" -> Icons.Filled.DirectionsCar
    "transit" -> Icons.Filled.DirectionsBus
    "bicycle" -> Icons.Filled.DirectionsBike
    else -> Icons.AutoMirrored.Filled.DirectionsWalk
}

/** Hands the trip's link (Waze by car, Google Maps otherwise) to whatever opens it; nothing happens when nothing does. */
fun openNavigation(context: Context, trip: Trip) {
    if (trip.navigationUrl.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trip.navigationUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}
