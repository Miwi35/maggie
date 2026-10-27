package com.maggie.app.ui.screens.grocery

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags

/** The months as the picker shows them, January first. */
internal val SEASON_MONTH_LABELS = listOf(
    "Janv.", "Févr.", "Mars", "Avr.", "Mai", "Juin",
    "Juil.", "Août", "Sept.", "Oct.", "Nov.", "Déc.",
)

/**
 * « de saison » / « hors saison » (MAG-494), from the `inSeason` the API computes
 * for this month. Null — a product without a season, a free-text line — shows nothing.
 */
@Composable
internal fun SeasonBadge(inSeason: Boolean?, modifier: Modifier = Modifier) {
    if (inSeason == null) return

    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (inSeason) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (inSeason) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 2.dp).testTag(UiTags.SEASON_BADGE),
    ) {
        Text(
            text = if (inSeason) "de saison" else "hors saison",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
