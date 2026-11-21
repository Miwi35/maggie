package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Period
import com.maggie.app.ui.screens.shared.parseColor

/** The colour of a period: its agenda's. */
fun periodColor(period: Period, agendas: List<Agenda>, fallback: Color): Color =
    agendas.firstOrNull { it.id == period.agendaId }?.let { parseColor(it.color) } ?: fallback

/** The hatching of a period chip, as in the mockup: wide bands at 10 %, no solid fill, so the label stays readable. */
data class ChipHatching(val fill: Color, val stripe: Color, val band: Dp, val step: Dp)

fun periodChipHatching(color: Color) =
    ChipHatching(fill = Color.Transparent, stripe = color.copy(alpha = 0.10f), band = 6.dp, step = 12.dp)

@Composable
fun HatchedChipBackground(color: Color, modifier: Modifier = Modifier) {
    val hatching = periodChipHatching(color)
    Canvas(modifier) {
        val step = hatching.step.toPx()
        val band = hatching.band.toPx()
        // A 135° band of perpendicular width `band` spans band * sqrt(2) along the horizontal.
        val stroke = band * 1.4142135f
        var x = -size.height
        while (x < size.width + size.height) {
            drawLine(
                hatching.stripe,
                Offset(x, size.height),
                Offset(x + size.height, 0f),
                strokeWidth = stroke,
            )
            x += step * 1.4142135f
        }
    }
}

/** A bar hatched in [color]: what says « period » rather than « event » (MAG-815). */
@Composable
fun HatchedBar(color: Color, modifier: Modifier = Modifier, spacing: Dp = 5.dp) {
    Box(modifier.clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.25f))) {
        Canvas(Modifier.matchParentSize()) {
            val step = spacing.toPx()
            val stroke = Stroke(width = 1.5.dp.toPx())
            var x = -size.height
            while (x < size.width) {
                drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = stroke.width)
                x += step
            }
        }
    }
}
