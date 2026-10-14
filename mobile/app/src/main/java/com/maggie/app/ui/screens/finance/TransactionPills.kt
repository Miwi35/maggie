package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.TransactionPill
import com.maggie.app.data.model.TransactionPillKind
import com.maggie.app.ui.UiTags

/** The one row of pills a line wears in the lists; a pill opens the line's sheet. Nothing is drawn for a line with none. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransactionPills(pills: List<TransactionPill>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (pills.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        pills.forEach { pill ->
            AssistChip(
                onClick = onOpen,
                label = { Text(pill.label) },
                modifier = Modifier.testTag(pillTag(pill.kind)),
            )
        }
    }
}

// The transfer and rejection pills keep the tags the journeys already address them by.
fun pillTag(kind: TransactionPillKind): String = when (kind) {
    TransactionPillKind.INTERNAL -> UiTags.TRANSFER_BADGE
    TransactionPillKind.REJECTED -> UiTags.REJECTION_BADGE
    TransactionPillKind.SERIES -> UiTags.PILL_SERIES
    TransactionPillKind.SHARED -> UiTags.PILL_SHARED
    TransactionPillKind.TO_SORT -> UiTags.PILL_TO_SORT
}
