package com.maggie.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.layout.PORTRAIT_SIZE

/**
 * Maggie folded away, on a tablet (MAG-624, `Chat-Tablette` with `ouvert` off): her face, 64 dp,
 * bottom right; a tap brings the conversation drawer. It carries the bar's `chat_open` tag, so a
 * journey that opens the conversation does the same thing on either layout.
 */
@Composable
fun MaggiePortrait(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .size(PORTRAIT_SIZE)
            .testTag(UiTags.CHAT_OPEN)
            .semantics { contentDescription = "Ouvrir la conversation avec Maggie" }
            .clickable(role = Role.Button, onClick = onClick),
        shape = CircleShape,
        shadowElevation = 6.dp,
    ) {
        MaggieAvatar(size = PORTRAIT_SIZE)
    }
}
