package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.RETROSPECT_AVOIDABLE
import com.maggie.app.data.model.RETROSPECT_KEEP

/**
 * The satisfaction of a spend, « À conserver » or « Superflue » (MAG-458): the verdict the line has is selected, a tap on
 * the other sends it, a tap on the selected one takes it back ([onJudge] decides). The caller draws it only for a spend
 * that [com.maggie.app.data.model.TransactionToSort.judgeable] — an income has none.
 */
@Composable
fun SatisfactionChips(
    retrospect: String,
    enabled: Boolean,
    onJudge: (String) -> Unit,
    keepTag: String,
    avoidableTag: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = retrospect == RETROSPECT_KEEP,
            onClick = { onJudge(RETROSPECT_KEEP) },
            enabled = enabled,
            label = { Text("À conserver") },
            modifier = Modifier.testTag(keepTag),
        )
        FilterChip(
            selected = retrospect == RETROSPECT_AVOIDABLE,
            onClick = { onJudge(RETROSPECT_AVOIDABLE) },
            enabled = enabled,
            label = { Text("Superflue") },
            modifier = Modifier.testTag(avoidableTag),
        )
    }
}
