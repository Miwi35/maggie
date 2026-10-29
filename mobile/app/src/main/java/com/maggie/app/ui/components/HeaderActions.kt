package com.maggie.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.interruption.ConfirmationEntry
import com.maggie.app.ui.interruption.InterruptionsUiState
import com.maggie.app.ui.interruption.SettledEntry
import com.maggie.app.ui.interruption.TripEntry

/** What a header does with the panel's taps: the navigation owns them. */
class InterruptionsActions(
    val onTrip: (TripEntry) -> Unit = {},
    val onQuestion: (PushPayload) -> Unit = {},
    val onConfirmation: (ConfirmationEntry) -> Unit = {},
    val onSettled: (SettledEntry) -> Unit = {},
)

/**
 * The loupe then the bell at the right of every header (MAG-712), drawn by the screen's own bar: the frame
 * provides what they need, the bars only ask for them. Outside the graph: nothing.
 */
val LocalHeaderActions = compositionLocalOf<@Composable RowScope.() -> Unit> { {} }

@Composable
fun RowScope.HeaderActions(
    state: InterruptionsUiState,
    actions: InterruptionsActions,
    onSearch: () -> Unit,
) {
    IconButton(onClick = onSearch, modifier = Modifier.size(48.dp).testTag(UiTags.HEADER_SEARCH)) {
        Icon(Icons.Default.Search, contentDescription = "Rechercher")
    }
    MaggieBell(state, actions)
}

/** The bell, its violet badge (nothing at 0) and the panel it opens under itself. */
@Composable
fun MaggieBell(state: InterruptionsUiState, actions: InterruptionsActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    var bottomPx by remember { mutableIntStateOf(0) }
    val count = state.pendingCount
    Box(
        modifier = Modifier.onGloballyPositioned {
            bottomPx = (it.positionInWindow().y + it.size.height).toInt()
        },
    ) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp).testTag(UiTags.HEADER_BELL)) {
            if (count > 0) {
                BadgedBox(
                    badge = {
                        Badge(modifier = Modifier.testTag(UiTags.HEADER_BELL_BADGE)) { Text("$count") }
                    },
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = "Interruptions de Maggie, $count en attente")
                }
            } else {
                Icon(Icons.Default.Notifications, contentDescription = "Interruptions de Maggie")
            }
        }
    }
    if (open) {
        InterruptionsPanel(
            state = state,
            topOffset = with(LocalDensity.current) { bottomPx.toDp() },
            actions = InterruptionsActions(
                onTrip = { open = false; actions.onTrip(it) },
                onQuestion = { open = false; actions.onQuestion(it) },
                onConfirmation = { open = false; actions.onConfirmation(it) },
                onSettled = { open = false; actions.onSettled(it) },
            ),
            onDismiss = { open = false },
        )
    }
}
