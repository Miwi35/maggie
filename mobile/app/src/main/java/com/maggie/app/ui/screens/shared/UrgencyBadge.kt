package com.maggie.app.ui.screens.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Report
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.urgencyColor

private fun urgencyIcon(urgency: String): ImageVector = when (urgency) {
    "critical" -> Icons.Default.Report
    "high" -> Icons.Default.KeyboardDoubleArrowUp
    "normal" -> Icons.Default.Remove
    else -> Icons.Default.KeyboardArrowDown
}

/**
 * A task's urgency, computed by the API (MAG-406): an icon and a word, never the
 * colour alone; the colour is on the icon, the word keeps the text's contrast.
 * An urgency the app does not know draws nothing rather than a wrong word.
 */
@Composable
fun UrgencyBadge(urgency: String?, modifier: Modifier = Modifier) {
    val label = urgency?.let { TaskConstants.URGENCY_LABELS[it] } ?: return
    Row(
        modifier = modifier.testTag(UiTags.TASK_URGENCY),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = urgencyIcon(urgency),
            contentDescription = null,
            tint = urgencyColor(urgency),
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
