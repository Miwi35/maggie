package com.maggie.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.isMaggieDark

internal val CARD_RADIUS = 22.dp
internal val ROW_RADIUS = 18.dp
internal val SELECTED_BORDER = 1.5.dp

/** The surface a card is drawn on: `paper`, or `raised` once selected (mock-ups, finance pages). */
internal fun maggieCardContainer(dark: Boolean, raised: Boolean): Color {
    val surface = if (dark) MaggieTokens.surfaceDark else MaggieTokens.surfaceLight

    return if (raised) surface.raised else surface.paper
}

/**
 * The Veilleuse card (MAG-579): `paper`, 22 dp corners (18 for [listRow]), no shadow, no border —
 * never Material's default grey. Every screen draws its cards with this one, not with `Card`.
 *
 * [selected] is `raised` with a 1.5 dp rim in the accent; [raised] alone is a card sitting on a
 * card, without the rim. [containerColor] is for a state tint (attention, error): it replaces the
 * surface, not the shape.
 */
@Composable
fun MaggieCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    raised: Boolean = false,
    listRow: Boolean = false,
    containerColor: Color? = null,
    elevation: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container = containerColor ?: maggieCardContainer(isMaggieDark(), selected || raised)
    val colors: CardColors = CardDefaults.cardColors(
        containerColor = container,
        contentColor = containerColor?.let { scheme.contentColorFor(it) }?.takeIf { it != Color.Unspecified } ?: scheme.onSurface,
    )
    val shape = RoundedCornerShape(if (listRow) ROW_RADIUS else CARD_RADIUS)
    val border = if (selected) BorderStroke(SELECTED_BORDER, scheme.primary) else null
    val elevations = CardDefaults.cardElevation(defaultElevation = elevation)

    if (onClick == null) {
        Card(modifier = modifier, shape = shape, colors = colors, elevation = elevations, border = border, content = content)
    } else {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, elevation = elevations, border = border, content = content)
    }
}
