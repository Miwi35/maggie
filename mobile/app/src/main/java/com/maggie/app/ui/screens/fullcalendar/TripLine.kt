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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.Trip
import com.maggie.app.data.model.TripStep
import com.maggie.app.ui.UiTags
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The line under an event (« Partir à 13:34 · depuis la maison », then one chip per step), from the
 * « Trajets » mockup (MAG-658). Past trips are muted and read « Parti à ». [isNext], the next departure
 * of the day, becomes a card with the time left and the « Y aller » button.
 */
@Composable
fun TripLine(
    trip: Trip,
    now: Instant,
    zone: ZoneId,
    isNext: Boolean,
    modifier: Modifier = Modifier,
    onGo: () -> Unit = {},
) {
    val past = trip.departInstant?.isAfter(now) == false
    val headline = trip.headline(zone, now)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    if (isNext) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag(UiTags.tripLine(trip.eventId)),
                    )
                    minutesLeft(trip, now)?.let {
                        Text(text = timeLeftLabel(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(text = "${trip.durationMinutes} min porte à porte", style = MaterialTheme.typography.bodySmall, color = muted)
                StepChips(trip.steps, withLabels = true)
                if (trip.navigationUrl.isNotBlank()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface,
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
                            Text("Y aller", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
            style = MaterialTheme.typography.bodySmall,
            fontSize = if (past) 13.sp else 14.sp,
            color = if (past) muted else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(UiTags.tripLine(trip.eventId)),
        )
        StepChips(trip.steps, withLabels = false)
    }
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
                    Text("${step.minutes} min", style = MaterialTheme.typography.labelMedium)
                    if (withLabels && !step.label.isNullOrBlank()) {
                        Text(step.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
