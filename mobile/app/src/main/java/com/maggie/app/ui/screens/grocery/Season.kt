package com.maggie.app.ui.screens.grocery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot

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

/**
 * The months a product is in season, one chip per month, big enough for a finger.
 * None selected is a product without a season.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeasonMonthsDialog(
    productName: String,
    months: List<Int>?,
    onConfirm: (List<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(months.orEmpty().toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // A dialog is its own window: its tags need their own root (MAG-98).
        modifier = Modifier.uiTagRoot(),
        title = { Text("Saison de $productName") },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag(UiTags.SEASON_MONTHS),
            ) {
                SEASON_MONTH_LABELS.forEachIndexed { index, label ->
                    val month = index + 1
                    FilterChip(
                        selected = month in selected,
                        onClick = { selected = if (month in selected) selected - month else selected + month },
                        label = { Text(label) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.sorted()) }) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

/** Opens the season months of a product card, showing those ticked. */
@Composable
internal fun SeasonMonthsButton(months: List<Int>?, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(UiTags.PRODUCT_SEASON_EDIT)) {
        Text(seasonSummary(months))
    }
}

/** The months ticked, spelled out: « Mois de saison : janv., sept., oct. ». */
internal fun seasonSummary(months: List<Int>?): String =
    if (months.isNullOrEmpty()) {
        "Mois de saison : aucun"
    } else {
        "Mois de saison : " + months.mapNotNull { SEASON_MONTH_LABELS.getOrNull(it - 1)?.lowercase() }.joinToString(", ")
    }
