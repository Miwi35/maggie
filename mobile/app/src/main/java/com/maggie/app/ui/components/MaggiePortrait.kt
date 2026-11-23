package com.maggie.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.layout.PORTRAIT_SIZE
import com.maggie.app.ui.theme.MaggieTokens

/**
 * Maggie folded away, on a tablet (MAG-624, `Chat-Tablette` with `ouvert` off): her face, 64 dp,
 * bottom right; a tap brings the conversation drawer. It carries the bar's `chat_open` tag, so a
 * journey that opens the conversation does the same thing on either layout. Unread messages put a
 * violet pill on its top right corner (MAG-965, `Portrait-Pastille-Tablette`).
 */
@Composable
fun MaggiePortrait(onClick: () -> Unit, unreadCount: Int = 0, modifier: Modifier = Modifier) {
    val label = when {
        unreadCount <= 0 -> "Ouvrir la conversation avec Maggie"
        unreadCount == 1 -> "Ouvrir la conversation avec Maggie, 1 message non lu"
        else -> "Ouvrir la conversation avec Maggie, $unreadCount messages non lus"
    }
    Box(modifier = modifier.size(PORTRAIT_SIZE)) {
        Surface(
            modifier = Modifier
                .size(PORTRAIT_SIZE)
                .testTag(UiTags.CHAT_OPEN)
                .semantics { contentDescription = label }
                .clickable(role = Role.Button, onClick = onClick),
            shape = CircleShape,
            shadowElevation = 6.dp,
        ) {
            MaggieAvatar(size = PORTRAIT_SIZE)
        }
        if (unreadCount > 0) {
            PortraitBadge(unreadCount, Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp))
        }
    }
}

@Composable
private fun PortraitBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .testTag(UiTags.CHAT_UNREAD_BADGE)
            .clearAndSetSemantics {}
            .border(3.dp, MaterialTheme.colorScheme.background, CircleShape)
            .background(MaggieTokens.Brand.primary, CircleShape)
            .heightIn(min = 24.dp)
            .widthIn(min = 24.dp)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = unreadLabel(count),
            color = MaggieTokens.Brand.onPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
        )
    }
}
