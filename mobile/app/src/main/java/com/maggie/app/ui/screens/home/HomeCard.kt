package com.maggie.app.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.chartColor
import com.maggie.app.ui.theme.readableTextOn

private val MIN_TOUCH_TARGET = 48.dp

/** The share of the module colour in an action card's background. */
private const val ACTION_TINT_ALPHA = 0.12f

/**
 * The colour of [module]'s title, icon and pill on the Accueil, per mode, from the design tokens
 * (`MaggieTokens.Module` has four hues; the others borrow a role of the palette). An unknown module
 * reads in the accent.
 */
@Composable
fun homeModuleColor(module: String): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    fun pick(pair: Pair<Color, Color>) = if (dark) pair.second else pair.first
    return when (module) {
        HomeModules.AGENDA -> chartColor(4, dark)
        HomeModules.GROCERY -> pick(MaggieTokens.Rhythm.above)
        HomeModules.NUTRITION -> pick(MaggieTokens.Module.travail)
        HomeModules.HEALTH -> pick(MaggieTokens.Module.sport)
        HomeModules.FINANCE -> pick(MaggieTokens.Module.comptes)
        HomeModules.LEISURE -> pick(MaggieTokens.Module.saga)
        else -> MaterialTheme.colorScheme.primary
    }
}

/**
 * A card of the Accueil: the title in the module's colour, then [content] as the module draws it.
 * The whole header is the way into the module — [onOpen] is the card's own route or link — and is
 * at least 48 dp tall.
 */
@Composable
fun HomeCard(
    title: String,
    module: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val color = homeModuleColor(module)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTags.homeCard(module)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_TOUCH_TARGET)
                .testTag(UiTags.homeCardHeader(module))
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            content = content,
        )
    }
}

/**
 * A card that asks the user for something: [module]'s colour at 12 % as background, the [icon], the [title]
 * with its [moment] under it, and one pill, [actionLabel], that runs [onAction].
 */
@Composable
fun HomeActionCard(
    icon: ImageVector,
    title: String,
    moment: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    module: String = HomeModules.TASKS,
) {
    val color = homeModuleColor(module)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTags.homeCard(module)),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = ACTION_TINT_ALPHA)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = moment,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(MaggieTokens.Radius.pill),
                colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = readableTextOn(color)),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET),
            ) {
                Text(actionLabel)
            }
        }
    }
}
