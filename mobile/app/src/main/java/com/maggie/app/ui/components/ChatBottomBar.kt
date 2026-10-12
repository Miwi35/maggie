package com.maggie.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags

/**
 * The band under the content: the mic, and the field that opens the
 * conversation. Drawn on a window tall enough to pay 72 dp for it —
 * [com.maggie.app.ui.layout.ChatEntry.BOTTOM_BAR]. A short window gets
 * [ChatRailActions] instead.
 */
@Composable
fun ChatBottomBar(
    onOpenChat: () -> Unit,
    onMicClick: () -> Unit = {},
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The app draws edge to edge and the Scaffold adds no inset: without this the
                // bar sits under a tablet's taskbar, which gets the taps meant for « Demander à Maggie… ».
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatMicButton(onMicClick)

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .testTag(UiTags.CHAT_OPEN)
                    .clickable(onClick = onOpenChat),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    text = "Demander à Maggie...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    }
}

/**
 * The same two ways into the conversation, at the top of the rail, for a window too
 * short for a band under its content (MAG-35, recette return).
 *
 * The field becomes a `SmallFloatingActionButton`, which is what the rail's header slot
 * is for: « demander à Maggie » is the app's primary action, and a 360 dp placeholder
 * would not fit in 80 dp of rail anyway. Small and not full size — 40 dp instead of 56 —
 * because the header already costs the seven destinations ~160 dp of a 411 dp window.
 * The tags are the bar's own, so a journey or a test that taps `chat_open` taps it in
 * either layout.
 */
@Composable
fun ChatRailActions(
    onOpenChat: () -> Unit,
    onMicClick: () -> Unit = {},
) {
    Spacer(modifier = Modifier.height(8.dp))

    SmallFloatingActionButton(
        onClick = onOpenChat,
        modifier = Modifier.testTag(UiTags.CHAT_OPEN),
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
    ) {
        Icon(Icons.Default.Chat, contentDescription = "Demander à Maggie")
    }

    ChatMicButton(onMicClick)
}

/** The mic, which opens the conversation in voice mode. Shared by the three surfaces. */
@Composable
internal fun ChatMicButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(UiTags.CHAT_MIC)) {
        Icon(Icons.Default.Mic, contentDescription = "Micro")
    }
}
