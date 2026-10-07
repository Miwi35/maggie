package com.maggie.app.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags

/**
 * A list and the detail of what is selected in it, side by side (MAG-263).
 *
 * Like [AppShell] it takes **no decision**: `showsDetailPane` is
 * [AppLayout.detailPaneFits] turned into a `Chrome` by the caller.
 *
 * - `false` — one pane, the list. The detail is not drawn here: on a narrow window it
 *   is a sheet or a route of its own, and nothing changes from what the phone has.
 * - `true` — the list at [PANE_MIN_WIDTH], the detail taking the rest. [detail] is
 *   `null` while nothing is selected, and [placeholder] says so: an empty right half
 *   reads as a bug.
 */
@Composable
fun ListDetailPane(
    showsDetailPane: Boolean,
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    if (!showsDetailPane) {
        Box(modifier = modifier.fillMaxSize().testTag(UiTags.LIST_PANE)) { list() }
        return
    }

    Row(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.width(PANE_MIN_WIDTH).fillMaxSize().testTag(UiTags.LIST_PANE)) { list() }
        VerticalDivider()
        Box(modifier = Modifier.weight(1f).fillMaxSize().testTag(UiTags.DETAIL_PANE)) {
            if (detail != null) detail() else DetailPanePlaceholder(placeholder)
        }
    }
}

/** What the detail pane says while nothing is selected. */
@Composable
fun DetailPanePlaceholder(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
