package com.maggie.app.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.Trip
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.fullcalendar.GoButton
import com.maggie.app.ui.screens.fullcalendar.StepChips
import com.maggie.app.ui.screens.fullcalendar.minutesLeft
import com.maggie.app.ui.screens.fullcalendar.timeLeftLabel
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.maggieSurface
import java.time.Instant
import java.time.ZoneId

private const val TILE_FILL_ALPHA = 0.55f
private const val TILE_BORDER_ALPHA = 0.28f
private const val PAST_ALPHA = 0.6f
private val ZONE: ZoneId = ZoneId.of("Europe/Paris")

/**
 * The next departure of the day as a tile above its event on the Accueil's Agenda card (MAG-866), from
 * `Accueil-Tel`: the time, the time left, the origin and door-to-door duration, the steps, « Y aller » and « à jour ».
 */
@Composable
internal fun HomeDepartureTile(trip: Trip, now: Instant, onGo: () -> Unit, modifier: Modifier = Modifier) {
    val accent = homeModuleColor(HomeModules.AGENDA)
    val surface = maggieSurface()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaggieTokens.Night.background.copy(alpha = TILE_FILL_ALPHA))
            .border(BorderStroke(1.dp, accent.copy(alpha = TILE_BORDER_ALPHA)), RoundedCornerShape(18.dp))
            .padding(14.dp)
            .testTag(UiTags.HOME_DEPARTURE_TILE),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = trip.departureHeadline(ZONE, now),
                style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                modifier = Modifier.alignByBaseline().testTag(UiTags.HOME_DEPARTURE_TIME),
            )
            minutesLeft(trip, now)?.let {
                Text(text = timeLeftLabel(it), fontSize = 14.sp, color = accent, modifier = Modifier.alignByBaseline())
            }
        }
        Text(text = trip.originLine(), fontSize = 13.sp, color = surface.textMuted)
        StepChips(trip.steps, withLabels = true)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (trip.navigationUrl.isNotBlank()) {
                Box(Modifier.testTag(UiTags.HOME_DEPARTURE_GO)) { GoButton(onGo) }
            }
            trip.computedTime(ZONE)?.let {
                Text("à jour $it", fontSize = 12.sp, color = surface.caption, modifier = Modifier.testTag(UiTags.TRIP_UPDATED))
            }
        }
    }
}

/**
 * Any other trip as one line above its event: « Partir à 18:05 · depuis École Jean-Moulin ». With a null [now]
 * (tomorrow) it always reads « Partir »; a trip gone reads « Parti à », dimmed.
 */
@Composable
internal fun HomeTripLine(trip: Trip, now: Instant?, modifier: Modifier = Modifier) {
    val muted = maggieSurface().textMuted
    val clock = now ?: Instant.MIN
    val past = trip.departInstant?.isAfter(clock) == false
    val headline = trip.headline(ZONE, clock)
    val split = headline.indexOf(" · ")
    val text = buildAnnotatedString {
        if (split < 0 || past || trip.isReturn) {
            append(headline)
        } else {
            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(headline.substring(0, split)) }
            withStyle(SpanStyle(color = muted)) { append(headline.substring(split)) }
        }
    }
    Text(
        text = text,
        fontSize = 13.sp,
        color = if (past) muted else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 22.dp)
            .alpha(if (past) PAST_ALPHA else 1f)
            .padding(horizontal = 8.dp)
            .testTag(UiTags.homeTripLine(trip.eventId)),
    )
}
