package com.maggie.app.ui.screens.departure

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.Trip
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.fullcalendar.StepChips
import com.maggie.app.ui.theme.MaggieTokens
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

/**
 * « Pars maintenant » (MAG-658, mockup `Depart-Tel`): what a departure alert opens over the lock
 * screen. Always the night palette, like the other screens meant to be read at a glance. [trip] is
 * null while it loads or when the alert's trip is gone: the screen then carries the title and the
 * two buttons only. [onGo] is null when the alert carries no navigation link.
 */
@Composable
fun DepartureScreen(
    title: String,
    trip: Trip?,
    now: ZonedDateTime,
    onGo: (() -> Unit)?,
    onAlreadyOnTheWay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val night = MaggieTokens.Night
    val brand = MaggieTokens.Brand
    val bubble = MaggieTokens.surfaceDark.raised
    val muted = MaggieTokens.surfaceDark.textMuted

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(night.background)
            .background(
                Brush.radialGradient(
                    colors = listOf(brand.primary.copy(alpha = 0.28f), Color.Transparent),
                    radius = 900f,
                ),
            )
            .padding(start = 20.dp, end = 20.dp, top = 72.dp, bottom = 32.dp)
            .testTag(UiTags.DEPARTURE_SCREEN),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(now.format(CLOCK), fontSize = 64.sp, fontWeight = FontWeight.Light, color = MaggieTokens.surfaceDark.text)
            Text(now.format(DAY), fontSize = 14.sp, color = muted)
        }

        Column(modifier = Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .border(5.dp, brand.primary.copy(alpha = 0.25f), CircleShape)
                        .background(Brush.radialGradient(listOf(brand.primaryHover, brand.primaryLight)), CircleShape),
                )
                Text("Maggie · ${now.format(CLOCK)}", fontSize = 14.sp, color = brand.primaryHover, modifier = Modifier.padding(bottom = 8.dp))
            }
            Box(
                modifier = Modifier.background(bubble, RoundedCornerShape(topStart = 6.dp, topEnd = 26.dp, bottomEnd = 26.dp, bottomStart = 26.dp)),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(title, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, color = MaggieTokens.surfaceDark.text)
                    trip?.let { detail ->
                        val origin = detail.originLabel.takeIf { it.isNotBlank() }
                        Text(
                            listOfNotNull(origin, "${detail.durationMinutes} min porte à porte").joinToString(" · "),
                            fontSize = 15.sp,
                            color = muted,
                        )
                        StepChips(detail.steps, withLabels = true)
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onGo != null) {
                            Pill(
                                modifier = Modifier.weight(1f).testTag(UiTags.DEPARTURE_GO),
                                background = MaggieTokens.surfaceDark.text,
                                content = night.background,
                                onClick = onGo,
                            ) {
                                Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Y aller", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Pill(
                            modifier = Modifier.testTag(UiTags.DEPARTURE_DISMISS),
                            background = night.text.copy(alpha = 0.08f),
                            content = MaggieTokens.surfaceDark.text,
                            onClick = onAlreadyOnTheWay,
                        ) {
                            Text("Déjà en route", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        if (trip != null && trip.navigationUrl.isNotBlank()) {
            Text(
                if (trip.mode == Trip.MODE_DRIVE) "Waze" else "Google Maps",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                color = MaggieTokens.surfaceDark.caption,
            )
        }
    }
}

@Composable
private fun Pill(
    modifier: Modifier,
    background: Color,
    content: Color,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
) {
    Surface(onClick = onClick, shape = CircleShape, color = background, contentColor = content, modifier = modifier.height(52.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier.padding(horizontal = 20.dp),
        ) { label() }
    }
}
