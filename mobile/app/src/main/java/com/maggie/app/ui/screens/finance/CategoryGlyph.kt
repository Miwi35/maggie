package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The Material icon behind the names a category's `icon` field takes (« shopping-cart »); null when the name is not one we draw. */
internal fun categoryIcon(name: String?): ImageVector? = when (name?.trim()?.lowercase()) {
    "home" -> Icons.Default.Home
    "shopping-cart" -> Icons.Default.ShoppingCart
    "repeat" -> Icons.Default.Repeat
    "water-drop" -> Icons.Default.WaterDrop
    "bolt" -> Icons.Default.Bolt
    "wifi" -> Icons.Default.Wifi
    "tv" -> Icons.Default.Tv
    "cloud" -> Icons.Default.Cloud
    "sports-esports" -> Icons.Default.SportsEsports
    "trending-up" -> Icons.AutoMirrored.Filled.TrendingUp
    "local-gas-station" -> Icons.Default.LocalGasStation
    "account-balance" -> Icons.Default.AccountBalance
    "error-outline" -> Icons.Default.ErrorOutline
    "payments" -> Icons.Default.Payments
    "star" -> Icons.Default.Star
    "volunteer-activism" -> Icons.Default.VolunteerActivism
    "undo" -> Icons.AutoMirrored.Filled.Undo
    "redeem" -> Icons.Default.Redeem
    "restaurant" -> Icons.Default.Restaurant
    "directions-car" -> Icons.Default.DirectionsCar
    "flight" -> Icons.Default.Flight
    "school" -> Icons.Default.School
    "favorite" -> Icons.Default.Favorite
    "fitness-center" -> Icons.Default.FitnessCenter
    "movie" -> Icons.Default.Movie
    "savings" -> Icons.Default.Savings
    else -> null
}

internal fun categoryColorOrNull(hex: String?): Color? {
    val code = hex?.let(CategoryColors::normalize) ?: return null
    return Color(code.substring(1).toLong(16) or 0xFF000000L)
}

/**
 * A category's colour and icon on its list row: a disc of its colour holding its icon.
 * A name we do not draw still shows as a generic tag, so the icon the owner set is never
 * silently absent; no icon at all leaves the plain colour.
 */
@Composable
internal fun CategoryGlyph(color: String?, icon: String?, modifier: Modifier = Modifier) {
    val fill = categoryColorOrNull(color)
    val vector = categoryIcon(icon) ?: if (icon.isNullOrBlank()) null else Icons.AutoMirrored.Filled.Label
    val background = fill ?: MaterialTheme.colorScheme.surfaceVariant
    val tint = when {
        fill == null -> MaterialTheme.colorScheme.onSurfaceVariant
        fill.luminance() > 0.5f -> Color.Black
        else -> Color.White
    }
    val description = listOfNotNull(
        fill?.let { "couleur ${CategoryColors.normalize(color.orEmpty())}" },
        icon?.takeIf { it.isNotBlank() }?.let { "icône $it" },
    ).joinToString(", ").ifEmpty { "sans couleur ni icône" }

    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(background)
            .semantics { contentDescription = "Repère : $description" },
        contentAlignment = Alignment.Center,
    ) {
        if (vector != null) {
            Icon(vector, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}
