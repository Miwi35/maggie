package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.theme.maggieSurface
import com.maggie.app.ui.theme.readableTextOn

/** The Material icon behind the names a category's `icon` field takes (« shopping-cart »); null when the name is not one we draw. */
internal fun categoryIcon(name: String?): ImageVector? = name?.trim()?.lowercase()?.let(CategoryIconVectors::find)

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
    val background = fill ?: maggieSurface().raised
    val tint = if (fill == null) MaterialTheme.colorScheme.onSurfaceVariant else readableTextOn(fill)
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
